package one.meshcore.shared.connection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import kotlin.time.Duration.Companion.seconds

/**
 * A cancelled Wi-Fi heartbeat is rethrown and leaves the live link up. Any other failure, including
 * a thrown error, drops the link so reconnect can start. A failure that lands during sync is
 * inconclusive and does not drop the link.
 */
class ConnectionWifiHeartbeatFaultTest {
    private lateinit var dbFile: File
    private lateinit var db: MeshDatabase
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        dbFile = File.createTempFile("mesh-wifi-heartbeat-fault", ".db").also { it.delete() }
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
    fun aCancelledHeartbeatIsRethrownAndLeavesTheLinkUp() = runBlocking {
        val stopped = CancellationException("stopped")
        val radio = connectRadio()
        radio.session.getTimeFailures += stopped

        val thrown = assertFailsWith<CancellationException> { radio.links.single().heartbeat() }

        assertSame(stopped, thrown)
        delay(SETTLE)
        assertEquals(1, radio.links.size)
        assertEquals(ConnectionState.Ready(HANDLE), radio.manager.state.value)
    }

    @Test
    fun aHeartbeatErrorDropsTheLinkAndReconnects() = runBlocking {
        val radio = connectRadio()
        radio.session.getTimeFailures += WifiHeartbeatProbeError()

        radio.links.single().heartbeat()

        awaitCondition { radio.links.size == 2 && radio.manager.state.value == ConnectionState.Ready(HANDLE) }
    }

    @Test
    fun aHeartbeatErrorDuringSyncDoesNotDropTheLink() = runBlocking {
        val radio = connectRadio()
        val gate = CompletableDeferred<Unit>()
        val probed = radio.session.getTimeCount
        radio.session.getTimeGate = gate
        radio.session.getTimeFailures += WifiHeartbeatProbeError()
        val probe = scope.launch { radio.links.single().heartbeat() }
        awaitCondition { radio.session.getTimeCount > probed }

        radio.manager.mutableState.value = ConnectionState.Syncing(HANDLE)
        gate.complete(Unit)
        probe.join()

        assertEquals(1, radio.links.size)
        assertEquals(ConnectionState.Syncing(HANDLE), radio.manager.state.value)
    }

    private suspend fun connectRadio(): LiveRadio {
        val links = mutableListOf<FakeWiFiLink>()
        val session = FakeMeshSession()
        val manager = ConnectionManager(
            database = db,
            scope = scope,
            linkFactory = { FakeMeshLink() },
            sessionFactory = { session },
            nowEpochSeconds = { NOW_SECONDS },
            ports = ConnectionManagerPorts(
                wifiPolicy = WiFiReconnectPolicy(
                    cooldown = 0.seconds,
                    window = 10.seconds,
                    baseBackoff = 20.milliseconds,
                    maxBackoff = 100.milliseconds,
                ),
                wifiLinkFactory = { host, port, heartbeat ->
                    FakeWiFiLink(host, port, heartbeat).also { links += it }
                },
                connectRetryPolicy = ConnectRetryPolicy(baseBackoff = 1.milliseconds),
            ),
        )
        manager.connectViaWiFi(HOST, PORT)
        assertEquals(ConnectionState.Ready(HANDLE), manager.state.value)
        return LiveRadio(manager, session, links)
    }

    private suspend fun awaitCondition(condition: suspend () -> Boolean) {
        withTimeout(TIMEOUT) {
            while (!condition()) delay(POLL)
        }
    }

    private data class LiveRadio(
        val manager: ConnectionManager,
        val session: FakeMeshSession,
        val links: List<FakeWiFiLink>,
    )

    private companion object {
        const val HOST = "10.0.0.9"
        const val PORT = 4404
        const val HANDLE = "$HOST:$PORT"
        const val NOW_SECONDS = 1_700_000_000.0
        const val TIMEOUT = 5_000L
        const val POLL = 10L
        val SETTLE = 100.milliseconds
    }
}

private class WifiHeartbeatProbeError : Error("probe")
