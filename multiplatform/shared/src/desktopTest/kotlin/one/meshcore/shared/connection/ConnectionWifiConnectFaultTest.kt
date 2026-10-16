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
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * A cancelled WiFi connect is abandoned and rethrown. Any other failure, including a thrown error,
 * drops the WiFi endpoint and is rethrown.
 */
class ConnectionWifiConnectFaultTest {
    private lateinit var dbFile: File
    private lateinit var db: MeshDatabase
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        dbFile = File.createTempFile("mesh-wifi-connect-fault", ".db").also { it.delete() }
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
    fun aCancelledWifiConnectIsAbandonedAndRethrown() = runBlocking {
        val stopped = CancellationException("stopped")
        val link = FakeWiFiLink(HOST, PORT) {}
        link.failConnectWith = stopped
        val manager = manager(link)

        val thrown = assertFailsWith<CancellationException> { manager.connectViaWiFi(HOST, PORT) }

        assertSame(stopped, thrown)
        assertEquals(1, link.connectCount)
        assertEquals(ConnectionState.Disconnected, manager.state.value)
        assertEquals(HOST, manager.wifiHost)
        assertEquals(PORT, manager.wifiPort)
    }

    @Test
    fun aWifiConnectErrorDropsTheEndpointAndRethrows() = runBlocking {
        val probe = WifiConnectProbeError()
        val link = FakeWiFiLink(HOST, PORT) {}
        link.failConnectWith = probe
        val manager = manager(link)

        val thrown = assertFailsWith<WifiConnectProbeError> { manager.connectViaWiFi(HOST, PORT) }

        assertSame(probe, thrown)
        assertEquals(1, link.connectCount)
        assertEquals(ConnectionState.Disconnected, manager.state.value)
        assertNull(manager.wifiHost)
        assertNull(manager.wifiPort)
    }

    private fun manager(link: FakeWiFiLink) = ConnectionManager(
        database = db,
        scope = scope,
        linkFactory = { FakeMeshLink() },
        sessionFactory = { FakeMeshSession() },
        nowEpochSeconds = { NOW_SECONDS },
        ports = ConnectionManagerPorts(
            wifiLinkFactory = { _, _, _ -> link },
        ),
    )

    private companion object {
        const val HOST = "10.0.0.8"
        const val PORT = 4403
        const val NOW_SECONDS = 1_700_000_000.0
    }
}

private class WifiConnectProbeError : Error("probe")
