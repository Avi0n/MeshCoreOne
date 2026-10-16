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
 * A failed flood retry keeps its failure. A mesh timeout is logged first. Cancellation keeps the
 * thrown instance and does not log. An error is rethrown unchanged and is not logged as a timeout.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NodeCliFloodRetryFaultTest {
    @Test
    fun aCancelledFloodRetryKeepsTheThrownInstance() = runTest(UnconfinedTestDispatcher()) {
        val stopped = CancellationException("stopped")
        val harness = harness()
        var calls = 0

        val thrown = assertFailsWith<CancellationException> {
            harness.cli.performWithDirectPathFloodRecovery(harness.radioID, harness.publicKey) {
                calls += 1
                if (calls == 1) throw RemoteNodeError.Timeout
                throw stopped
            }
        }

        assertSame(stopped, thrown)
        assertRetriedOnce(harness, calls)
        assertTrue(harness.logs.none { it.message.contains(FLOOD_RETRY_TIMED_OUT) })
    }

    @Test
    fun aFloodRetryFailureIsRethrownUnchanged() = runTest(UnconfinedTestDispatcher()) {
        val failure = FloodRetryFailure()
        val harness = harness()
        var calls = 0

        val thrown = assertFailsWith<FloodRetryFailure> {
            harness.cli.performWithDirectPathFloodRecovery(harness.radioID, harness.publicKey) {
                calls += 1
                if (calls == 1) throw RemoteNodeError.Timeout
                throw failure
            }
        }

        assertSame(failure, thrown)
        assertRetriedOnce(harness, calls)
        assertTrue(harness.logs.none { it.message.contains(FLOOD_RETRY_TIMED_OUT) })
    }

    @Test
    fun aFloodRetryTimeoutIsLoggedAndRethrown() = runTest(UnconfinedTestDispatcher()) {
        val harness = harness()
        var calls = 0

        val thrown = assertFailsWith<RemoteNodeError.Timeout> {
            harness.cli.performWithDirectPathFloodRecovery(harness.radioID, harness.publicKey) {
                calls += 1
                throw RemoteNodeError.Timeout
            }
        }

        assertSame(RemoteNodeError.Timeout, thrown)
        assertRetriedOnce(harness, calls)
        val line = harness.logs.single { it.message == FLOOD_RETRY_TIMED_OUT }
        assertEquals(DebugLogLevel.WARNING, line.level)
        assertEquals("RemoteNode", line.category)
    }

    @Test
    fun aFloodRetryErrorIsRethrownAndIsNotLoggedAsATimeout() = runTest(UnconfinedTestDispatcher()) {
        val probe = FloodRetryProbeError()
        val harness = harness()
        var calls = 0

        val thrown = assertFailsWith<FloodRetryProbeError> {
            harness.cli.performWithDirectPathFloodRecovery(harness.radioID, harness.publicKey) {
                calls += 1
                if (calls == 1) throw RemoteNodeError.Timeout
                throw probe
            }
        }

        assertSame(probe, thrown)
        assertRetriedOnce(harness, calls)
        assertTrue(harness.logs.none { it.message.contains(FLOOD_RETRY_TIMED_OUT) })
    }

    private fun assertRetriedOnce(harness: FloodRetryHarness, calls: Int) {
        assertEquals(2, calls)
        assertEquals(1, harness.radio.resetPathCalls.size)
        assertTrue(harness.radio.resetPathCalls.single().contentEquals(harness.publicKey))
    }

    private fun TestScope.harness(): FloodRetryHarness {
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
        return FloodRetryHarness(cli, radio, logs, session.radioID, session.publicKey)
    }
}

private class FloodRetryHarness(
    val cli: NodeCliCoordinator,
    val radio: FakeNodeSession,
    val logs: List<DebugLogEntryDto>,
    val radioID: Uuid,
    val publicKey: ByteArray,
)

private const val FLOOD_RETRY_TIMED_OUT: String = "remoteCLI: flood retry also timed out"

private class FloodRetryFailure : Exception("radio")

private class FloodRetryProbeError : Error("probe")
