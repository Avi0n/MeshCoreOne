package one.meshcore.shared.connection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
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

/**
 * A cancelled handshake cancels an in-flight session start, drops a dead link, and keeps the
 * caller's cancellation as the cause. A failure on a connected Bluetooth link asks for a session
 * rebuild. A failure on a dead link is rethrown.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionHandshakeFaultTest {
    private lateinit var dbFile: File
    private lateinit var db: MeshDatabase

    @BeforeTest
    fun setUp() {
        dbFile = File.createTempFile("mesh-handshake-fault", ".db").also { it.delete() }
        db = meshDatabase(dbFile.absolutePath)
    }

    @AfterTest
    fun tearDown() {
        db.close()
        dbFile.delete()
    }

    @Test
    fun aCancelledHandshakeDropsADeadLinkAndRethrows() = runTest(UnconfinedTestDispatcher()) {
        val stopped = CancellationException("stopped")
        val session = FakeMeshSession().apply { failQueryWith = stopped }
        val link = FakeMeshLink().apply { silentlyDead = true }
        val manager = manager(session, link, this)
        manager.currentTransportType = TransportType.BLUETOOTH

        val thrown = assertFailsWith<CancellationException> {
            manager.handshakeOverExistingLink(DEVICE_HANDLE, link, manager.connectGeneration)
        }

        assertSame(stopped, thrown.cause)
        assertEquals(stopped.message, thrown.message)
        assertTrue(session.started)
        assertTrue(link.disconnected)
    }

    @Test
    fun aCancelledHandshakeCancelsTheInFlightStart() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        val session = FakeMeshSession().apply { startGate = gate }
        val link = FakeMeshLink()
        val manager = manager(session, link, this)
        manager.currentTransportType = TransportType.BLUETOOTH
        val handshake = async { manager.handshakeOverExistingLink(DEVICE_HANDLE, link, manager.connectGeneration) }
        assertTrue(session.startEntered)

        handshake.cancel(CancellationException("stopped"))
        assertFailsWith<CancellationException> { handshake.await() }
        gate.complete(Unit)
        yield()

        assertFalse(session.started)
        assertFalse(link.disconnected)
    }

    @Test
    fun aConnectedBleHandshakeErrorAsksForASessionRebuild() = runTest(UnconfinedTestDispatcher()) {
        val probe = HandshakeProbeError()
        val session = FakeMeshSession().apply { failQueryWith = probe }
        val link = FakeMeshLink()
        val manager = manager(session, link, this)
        manager.currentTransportType = TransportType.BLUETOOTH

        val thrown = assertFailsWith<SessionRebuildException> {
            manager.handshakeOverExistingLink(DEVICE_HANDLE, link, manager.connectGeneration)
        }

        assertSame(probe, thrown.cause)
        assertTrue(session.started)
        assertFalse(link.disconnected)
    }

    @Test
    fun aHandshakeErrorOnADeadLinkRethrows() = runTest(UnconfinedTestDispatcher()) {
        val probe = HandshakeProbeError()
        val session = FakeMeshSession().apply { failQueryWith = probe }
        val link = FakeMeshLink().apply { silentlyDead = true }
        val manager = manager(session, link, this)
        manager.currentTransportType = TransportType.BLUETOOTH

        val thrown = assertFailsWith<HandshakeProbeError> {
            manager.handshakeOverExistingLink(DEVICE_HANDLE, link, manager.connectGeneration)
        }

        assertSame(probe, thrown)
        assertTrue(session.started)
        assertFalse(link.disconnected)
    }

    private fun manager(session: FakeMeshSession, link: FakeMeshLink, scope: CoroutineScope) = ConnectionManager(
        database = db,
        scope = scope,
        linkFactory = { link },
        sessionFactory = { session },
        nowEpochSeconds = { NOW_SECONDS },
    ).also { it.link = link }

    private companion object {
        const val DEVICE_HANDLE = "AA:BB:CC:DD:EE:6E"
        const val NOW_SECONDS = 1_700_000_000.0
    }
}

private class HandshakeProbeError : Error("probe")
