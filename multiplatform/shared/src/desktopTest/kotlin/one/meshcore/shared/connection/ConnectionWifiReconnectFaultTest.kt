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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A cancelled Wi-Fi reconnect attempt is rethrown and leaves the new link up. Any other failure,
 * including a thrown error, is logged, tears that link down, and lets the reconnect window end.
 */
class ConnectionWifiReconnectFaultTest {
    private lateinit var dbFile: File
    private lateinit var db: MeshDatabase
    private lateinit var scope: CoroutineScope
    private var nowSeconds = NOW_SECONDS

    @BeforeTest
    fun setUp() {
        dbFile = File.createTempFile("mesh-wifi-reconnect-fault", ".db").also { it.delete() }
        db = meshDatabase(dbFile.absolutePath)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        nowSeconds = NOW_SECONDS
    }

    @AfterTest
    fun tearDown() {
        scope.cancelAndJoinBlocking()
        db.close()
        dbFile.delete()
    }

    @Test
    fun aCancelledWifiReconnectLeavesTheNewLinkUp() = runBlocking {
        val stopped = CancellationException("stopped")
        lateinit var link: FakeWiFiLink
        val manager = manager(stopped) { created -> link = created }

        manager.startWiFiReconnect(HOST, PORT, HANDLE)
        val job = checkNotNull(manager.reconnectJob)
        var cause: Throwable? = null
        job.invokeOnCompletion { cause = it }
        job.join()

        assertTrue(cause === stopped || cause?.cause === stopped)
        assertTrue(job.isCancelled)
        assertEquals(1, link.connectCount)
        assertSame(link, manager.link)
    }

    @Test
    fun aWifiReconnectErrorTearsDownAndEndsTheWindow() = runBlocking {
        lateinit var link: FakeWiFiLink
        val manager = manager(WifiReconnectProbeError()) { created -> link = created }

        manager.startWiFiReconnect(HOST, PORT, HANDLE)
        val job = checkNotNull(manager.reconnectJob)
        var cause: Throwable? = null
        job.invokeOnCompletion { cause = it }
        job.join()

        assertNull(cause)
        assertFalse(job.isCancelled)
        assertEquals(1, link.connectCount)
        assertNull(manager.link)
        assertEquals(ConnectionState.Disconnected, manager.state.value)
    }

    private fun manager(failure: Throwable, onLink: (FakeWiFiLink) -> Unit) = ConnectionManager(
        database = db,
        scope = scope,
        linkFactory = { FakeMeshLink() },
        sessionFactory = { FakeMeshSession() },
        nowEpochSeconds = { nowSeconds },
        ports = ConnectionManagerPorts(
            wifiPolicy = WiFiReconnectPolicy(
                window = WINDOW,
                baseBackoff = FAST_BACKOFF,
                maxBackoff = FAST_BACKOFF,
            ),
            wifiLinkFactory = { host, port, heartbeat ->
                nowSeconds += WINDOW_JUMP_SECONDS
                FakeWiFiLink(host, port, heartbeat).apply {
                    failConnectWith = failure
                    onLink(this)
                }
            },
        ),
    ).apply {
        wantsConnection = true
        currentDeviceId = HANDLE
    }

    private companion object {
        const val HOST = "10.0.0.10"
        const val PORT = 4403
        const val HANDLE = "10.0.0.10:4403"
        const val NOW_SECONDS = 1_700_000_000.0
        const val WINDOW_JUMP_SECONDS = 6.0
        val WINDOW = 5.seconds
        val FAST_BACKOFF = 1.milliseconds
    }
}

private class WifiReconnectProbeError : Error("probe")
