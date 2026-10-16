package one.meshcore.shared.connection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import one.meshcore.shared.fixtures.cancelAndJoinBlocking
import one.meshcore.shared.meshcore.AutoAddConfig
import one.meshcore.shared.meshcore.DeviceOtherParams
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.persistence.meshDatabase
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * A Settings screen opened long after connect has to see what the radio holds now, not what it held at
 * connect. Each case changes the radio behind the app's back — the same shape as another client, or the
 * radio's own screen, changing a setting mid-session — and then asks the settings snapshots.
 */
class ConnectionRefreshDeviceSettingsTest {
    private lateinit var dbFile: File
    private lateinit var db: MeshDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var lastConnection: LastConnectionStore

    private val radioId = Uuid.normalized("A0000000-0000-0000-0000-000000000099")

    @BeforeTest
    fun setUp() {
        dbFile = File.createTempFile("mesh-refresh-device-settings", ".db").also { it.delete() }
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

    @Test
    fun aRetuneMadeBehindTheAppsBackReachesTheRadioScreen() {
        runBlocking {
            val session = FakeMeshSession()
            val connected = manager(session)
            connected.connect(DEVICE_HANDLE)
            assertEquals(CONNECT_FREQUENCY_KHZ, connected.radioSnapshot()!!.frequencyKHz)

            session.setRadio(
                frequency = RETUNED_FREQUENCY_MHZ,
                bandwidth = RETUNED_BANDWIDTH_KHZ,
                spreadingFactor = RETUNED_SPREADING_FACTOR,
                codingRate = RETUNED_CODING_RATE,
                clientRepeat = null,
            )
            connected.refreshDeviceSettings()

            val snapshot = connected.radioSnapshot()!!
            assertEquals(RETUNED_FREQUENCY_KHZ, snapshot.frequencyKHz)
            assertEquals(RETUNED_SPREADING_FACTOR, snapshot.spreadingFactor)
            assertNotEquals(CONNECT_FREQUENCY_KHZ, snapshot.frequencyKHz)
        }
    }

    @Test
    fun theAdvertPolicyAndTelemetryModesAreRereadToo() {
        runBlocking {
            val session = FakeMeshSession()
            val connected = manager(session)
            connected.connect(DEVICE_HANDLE)
            assertEquals(0, connected.locationSettingsSnapshot()!!.advertLocationPolicyRaw)

            session.setOtherParams(
                DeviceOtherParams(
                    manualAddContacts = true,
                    telemetryModeEnvironment = 0,
                    telemetryModeLocation = 0,
                    telemetryModeBase = TELEMETRY_MODE,
                    advertisementLocationPolicy = SHARE_POLICY,
                    multiAcks = 1,
                ),
            )
            connected.refreshDeviceSettings()

            assertEquals(SHARE_POLICY, connected.locationSettingsSnapshot()!!.advertLocationPolicyRaw)
            val other = connected.deviceOtherParamsSnapshot()!!
            assertEquals(TELEMETRY_MODE, other.telemetryModeBase)
            assertEquals(1, other.multiAcks)
        }
    }

    @Test
    fun theDataPartitionKeyIsNeverReMintedByARefresh() {
        runBlocking {
            val session = FakeMeshSession()
            val connected = manager(session)
            connected.connect(DEVICE_HANDLE)
            val rowId = lastConnection.deviceId()!!

            connected.refreshDeviceSettings()
            connected.refreshDeviceSettings()

            val row = db.deviceDao().getById(rowId)!!
            assertEquals(radioId, row.radioID)
            assertEquals(rowId, row.id)
            assertEquals(radioId, lastConnection.radioId())
            assertEquals(1, db.deviceDao().listAll().size)
        }
    }

    @Test
    fun aRenamedRadioReachesTheDeviceRowAndLastConnectionHeader() {
        runBlocking {
            val session = FakeMeshSession()
            val connected = manager(session)
            connected.connect(DEVICE_HANDLE)
            assertEquals("Test", lastConnection.deviceName())

            session.setName(IMPORTED_NAME)
            connected.refreshDeviceSettings()

            assertEquals(IMPORTED_NAME, db.deviceDao().getById(lastConnection.deviceId()!!)!!.nodeName)
            assertEquals(IMPORTED_NAME, lastConnection.deviceName())
        }
    }

    @Test
    fun aRefusedSelfInfoReadLeavesTheRow() = runBlocking {
        val session = FakeMeshSession()
        val connected = manager(session)
        connected.connect(DEVICE_HANDLE)
        session.setName(IMPORTED_NAME)
        session.selfInfoFailure = IllegalStateException("radio down")
        session.selfInfoFailuresRemaining = 1

        connected.refreshDeviceSettings()

        val row = db.deviceDao().getById(lastConnection.deviceId()!!)!!
        assertEquals("Test", row.nodeName)
        assertEquals(CONNECT_FREQUENCY_KHZ, connected.radioSnapshot()!!.frequencyKHz)
    }

    @Test
    fun aCancelledSelfInfoReadStopsTheRefresh() = runBlocking {
        val session = FakeMeshSession()
        val connected = manager(session)
        connected.connect(DEVICE_HANDLE)
        val cancelled = CancellationException("stopped")
        session.selfInfoFailure = cancelled
        session.selfInfoFailuresRemaining = 1

        val thrown = assertFailsWith<CancellationException> { connected.refreshDeviceSettings() }

        assertSame(cancelled, thrown)
        assertEquals("Test", db.deviceDao().getById(lastConnection.deviceId()!!)!!.nodeName)
    }

    @Test
    fun aSelfInfoReadErrorStopsTheRefresh() = runBlocking {
        val session = FakeMeshSession()
        val connected = manager(session)
        connected.connect(DEVICE_HANDLE)
        val probe = RefreshSelfInfoProbeError()
        session.selfInfoFailure = probe
        session.selfInfoFailuresRemaining = 1

        val thrown = assertFailsWith<RefreshSelfInfoProbeError> { connected.refreshDeviceSettings() }

        assertSame(probe, thrown)
        assertEquals("Test", db.deviceDao().getById(lastConnection.deviceId()!!)!!.nodeName)
    }

    @Test
    fun aRefusedAutoAddReadStillPersistsSelfInfo() = runBlocking {
        val session = FakeMeshSession()
        val connected = manager(session)
        connected.connect(DEVICE_HANDLE)
        session.setName(IMPORTED_NAME)
        session.autoAddConfig = AutoAddConfig(bitmask = REFUSED_AUTO_ADD_BITS, maxHops = REFUSED_MAX_HOPS)
        session.failAutoAddConfigWith = IllegalStateException("refused")

        connected.refreshDeviceSettings()

        val row = db.deviceDao().getById(lastConnection.deviceId()!!)!!
        assertEquals(IMPORTED_NAME, row.nodeName)
        assertEquals(0, row.autoAddConfig)
        assertEquals(0, row.autoAddMaxHops)
    }

    @Test
    fun aCancelledAutoAddReadDoesNotPersistTheRefresh() = runBlocking {
        val session = FakeMeshSession()
        val connected = manager(session)
        connected.connect(DEVICE_HANDLE)
        session.setName(IMPORTED_NAME)
        val cancelled = CancellationException("stopped")
        session.failAutoAddConfigWith = cancelled

        val thrown = assertFailsWith<CancellationException> { connected.refreshDeviceSettings() }

        assertSame(cancelled, thrown)
        assertEquals("Test", db.deviceDao().getById(lastConnection.deviceId()!!)!!.nodeName)
    }

    @Test
    fun aDisconnectedRefreshIsANoOp() {
        runBlocking {
            val disconnected = manager(FakeMeshSession())

            disconnected.refreshDeviceSettings()

            assertNull(disconnected.radioSnapshot())
        }
    }

    private companion object {
        const val DEVICE_HANDLE = "AA:BB:CC:DD:EE:99"
        const val CONNECT_FREQUENCY_KHZ = 915_000L
        const val RETUNED_FREQUENCY_MHZ = 869.525
        const val RETUNED_FREQUENCY_KHZ = 869_525L
        const val RETUNED_BANDWIDTH_KHZ = 125.0
        const val RETUNED_SPREADING_FACTOR = 11
        const val RETUNED_CODING_RATE = 8
        const val SHARE_POLICY = 2
        const val TELEMETRY_MODE = 3
        const val IMPORTED_NAME = "Imported Ridge"
        const val REFUSED_AUTO_ADD_BITS = 7
        const val REFUSED_MAX_HOPS = 4
    }
}

private class RefreshSelfInfoProbeError : Error("probe")
