package one.meshcore.shared.connection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.persistence.meshDatabase
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * A failed link connect cancels the in-flight session start and rethrows. Cancellation is the same
 * instance. A thrown error stays on that path and still cancels the start.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionOpenLinkFaultTest {
    private lateinit var dbFile: File
    private lateinit var db: MeshDatabase

    @BeforeTest
    fun setUp() {
        dbFile = File.createTempFile("mesh-open-link-fault", ".db").also { it.delete() }
        db = meshDatabase(dbFile.absolutePath)
    }

    @AfterTest
    fun tearDown() {
        db.close()
        dbFile.delete()
    }

    @Test
    fun aCancelledLinkConnectCancelsSessionStart() = runTest(UnconfinedTestDispatcher()) {
        val stopped = CancellationException("stopped")
        val session = parkedSession()
        val link = FakeMeshLink().apply { failConnectWith = stopped }
        val manager = manager(session, link, this)

        val thrown = assertFailsWith<CancellationException> { manager.connect(DEVICE_HANDLE) }

        assertSame(stopped, thrown)
        assertEquals(1, link.connectCount)
        assertTrue(session.startEntered)
        assertFalse(session.started)
        assertEquals(ConnectionState.Disconnected, manager.state.value)
    }

    @Test
    fun aLinkConnectErrorCancelsSessionStartAndRetries() = runTest(UnconfinedTestDispatcher()) {
        val probe = OpenLinkProbeError()
        val session = parkedSession()
        val link = FakeMeshLink().apply { failConnectWith = probe }
        val manager = manager(session, link, this)

        val thrown = assertFailsWith<OpenLinkProbeError> { manager.connect(DEVICE_HANDLE) }

        assertSame(probe, thrown)
        assertEquals(ConnectRetryPolicy.DEFAULT_MAX_ATTEMPTS, link.connectCount)
        assertTrue(session.startEntered)
        assertFalse(session.started)
        assertEquals(ConnectionState.Disconnected, manager.state.value)
    }

    private fun parkedSession(): FakeMeshSession {
        val gate = CompletableDeferred<Unit>()
        return FakeMeshSession().apply { startGate = gate }
    }

    private fun manager(session: FakeMeshSession, link: FakeMeshLink, scope: CoroutineScope) = ConnectionManager(
        database = db,
        scope = scope,
        linkFactory = { link },
        sessionFactory = { session },
        nowEpochSeconds = { NOW_SECONDS },
        ports = ConnectionManagerPorts(
            connectRetryPolicy = ConnectRetryPolicy(baseBackoff = FAST_BACKOFF),
        ),
    )

    private companion object {
        const val DEVICE_HANDLE = "AA:BB:CC:DD:EE:6D"
        const val NOW_SECONDS = 1_700_000_000.0
        val FAST_BACKOFF = 1.milliseconds
    }
}

private class OpenLinkProbeError : Error("probe")
