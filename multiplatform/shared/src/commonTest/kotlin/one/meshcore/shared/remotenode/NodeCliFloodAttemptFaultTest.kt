package one.meshcore.shared.remotenode

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.PersistentLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A failed first CLI attempt is passed to recovery. Cancellation keeps the thrown instance and does
 * not reset the path. An ordinary failure and a thrown error are rethrown unchanged.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NodeCliFloodAttemptFaultTest {
    @Test
    fun aCancelledFirstAttemptKeepsTheThrownInstanceAndDoesNotReset() = runTest(UnconfinedTestDispatcher()) {
        val stopped = CancellationException("stopped")
        val harness = harness()
        var calls = 0

        val thrown = assertFailsWith<CancellationException> {
            harness.cli.performWithDirectPathFloodRecovery(harness.radioID, harness.publicKey) {
                calls += 1
                throw stopped
            }
        }

        assertSame(stopped, thrown)
        assertDidNotRecover(harness, calls)
    }

    @Test
    fun anOrdinaryFirstAttemptFailureIsRethrownUnchanged() = runTest(UnconfinedTestDispatcher()) {
        val failure = FloodAttemptFailure()
        val harness = harness()
        var calls = 0

        val thrown = assertFailsWith<FloodAttemptFailure> {
            harness.cli.performWithDirectPathFloodRecovery(harness.radioID, harness.publicKey) {
                calls += 1
                throw failure
            }
        }

        assertSame(failure, thrown)
        assertDidNotRecover(harness, calls)
    }

    @Test
    fun aFirstAttemptErrorIsRethrownAndDoesNotReset() = runTest(UnconfinedTestDispatcher()) {
        val probe = FloodAttemptProbeError()
        val harness = harness()
        var calls = 0

        val thrown = assertFailsWith<FloodAttemptProbeError> {
            harness.cli.performWithDirectPathFloodRecovery(harness.radioID, harness.publicKey) {
                calls += 1
                throw probe
            }
        }

        assertSame(probe, thrown)
        assertDidNotRecover(harness, calls)
    }

    private fun assertDidNotRecover(harness: FloodAttemptHarness, calls: Int) {
        assertEquals(1, calls)
        assertTrue(harness.radio.resetPathCalls.isEmpty())
        assertTrue(harness.logs.none { it.message.contains(FLOOD_RECOVERY_STARTED) })
    }

    private fun TestScope.harness(): FloodAttemptHarness {
        val logs = mutableListOf<DebugLogEntryDto>()
        val radio = FakeNodeSession()
        val session = testSession()
        val cli = NodeCliCoordinator(
            radio,
            InMemoryRemoteNodeStore(),
            InMemoryNodePasswordStore(),
            backgroundScope,
            logger = PersistentLogger(this, category = "RemoteNode", record = { logs += it }),
        )
        return FloodAttemptHarness(cli, radio, logs, session.radioID, session.publicKey)
    }
}

private class FloodAttemptHarness(
    val cli: NodeCliCoordinator,
    val radio: FakeNodeSession,
    val logs: List<DebugLogEntryDto>,
    val radioID: Uuid,
    val publicKey: ByteArray,
)

private const val FLOOD_RECOVERY_STARTED: String = "remoteCLI: direct-path timeout"

private class FloodAttemptFailure : Exception("radio")

private class FloodAttemptProbeError : Error("probe")
