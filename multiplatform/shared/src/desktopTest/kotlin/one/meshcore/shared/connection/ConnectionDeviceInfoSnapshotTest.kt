package one.meshcore.shared.connection

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import one.meshcore.shared.fixtures.cancelAndJoinBlocking
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.persistence.meshDatabase
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The identity and capability read-outs Settings -> My Device and region discovery need: the four
 * firmware/capability columns the connect path persists have to reach the snapshot, and the ad-hoc
 * repeater predicate has to be computed from the firmware the radio reports.
 */
class ConnectionDeviceInfoSnapshotTest {
    private lateinit var dbFile: File
    private lateinit var db: MeshDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var lastConnection: LastConnectionStore

    private val radioId = Uuid.normalized("A0000000-0000-0000-0000-000000000077")

    @BeforeTest
    fun setUp() {
        dbFile = File.createTempFile("mesh-device-info-snapshot", ".db").also { it.delete() }
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

    private suspend fun connected(session: FakeMeshSession): ConnectionManager = ConnectionManager(
        database = db,
        scope = scope,
        linkFactory = { FakeMeshLink() },
        sessionFactory = { session },
        nowEpochSeconds = { 1_000.0 },
        ports = ConnectionManagerPorts(
            newRadioId = { radioId },
            lastConnectionStore = lastConnection,
        ),
    ).also { it.connect(DEVICE_HANDLE) }

    @Test
    fun theSnapshotCarriesTheFirmwareAndCapabilityColumnsMyDeviceRenders() {
        runBlocking {
            val capabilities = defaultCapabilities().copy(
                model = ESP32_MODEL,
                firmwareBuild = "14 Mar 2026",
                maxContacts = 250,
                maxChannels = 12,
            )
            val manager = connected(FakeMeshSession(capabilities = capabilities))

            val snapshot = manager.radioSnapshot()!!
            assertEquals(ESP32_MODEL, snapshot.manufacturerName)
            assertEquals("14 Mar 2026", snapshot.buildDate)
            assertEquals(250, snapshot.maxContacts)
            assertEquals(12, snapshot.maxChannels)
        }
    }

    @Test
    fun aPreV13RadioDoesNotOfferAdHocRepeaterRequests() {
        runBlocking {
            val old = defaultCapabilities().copy(firmwareVersion = 12, version = "v1.15.0")
            val manager = connected(FakeMeshSession(capabilities = old))

            assertFalse(manager.radioSnapshot()!!.supportsAdHocRepeaterRequest)
        }
    }

    @Test
    fun aForkThatLeavesTheVersionCodeUnbumpedIsReadFromTheVersionString() {
        runBlocking {
            val fork = defaultCapabilities().copy(firmwareVersion = 12, version = "v1.16.0")
            val manager = connected(FakeMeshSession(capabilities = fork))

            assertTrue(manager.radioSnapshot()!!.supportsAdHocRepeaterRequest)
        }
    }

    @Test
    fun aV13RadioOffersAdHocRepeaterRequests() {
        runBlocking {
            val modern = defaultCapabilities().copy(firmwareVersion = 13, version = "")
            val manager = connected(FakeMeshSession(capabilities = modern))

            assertTrue(manager.radioSnapshot()!!.supportsAdHocRepeaterRequest)
        }
    }

    private companion object {
        const val DEVICE_HANDLE = "AA:BB:CC:DD:EE:77"
    }
}
