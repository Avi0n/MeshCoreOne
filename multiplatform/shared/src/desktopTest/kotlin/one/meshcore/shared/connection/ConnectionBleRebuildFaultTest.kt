package one.meshcore.shared.connection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import one.meshcore.shared.fixtures.cancelAndJoinBlocking
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.persistence.meshDatabase
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A cancelled rebuild of a live Bluetooth session is rethrown and leaves the link up. Any other
 * failure, including a thrown error, is logged and drops that link.
 */
class ConnectionBleRebuildFaultTest {
    private lateinit var dbFile: File
    private lateinit var db: MeshDatabase
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        dbFile = File.createTempFile("mesh-ble-rebuild-fault", ".db").also { it.delete() }
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
    fun aCancelledRebuildLeavesTheLiveLinkUp() = runBlocking {
        val stopped = CancellationException("stopped")
        val session = FakeMeshSession().apply { failQueryWith = stopped }
        val link = FakeMeshLink()
        val manager = manager(session, link)

        val thrown = assertFailsWith<CancellationException> {
            manager.rebuildConnectedBleAppStack(DEVICE_HANDLE)
        }

        assertTrue(thrown === stopped || thrown.cause === stopped)
        assertFalse(link.disconnected)
        assertEquals(ConnectionState.Connecting(DEVICE_HANDLE), manager.state.value)
    }

    @Test
    fun aRebuildErrorDropsTheLiveLink() = runBlocking {
        val session = FakeMeshSession().apply { failQueryWith = BleRebuildProbeError() }
        val link = FakeMeshLink()
        val manager = manager(session, link)

        manager.rebuildConnectedBleAppStack(DEVICE_HANDLE)

        assertTrue(link.disconnected)
        assertEquals(ConnectionState.Disconnected, manager.state.value)
    }

    private fun manager(session: FakeMeshSession, link: FakeMeshLink) = ConnectionManager(
        database = db,
        scope = scope,
        linkFactory = { link },
        sessionFactory = { session },
        nowEpochSeconds = { NOW_SECONDS },
    ).apply {
        this.link = link
        currentDeviceId = DEVICE_HANDLE
        currentTransportType = TransportType.BLUETOOTH
    }

    private companion object {
        const val DEVICE_HANDLE = "AA:BB:CC:DD:EE:70"
        const val NOW_SECONDS = 1_700_000_000.0
    }
}

private class BleRebuildProbeError : Error("probe")
