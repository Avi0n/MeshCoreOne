package one.meshcore.shared.connection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.persistence.meshDatabase
import one.meshcore.shared.transport.MeshLinkConnectException
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
 * A handshake step that hangs becomes a connect exception naming that step. A caller cancellation
 * is rethrown. Any other failure, including a thrown error, is rethrown unchanged.
 */
class ConnectionHandshakeStepFaultTest {
    private lateinit var dbFile: File
    private lateinit var db: MeshDatabase

    @BeforeTest
    fun setUp() {
        dbFile = File.createTempFile("mesh-handshake-step-fault", ".db").also { it.delete() }
        db = meshDatabase(dbFile.absolutePath)
    }

    @AfterTest
    fun tearDown() {
        db.close()
        dbFile.delete()
    }

    @Test
    fun aHangingHandshakeStepBecomesAConnectException() = runBlocking {
        val manager = manager(this)

        val thrown = assertFailsWith<MeshLinkConnectException> {
            manager.awaitHandshakeStep(OPERATION) { suspendCancellableCoroutine<Unit> { } }
        }

        assertEquals("$OPERATION timed out", thrown.message)
    }

    @Test
    fun aCancelledHandshakeStepRethrows() = runBlocking {
        val stopped = CancellationException("stopped")
        val manager = manager(this)

        val thrown = assertFailsWith<CancellationException> {
            manager.awaitHandshakeStep(OPERATION) { throw stopped }
        }

        assertTrue(thrown === stopped || thrown.cause === stopped)
        assertEquals(stopped.message, thrown.message)
    }

    @Test
    fun callersDeadlineDoesNotBecomeAConnectError() = runTest {
        val manager = manager(this)
        var mappedTimeout = false

        withTimeoutOrNull(10.milliseconds) {
            try {
                manager.awaitHandshakeStep(OPERATION) { suspendCancellableCoroutine<Unit> { } }
            } catch (_: MeshLinkConnectException) {
                mappedTimeout = true
            }
        }

        assertTrue(!mappedTimeout)
    }

    @Test
    fun aHandshakeStepErrorIsRethrown() = runBlocking {
        val probe = HandshakeStepProbeError()
        val manager = manager(this)

        val thrown = assertFailsWith<HandshakeStepProbeError> {
            manager.awaitHandshakeStep(OPERATION) { throw probe }
        }

        assertSame(probe, thrown)
    }

    private fun manager(scope: CoroutineScope) = ConnectionManager(
        database = db,
        scope = scope,
        linkFactory = { FakeMeshLink() },
        sessionFactory = { FakeMeshSession() },
        nowEpochSeconds = { NOW_SECONDS },
        ports = ConnectionManagerPorts(handshakeTimeout = STEP_TIMEOUT),
    )

    private companion object {
        const val OPERATION = "queryDevice"
        const val NOW_SECONDS = 1_700_000_000.0
        val STEP_TIMEOUT = 50.milliseconds
    }
}

private class HandshakeStepProbeError : Error("probe")
