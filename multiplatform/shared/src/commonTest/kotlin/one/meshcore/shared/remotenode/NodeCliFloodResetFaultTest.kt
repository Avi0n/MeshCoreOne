package one.meshcore.shared.remotenode

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.DebugLogLevel
import one.meshcore.shared.settings.PersistentLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A failed flood path reset is logged and the original timeout is rethrown. Cancellation keeps the
 * thrown instance, skips the failure log, and does not retry. An error is logged the same way and
 * does not escape.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NodeCliFloodResetFaultTest {
    @Test
    fun aCancelledFloodResetKeepsTheThrownInstanceAndDoesNotRetry() = runTest(UnconfinedTestDispatcher()) {
        val stopped = CancellationException("stopped")
        val harness = harness(stopped)
        var calls = 0

        val thrown = assertFailsWith<CancellationException> {
            harness.cli.performWithDirectPathFloodRecovery(harness.radioID, harness.publicKey) {
                calls += 1
                throw RemoteNodeError.Timeout
            }
        }

        assertSame(stopped, thrown)
        assertEquals(1, calls)
        assertEquals(1, harness.radio.resetPathCalls.size)
        assertTrue(harness.radio.resetPathCalls.single().contentEquals(harness.publicKey))
        assertTrue(harness.logs.none { it.message.contains(FLOOD_RESET_FAILED) })
    }

    @Test
    fun aFloodResetFailureIsLoggedAndRethrowsTheOriginalTimeout() = runTest(UnconfinedTestDispatcher()) {
        val harness = harness(FloodResetFailure())
        var calls = 0

        val thrown = assertFailsWith<RemoteNodeError.Timeout> {
            harness.cli.performWithDirectPathFloodRecovery(harness.radioID, harness.publicKey) {
                calls += 1
                throw RemoteNodeError.Timeout
            }
        }

        assertSame(RemoteNodeError.Timeout, thrown)
        assertEquals(1, calls)
        assertLogged(harness, "radio")
    }

    @Test
    fun aFloodResetErrorIsLoggedAndDoesNotEscape() = runTest(UnconfinedTestDispatcher()) {
        val harness = harness(FloodResetProbeError())
        var calls = 0

        val thrown = assertFailsWith<RemoteNodeError.Timeout> {
            harness.cli.performWithDirectPathFloodRecovery(harness.radioID, harness.publicKey) {
                calls += 1
                throw RemoteNodeError.Timeout
            }
        }

        assertSame(RemoteNodeError.Timeout, thrown)
        assertEquals(1, calls)
        assertLogged(harness, "probe")
    }

    private fun assertLogged(harness: FloodResetHarness, detail: String) {
        val line = harness.logs.single { it.message.startsWith(FLOOD_RESET_FAILED) }
        assertEquals("$FLOOD_RESET_FAILED ($detail); not retrying", line.message)
        assertEquals(DebugLogLevel.WARNING, line.level)
        assertEquals("RemoteNode", line.category)
        assertEquals(1, harness.radio.resetPathCalls.size)
    }

    private fun TestScope.harness(failure: Throwable): FloodResetHarness {
        val logs = mutableListOf<DebugLogEntryDto>()
        val radio = FakeNodeSession().apply { resetPathError = failure }
        val session = testSession()
        val cli = NodeCliCoordinator(
            radio,
            InMemoryRemoteNodeStore(),
            InMemoryNodePasswordStore(),
            backgroundScope,
            logger = PersistentLogger(this, category = "RemoteNode", record = { logs += it }),
        )
        return FloodResetHarness(cli, radio, logs, session.radioID, session.publicKey)
    }
}

private class FloodResetHarness(
    val cli: NodeCliCoordinator,
    val radio: FakeNodeSession,
    val logs: List<DebugLogEntryDto>,
    val radioID: Uuid,
    val publicKey: ByteArray,
)

private const val FLOOD_RESET_FAILED: String = "remoteCLI: path reset failed"

private class FloodResetFailure : Exception("radio")

private class FloodResetProbeError : Error("probe")
