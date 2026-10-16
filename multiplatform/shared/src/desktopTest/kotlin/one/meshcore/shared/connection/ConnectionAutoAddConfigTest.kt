package one.meshcore.shared.connection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import one.meshcore.shared.contact.AutoAddConfigBits
import one.meshcore.shared.contact.AutoAddMode
import one.meshcore.shared.contact.notifyNodeStorageFull
import one.meshcore.shared.fixtures.cancelAndJoinBlocking
import one.meshcore.shared.meshcore.AutoAddConfig
import one.meshcore.shared.meshcore.BridgeErrorCode
import one.meshcore.shared.meshcore.MeshBridgeException
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.DeviceEntity
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.persistence.meshDatabase
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import one.meshcore.shared.settings.NodesAutoAddDraft
import one.meshcore.shared.settings.toDraft
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The connect ceremony reads the radio's auto-add policy instead of assuming it. Settings → Advanced →
 * Nodes renders the Device row, and its Apply writes the rendered state back, so a row seeded with zeros
 * would both misreport the radio and then overwrite the real policy with all-off.
 */
class ConnectionAutoAddConfigTest {
    private lateinit var dbFile: File
    private lateinit var db: MeshDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var lastConnection: LastConnectionStore

    private val radioId = Uuid.normalized("A0000000-0000-0000-0000-000000000001")

    @BeforeTest
    fun setUp() {
        dbFile = File.createTempFile("mesh-auto-add", ".db").also { it.delete() }
        db = meshDatabase(dbFile.absolutePath)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        lastConnection = LastConnectionStore(InMemoryPreferencesStore())
    }

    @AfterTest
    fun tearDown() {
        scope.cancelAndJoinBlocking()
        db.close()
        dbFile.delete()
    }

    private fun manager(session: FakeMeshSession) = ConnectionManager(
        database = db,
        scope = scope,
        linkFactory = { FakeMeshLink() },
        sessionFactory = { session },
        nowEpochSeconds = { 1_000.0 },
        ports = ConnectionManagerPorts(
            newRadioId = { radioId },
            lastConnectionStore = lastConnection,
        ),
    )

    private fun selectiveSession(firmware: String = SUPPORTED_FIRMWARE) = FakeMeshSession(
        selfInfo = defaultSelfInfo().copy(manualAddContacts = true),
        capabilities = defaultCapabilities().copy(version = firmware),
    )

    @Test
    fun connectPersistsThePolicyTheRadioReports() = runBlocking {
        val session = selectiveSession()
        session.autoAddConfig = AutoAddConfig(bitmask = SELECTED_BITS, maxHops = MAX_HOPS)
        val manager = manager(session)

        manager.connect(DEVICE)

        assertEquals(1, session.getAutoAddConfigCount)
        val row = db.deviceDao().getById(ROW_ID)!!
        assertEquals(SELECTED_BITS, row.autoAddConfig)
        assertEquals(MAX_HOPS, row.autoAddMaxHops)
    }

    @Test
    fun nodesSettingsRenderTheRadioPolicyNotAllOff() = runBlocking {
        val session = selectiveSession()
        session.autoAddConfig = AutoAddConfig(bitmask = SELECTED_BITS, maxHops = MAX_HOPS)
        val manager = manager(session)

        manager.connect(DEVICE)

        val snapshot = manager.nodesAutoAddSnapshot()!!
        assertEquals(AutoAddMode.SELECTED_TYPES, snapshot.mode)
        assertTrue(snapshot.autoAddContacts)
        assertTrue(snapshot.autoAddRepeaters)
        assertFalse(snapshot.autoAddRoomServers)
        assertTrue(snapshot.overwriteOldest)
        assertEquals(MAX_HOPS, snapshot.toDraft().autoAddMaxHops)
    }

    @Test
    fun applyKeepsThePolicyItWasShownWhenNothingChanged() = runBlocking {
        val session = selectiveSession()
        session.autoAddConfig = AutoAddConfig(bitmask = SELECTED_BITS, maxHops = MAX_HOPS)
        val manager = manager(session)
        manager.connect(DEVICE)

        // The screen round-trips the snapshot it rendered; a user who opens Nodes and taps Apply
        // must not rewrite the radio's policy.
        manager.applyNodesAutoAdd(manager.nodesAutoAddSnapshot()!!.toDraft())

        assertEquals(AutoAddConfig(bitmask = SELECTED_BITS, maxHops = MAX_HOPS), session.lastAutoAddConfig)
    }

