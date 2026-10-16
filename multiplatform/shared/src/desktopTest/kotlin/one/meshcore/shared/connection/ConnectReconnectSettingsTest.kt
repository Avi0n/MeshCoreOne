package one.meshcore.shared.connection

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import one.meshcore.shared.fixtures.cancelAndJoinBlocking
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.DeviceEntity
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.persistence.meshDatabase
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The connect ceremony writes what the radio reported onto the row the radio already has. Every column
 * the radio does not report is a setting the user chose — the curated region list, the default flood
 * scope, the battery curve, the parameters saved before repeat mode — and the ceremony runs on every
 * link-up, reconnects included, so a write that reset them would erase them silently and repeatedly.
 */
class ConnectReconnectSettingsTest {
    private lateinit var dbFile: File
    private lateinit var db: MeshDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var lastConnection: LastConnectionStore

    private val radioId = Uuid.normalized("A0000000-0000-0000-0000-000000000007")

    @BeforeTest
    fun setUp() {
        dbFile = File.createTempFile("mesh-connect-settings", ".db").also { it.delete() }
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

    // A fresh session per connect, as a reconnect gets: the ceremony must carry settings across the
    // rebuilt session, not across a session object that happens to be reused.
    private fun manager(newSession: () -> FakeMeshSession) = ConnectionManager(
        database = db,
        scope = scope,
        linkFactory = { FakeMeshLink() },
        sessionFactory = { newSession() },
        nowEpochSeconds = { 1_000.0 },
        ports = ConnectionManagerPorts(
            newRadioId = { radioId },
            lastConnectionStore = lastConnection,
        ),
    )

    private fun repeatingSession() = FakeMeshSession(
        capabilities = defaultCapabilities().copy(clientRepeat = true),
    )

    @Test
    fun everyPerRadioSettingSurvivesAReconnect() = runBlocking {
        db.deviceDao().upsert(curatedRow())
        val manager = manager(::repeatingSession)

        manager.connect(DEVICE)
        manager.disconnect()
        manager.connect(DEVICE)

        val row = db.deviceDao().getById(ROW_ID)!!
        assertEquals(curatedRow().userChosenSettings(), row.userChosenSettings())
    }

    @Test
    fun leavingRepeatModeAfterAReconnectStillRestoresTheMeshPreset() = runBlocking {
        var current = { FakeMeshSession() }
        val manager = manager { current() }
        manager.connect(DEVICE)
        manager.enableClientRepeat(REPEAT_FREQUENCY_KHZ)

        // The link drops and the app reconnects. The radio now reports itself in repeat mode on the
        // repeat frequency — the state in which erasing the saved parameters strands the user, because
        // the only frequency left to restore would be the repeat one.
        val repeating = FakeMeshSession(
            selfInfo = defaultSelfInfo().copy(radioFrequency = REPEAT_FREQUENCY_MHZ),
            capabilities = defaultCapabilities().copy(clientRepeat = true),
        )
        current = { repeating }
        manager.disconnect()
        manager.connect(DEVICE)

        manager.disableClientRepeat()

        assertEquals(MESH_FREQUENCY_MHZ, repeating.lastRadio!!.frequency)
        assertEquals(false, repeating.lastRadio!!.clientRepeat)
    }

    @Test
    fun theRadiosOwnReportStillOverwritesTheRowOnReconnect() = runBlocking {
        db.deviceDao().upsert(curatedRow())
        val manager = manager(::repeatingSession)

        manager.connect(DEVICE)

        val row = db.deviceDao().getById(ROW_ID)!!
        assertEquals("Test", row.nodeName)
        assertEquals(REPORTED_FREQUENCY_KHZ, row.frequency)
        assertEquals(REPORTED_MAX_CONTACTS, row.maxContacts)
        assertEquals(true, row.clientRepeat)
    }

    @Test
    fun aRadioNoLongerInRepeatModeDropsOnlyItsOrphanedPreRepeatParameters() = runBlocking {
        db.deviceDao().upsert(curatedRow())
        // Repeat mode was turned off elsewhere (CLI, another client): the saved parameters describe
        // settings that are already back in use, so keeping them would restore a stale preset later.
        val manager = manager { FakeMeshSession() }

        manager.connect(DEVICE)

        val row = db.deviceDao().getById(ROW_ID)!!
        assertNull(row.preRepeatFrequency)
        assertNull(row.preRepeatBandwidth)
        assertNull(row.preRepeatSpreadingFactor)
        assertNull(row.preRepeatCodingRate)
        assertEquals(KNOWN_REGIONS, row.knownRegions)
        assertEquals(FLOOD_SCOPE, row.defaultFloodScopeName)
        assertEquals(OCV_PRESET, row.ocvPreset)
        assertEquals(LAST_CONTACT_SYNC, row.lastContactSync)
    }

    @Test
    fun aFirstEverPairingStartsFromTheEntityDefaults() = runBlocking {
        val manager = manager { FakeMeshSession() }

        manager.connect(DEVICE)

        val row = db.deviceDao().getById(ROW_ID)!!
        assertEquals(radioId, row.radioID)
        assertEquals(emptyList(), row.knownRegions)
        assertNull(row.defaultFloodScopeName)
        assertNull(row.preRepeatFrequency)
        assertNull(row.ocvPreset)
        assertEquals(0L, row.lastContactSync)
    }

    /**
     * The columns no radio ever reports, so nothing in the connect path may write them. Compared as one
     * value rather than field by field: a failure then names every setting that was erased, not just the
     * first one asserted.
     */
    private fun DeviceEntity.userChosenSettings() = listOf(
        knownRegions,
        defaultFloodScopeName,
        preRepeatFrequency,
        preRepeatBandwidth,
        preRepeatSpreadingFactor,
        preRepeatCodingRate,
        ocvPreset,
        customOCVArrayString,
        lastContactSync,
    )

    /** A row carrying the settings a user curated over a season of use, none of them radio-reported. */
    @Suppress("LongMethod")
    private fun curatedRow() = DeviceEntity(
        id = ROW_ID,
        radioID = radioId,
        publicKey = ByteArray(32) { 0x01 },
        nodeName = "stale",
        firmwareVersion = 0,
        firmwareVersionString = "",
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
        manualAddContacts = false,
        autoAddConfig = 0,
        autoAddMaxHops = 0,
        multiAcks = 0,
        telemetryModeBase = 0,
        telemetryModeLoc = 0,
        telemetryModeEnv = 0,
        advertLocationPolicy = 0,
        lastConnected = 0.0,
        lastContactSync = LAST_CONTACT_SYNC,
        isActive = true,
        knownRegions = KNOWN_REGIONS,
        defaultFloodScopeName = FLOOD_SCOPE,
        preRepeatFrequency = PRE_REPEAT_FREQUENCY_KHZ,
        preRepeatBandwidth = PRE_REPEAT_BANDWIDTH_HZ,
        preRepeatSpreadingFactor = PRE_REPEAT_SPREADING_FACTOR,
        preRepeatCodingRate = PRE_REPEAT_CODING_RATE,
        ocvPreset = OCV_PRESET,
        customOCVArrayString = CUSTOM_OCV,
    )

    private companion object {
        const val DEVICE = "AA:BB:CC:DD:EE:01"
        val ROW_ID: Uuid = StableIds.uuid(DEVICE.encodeToByteArray())

        val KNOWN_REGIONS = listOf("eu-868", "au-915")
        const val FLOOD_SCOPE = "county-north"
        const val PRE_REPEAT_FREQUENCY_KHZ = 868_500L
        const val PRE_REPEAT_BANDWIDTH_HZ = 125_000L
        const val PRE_REPEAT_SPREADING_FACTOR = 10
        const val PRE_REPEAT_CODING_RATE = 6
        const val OCV_PRESET = "lipo"
        const val CUSTOM_OCV = "4200,4100,4000,3900"
        const val LAST_CONTACT_SYNC = 1_699_000_000L

        /** The canned handshake values of [defaultSelfInfo] / [defaultCapabilities]. */
        const val REPORTED_FREQUENCY_KHZ = 915_000L
        const val REPORTED_MAX_CONTACTS = 100
        const val MESH_FREQUENCY_MHZ = 915.0

        /** The repeat preset the radio retunes to, well clear of the mesh's own frequency. */
        const val REPEAT_FREQUENCY_KHZ = 869_525L
        const val REPEAT_FREQUENCY_MHZ = 869.525
    }
}
