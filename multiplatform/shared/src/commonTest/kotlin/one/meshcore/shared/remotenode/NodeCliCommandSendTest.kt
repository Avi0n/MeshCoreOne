package one.meshcore.shared.remotenode

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration

class NodeCliCommandSendTest {
    private val messages = NodeCliMessages(
        bannerConnected = { name -> "Connected to $name" },
        bannerHint = "Type help",
        cancelled = "cancelled",
        rebootSent = "reboot sent",
        promptSuffix = ">",
        help = listOf("help line"),
    )

    @Test
    fun aCommandPrintsTheNodeResponse() = runTest {
        val (state, runner) = runner { _, _ -> "pong" }

        runner.executeCommand("status")

        assertEquals("pong", state.outputLines.last { it.type == CLIOutputType.RESPONSE }.text)
        assertFalse(state.isWaitingForResponse)
    }

    @Test
    fun aCommandFailurePrintsTheErrorAndClearsTheWait() = runTest {
        val (state, runner) = runner { _, _ -> error("link down") }

        runner.executeCommand("status")

        assertEquals("link down", state.outputLines.last { it.type == CLIOutputType.ERROR }.text)
        assertFalse(state.isWaitingForResponse)
    }

    @Test
    fun anErrorPrintsTheErrorAndClearsTheWait() = runTest {
        val fault = CliCommandProbeFault()
        val (state, runner) = runner { _, _ -> throw fault }

        runner.executeCommand("status")

        assertEquals("probe", state.outputLines.last { it.type == CLIOutputType.ERROR }.text)
        assertFalse(state.isWaitingForResponse)
    }

    @Test
    fun aCancelledCommandRethrowsAndClearsTheWait() = runTest {
        val cancelled = CancellationException("superseded")
        val (state, runner) = runner { _, _ -> throw cancelled }

        val thrown = assertFailsWith<CancellationException> { runner.executeCommand("status") }

        assertSame(cancelled, thrown)
        assertFalse(state.isWaitingForResponse)
        assertTrue(state.outputLines.none { it.type == CLIOutputType.ERROR })
    }

    private fun runner(send: suspend (String, Duration) -> String): Pair<NodeCliState, NodeCliCommandRunner> {
        val state = NodeCliState()
        val output = NodeCliTranscript(state, "r1", { messages })
        return state to NodeCliCommandRunner(state, "r1", { messages }, output, send)
    }
}

private class CliCommandProbeFault : Error("probe")
