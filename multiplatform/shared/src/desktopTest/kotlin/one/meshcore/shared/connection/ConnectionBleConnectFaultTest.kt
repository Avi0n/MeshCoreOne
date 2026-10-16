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
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * A cancelled BLE connect attempt is abandoned and rethrown. Any other failure, including a thrown
 * error, stays on the retry path and is surfaced after the attempt budget.
 */
class ConnectionBleConnectFaultTest {
    private lateinit var dbFile: File
    private lateinit var db: MeshDatabase
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        dbFile = File.createTempFile("mesh-ble-connect-fault", ".db").also { it.delete() }
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
    fun aCancelledBleConnectIsAbandonedAndRethrown() = runBlocking {
        val stopped = CancellationException("stopped")
        val link = FakeMeshLink().apply { failConnectWith = stopped }
        val manager = manager(link)

        val thrown = assertFailsWith<CancellationException> { manager.connect(DEVICE_HANDLE) }

        assertSame(stopped, thrown)
        assertEquals(1, link.connectCount)
        assertTrue(link.disconnected)
        assertEquals(ConnectionState.Disconnected, manager.state.value)
    }

    @Test
    fun aBleConnectErrorRetriesThenSurfaces() = runBlocking {
        val probe = BleConnectProbeError()
        val link = FakeMeshLink().apply { failConnectWith = probe }
        val manager = manager(link)

        val thrown = assertFailsWith<BleConnectProbeError> { manager.connect(DEVICE_HANDLE) }

        assertSame(probe, thrown)
        assertEquals(ConnectRetryPolicy.DEFAULT_MAX_ATTEMPTS, link.connectCount)
        assertTrue(link.disconnected)
        assertEquals(ConnectionState.Disconnected, manager.state.value)
    }

    private fun manager(link: FakeMeshLink) = ConnectionManager(
        database = db,
        scope = scope,
        linkFactory = { link },
        sessionFactory = { FakeMeshSession() },
        nowEpochSeconds = { NOW_SECONDS },
        ports = ConnectionManagerPorts(
            connectRetryPolicy = ConnectRetryPolicy(baseBackoff = FAST_BACKOFF),
        ),
    )

    private companion object {
        const val DEVICE_HANDLE = "AA:BB:CC:DD:EE:6C"
        const val NOW_SECONDS = 1_700_000_000.0
        val FAST_BACKOFF = 1.milliseconds
    }
}

private class BleConnectProbeError : Error("probe")
