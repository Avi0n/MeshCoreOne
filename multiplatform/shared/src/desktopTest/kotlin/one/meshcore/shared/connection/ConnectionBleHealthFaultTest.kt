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
import kotlin.time.Duration.Companion.milliseconds

/**
 * A cancelled opportunistic BLE reconnect is rethrown. Any other failure, including a thrown
 * error, is logged and stays inside the health check after the connect budget is spent.
 */
class ConnectionBleHealthFaultTest {
    private lateinit var dbFile: File
    private lateinit var db: MeshDatabase
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        dbFile = File.createTempFile("mesh-ble-health-fault", ".db").also { it.delete() }
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
    fun aCancelledOpportunisticReconnectIsRethrown() = runBlocking {
        val stopped = CancellationException("stopped")
        val link = FakeMeshLink().apply { failConnectWith = stopped }
        val manager = manager(link)
        manager.wantsConnection = true
        manager.currentDeviceId = DEVICE_HANDLE

        val thrown = assertFailsWith<CancellationException> { manager.checkBLEConnectionHealth() }

        assertSame(stopped, thrown)
        assertEquals(1, link.connectCount)
        assertEquals(ConnectionState.Disconnected, manager.state.value)
    }

    @Test
    fun anOpportunisticReconnectErrorIsLoggedAndSwallowed() = runBlocking {
        val probe = BleHealthProbeError()
        val link = FakeMeshLink().apply { failConnectWith = probe }
        val manager = manager(link)
        manager.wantsConnection = true
        manager.currentDeviceId = DEVICE_HANDLE

        manager.checkBLEConnectionHealth()

        assertEquals(ConnectRetryPolicy.DEFAULT_MAX_ATTEMPTS, link.connectCount)
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
        const val DEVICE_HANDLE = "AA:BB:CC:DD:EE:6F"
        const val NOW_SECONDS = 1_700_000_000.0
        val FAST_BACKOFF = 1.milliseconds
    }
}

private class BleHealthProbeError : Error("probe")