    @Test
    fun firmwareWithoutTheCommandIsNeitherQueriedNorOverwritten() = runBlocking {
        db.deviceDao().upsert(seededDevice())
        val session = selectiveSession(firmware = UNSUPPORTED_FIRMWARE)
        val manager = manager(session)

        manager.connect(DEVICE)

        assertEquals(0, session.getAutoAddConfigCount)
        val row = db.deviceDao().getById(ROW_ID)!!
        assertEquals(SELECTED_BITS, row.autoAddConfig)
        assertEquals(MAX_HOPS, row.autoAddMaxHops)
    }

    @Test
    fun firmwareWithoutTheCommandIsNotWrittenOnApplyEither() = runBlocking {
        db.deviceDao().upsert(seededDevice())
        val session = selectiveSession(firmware = UNSUPPORTED_FIRMWARE)
        val manager = manager(session)
        manager.connect(DEVICE)

        manager.applyNodesAutoAdd(NodesAutoAddDraft(mode = AutoAddMode.MANUAL))

        assertNull(session.lastAutoAddConfig)
        assertEquals(SELECTED_BITS, db.deviceDao().getById(ROW_ID)!!.autoAddConfig)
    }

    @Test
    fun enablingOverwriteOldestClearsTheStorageFullFlag() = runBlocking {
        val session = selectiveSession()
        session.autoAddConfig = AutoAddConfig(bitmask = 0, maxHops = MAX_HOPS)
        val manager = manager(session)
        manager.connect(DEVICE)
        val contactService = manager.services!!.contactService
        contactService.notifyNodeStorageFull()
        assertTrue(contactService.isNodeStorageFull.value)

        manager.applyNodesAutoAdd(
            NodesAutoAddDraft(
                mode = AutoAddMode.SELECTED_TYPES,
                autoAddContacts = true,
                autoAddRepeaters = true,
                overwriteOldest = true,
                autoAddMaxHops = MAX_HOPS,
            ),
        )

        assertFalse(contactService.isNodeStorageFull.value)
    }

    @Test
    fun applyingWithoutOverwriteOldestLeavesTheStorageFullFlag() = runBlocking {
        val session = selectiveSession()
        session.autoAddConfig = AutoAddConfig(bitmask = AutoAddConfigBits.CONTACTS, maxHops = MAX_HOPS)
        val manager = manager(session)
        manager.connect(DEVICE)
        val contactService = manager.services!!.contactService
        contactService.notifyNodeStorageFull()

        manager.applyNodesAutoAdd(
            NodesAutoAddDraft(
                mode = AutoAddMode.SELECTED_TYPES,
                autoAddContacts = true,
                overwriteOldest = false,
                autoAddMaxHops = MAX_HOPS,
            ),
        )

        assertTrue(contactService.isNodeStorageFull.value)
    }

    @Test
    fun aRefusedPolicyWriteThrowsRatherThanShowingAPolicyTheRadioRejected() = runBlocking {
        val session = selectiveSession()
        session.autoAddConfig = AutoAddConfig(bitmask = SELECTED_BITS, maxHops = MAX_HOPS)
        val manager = manager(session)
        manager.connect(DEVICE)
        session.refuseAutoAddConfigWrite = true

        assertFailsWith<AutoAddConfigVerificationFailed> {
            manager.applyNodesAutoAdd(NodesAutoAddDraft(mode = AutoAddMode.ALL))
        }
        val row = db.deviceDao().getById(ROW_ID)!!
        assertEquals(SELECTED_BITS, row.autoAddConfig)
        assertEquals(MAX_HOPS, row.autoAddMaxHops)
    }

    @Test
    fun aRefusedManualAddWriteThrowsBeforeTheAutoAddPolicyIsTouched() = runBlocking {
        val session = selectiveSession()
        val manager = manager(session)
        manager.connect(DEVICE)
        session.refuseOtherParamsWrite = true

        assertFailsWith<OtherParamsVerificationFailed> {
            manager.applyNodesAutoAdd(NodesAutoAddDraft(mode = AutoAddMode.ALL))
        }
        assertNull(session.lastAutoAddConfig)
    }

    @Test
    fun aLiveReadReturnsTheRadioPolicy() = runBlocking {
        val session = selectiveSession()
        session.autoAddConfig = AutoAddConfig(bitmask = SELECTED_BITS, maxHops = MAX_HOPS)
        val manager = manager(session)
        manager.connect(DEVICE)
        val stored = AutoAddConfig(bitmask = 0, maxHops = 0)

        val read = manager.readAutoAddConfig(SUPPORTED_FIRMWARE, stored)

        assertEquals(AutoAddConfig(bitmask = SELECTED_BITS, maxHops = MAX_HOPS), read)
    }

