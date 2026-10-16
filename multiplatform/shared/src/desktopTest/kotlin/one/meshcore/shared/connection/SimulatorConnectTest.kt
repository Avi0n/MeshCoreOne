package one.meshcore.shared.connection

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import one.meshcore.shared.fixtures.cancelAndJoinBlocking
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.persistence.meshDatabase
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import one.meshcore.shared.simulator.MockDataProvider
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * iOS `ConnectionManager.simulatorConnect` seeds the store and reaches Ready without a radio
 * handshake, so Play review / debug Continue Demo is not an empty shell.
 */
class SimulatorConnectTest {
    private lateinit var dbFile: File
    private lateinit var db: MeshDatabase
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        dbFile = File.createTempFile("mesh-simulator", ".db").also { it.delete() }
        db = meshDatabase(dbFile.absolutePath)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @AfterTest
    fun tearDown() {
        scope.cancelAndJoinBlocking()
        db.close()
        dbFile.delete()
    }

    @Test
    fun simulatorConnectReachesReadyAndSeedsCatalog() = runBlocking {
        val session = FakeMeshSession()
        val last = LastConnectionStore(InMemoryPreferencesStore())
        val manager = ConnectionManager(
            database = db,
            scope = scope,
            linkFactory = { FakeMeshLink() },
            sessionFactory = { session },
            nowEpochSeconds = { FIXED_CLOCK },
            ports = ConnectionManagerPorts(
                lastConnectionStore = last,
            ),
        )

        manager.simulatorConnect()

        val handle = MockDataProvider.SIMULATOR_HANDLE
        assertEquals(ConnectionState.Ready(handle), manager.state.value)
        assertFalse(session.started)
        assertTrue(manager.services != null)

        val radio = MockDataProvider.simulatorDeviceID
        assertEquals(1, db.deviceDao().listByRadio(radio).size)
        assertEquals("Sim", db.deviceDao().getById(radio)?.nodeName)
        assertEquals(8, db.contactDao().listByRadio(radio).size)
        assertEquals(4, db.channelDao().listByRadio(radio).size)
        assertTrue(db.messageDao().listByRadio(radio).size > 20)
        assertEquals(radio, last.deviceId())
        assertEquals(radio, last.radioId())
        assertEquals(handle, last.handle())
        assertEquals("MeshCore One Sim", last.deviceName())
    }

    @Test
    fun reseedingKeepsExistingContactAvatarsAndDoesNotDuplicateSnapshots() = runBlocking {
        val radio = MockDataProvider.simulatorDeviceID
        val manager = ConnectionManager(
            database = db,
            scope = scope,
            linkFactory = { FakeMeshLink() },
            sessionFactory = { FakeMeshSession() },
            nowEpochSeconds = { FIXED_CLOCK },
        )
        manager.simulatorConnect()
        val alice = db.contactDao().getById(MockDataProvider.aliceChenID)!!
        db.contactDao().upsert(alice.copy(avatarImageData = byteArrayOf(0x0A, 0x0B)))
        val snapshotCount = db.nodeStatusSnapshotDao().listByNode(
            MockDataProvider.mockPublicKey(seed = 80),
        ).size

        manager.simulatorConnect()

        assertTrue(
            db.contactDao().getById(MockDataProvider.aliceChenID)!!.avatarImageData.contentEquals(
                byteArrayOf(0x0A, 0x0B),
            ),
        )
        assertEquals(
            snapshotCount,
            db.nodeStatusSnapshotDao().listByNode(MockDataProvider.mockPublicKey(seed = 80)).size,
        )
    }

    private companion object {
        const val FIXED_CLOCK = 1_700_000_000.0
    }
}