    @Test
    fun aRefusedLiveReadKeepsTheCallerStoredPolicy() = runBlocking {
        val session = selectiveSession()
        val manager = manager(session)
        manager.connect(DEVICE)
        session.autoAddConfig = AutoAddConfig(bitmask = 0, maxHops = 0)
        session.failAutoAddConfigWith = IllegalStateException("refused")
        val stored = AutoAddConfig(bitmask = SELECTED_BITS, maxHops = MAX_HOPS)

        val read = manager.readAutoAddConfig(SUPPORTED_FIRMWARE, stored)

        assertEquals(stored, read)
    }

    @Test
    fun aCancelledLiveReadStopsTheCaller() = runBlocking {
        val session = selectiveSession()
        val manager = manager(session)
        manager.connect(DEVICE)
        val cancelled = CancellationException("stopped")
        session.failAutoAddConfigWith = cancelled
        val stored = AutoAddConfig(bitmask = SELECTED_BITS, maxHops = MAX_HOPS)

        val thrown = assertFailsWith<CancellationException> {
            manager.readAutoAddConfig(SUPPORTED_FIRMWARE, stored)
        }

        assertSame(cancelled, thrown)
    }

    @Test
    fun aLiveReadErrorStopsTheCaller() = runBlocking {
        val session = selectiveSession()
        val manager = manager(session)
        manager.connect(DEVICE)
        val probe = LiveAutoAddProbeError()
        session.failAutoAddConfigWith = probe
        val stored = AutoAddConfig(bitmask = SELECTED_BITS, maxHops = MAX_HOPS)

        val thrown = assertFailsWith<LiveAutoAddProbeError> {
            manager.readAutoAddConfig(SUPPORTED_FIRMWARE, stored)
        }

        assertSame(probe, thrown)
    }

    @Test
    fun aRejectedReadKeepsTheStoredPolicy() = runBlocking {
        db.deviceDao().upsert(seededDevice())
        val session = selectiveSession()
        session.failAutoAddConfigWith = MeshBridgeException(BridgeErrorCode.DEVICE_ERROR, "unsupported")
        val manager = manager(session)

        manager.connect(DEVICE)

        val row = db.deviceDao().getById(ROW_ID)!!
        assertEquals(SELECTED_BITS, row.autoAddConfig)
        assertEquals(MAX_HOPS, row.autoAddMaxHops)
    }

    @Suppress("LongMethod")
    private fun seededDevice() = DeviceEntity(
        id = ROW_ID,
        radioID = radioId,
        publicKey = ByteArray(32) { 0x01 },
        nodeName = "seed",
        firmwareVersion = 0,
        firmwareVersionString = SUPPORTED_FIRMWARE,
        manufacturerName = "",
        buildDate = "",
        maxContacts = 0,
        maxChannels = 0,
        frequency = 0,
        bandwidth = 0,
        spreadingFactor = 0,
        codingRate = 0,
        txPower = 0,
        maxTxPower = 0,
        latitude = 0.0,
        longitude = 0.0,
        blePin = 0,
        clientRepeat = false,
        pathHashMode = 0,
        manualAddContacts = true,
        autoAddConfig = SELECTED_BITS,
        autoAddMaxHops = MAX_HOPS,
        multiAcks = 0,
        telemetryModeBase = 0,
        telemetryModeLoc = 0,
        telemetryModeEnv = 0,
        advertLocationPolicy = 0,
        lastConnected = 0.0,
        lastContactSync = 0,
        isActive = true,
        connectionMethods = emptyList(),
    )

    private companion object {
        const val DEVICE = "device-1"
        val ROW_ID: Uuid = StableIds.uuid(DEVICE.encodeToByteArray())

        /** Auto-add chats and repeaters, overwriting the oldest non-favorite when storage fills. */
        val SELECTED_BITS = AutoAddConfigBits.OVERWRITE_OLDEST or
            AutoAddConfigBits.CONTACTS or
            AutoAddConfigBits.REPEATERS
        const val MAX_HOPS = 2

        const val SUPPORTED_FIRMWARE = "v1.15.0"
        const val UNSUPPORTED_FIRMWARE = "v1.11.0"
    }
}

private class LiveAutoAddProbeError : Error("probe")
