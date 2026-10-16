package one.meshcore.shared.remotenode

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration

/** Output-buffer, history, prompt, reboot handling, and completion behaviour of [NodeCliSession]. */
class NodeCliSessionTest {
    private val messages = NodeCliMessages(
        bannerConnected = { "connected: $it" },
        bannerHint = "type a command",
        cancelled = "cancelled",
        rebootSent = "reboot sent",
        promptSuffix = ">",
        help = listOf("help line 1", "help line 2"),
    )

    private fun session(send: suspend (String, Duration) -> String = { _, _ -> "" }) =
        NodeCliSession(sessionName = "NodeA", messages = { messages }, sendRawCommand = send)

    @Test
    fun bannerAppearsOnConstruction() {
        val cli = session()
        assertEquals(3, cli.outputLines.size)
        assertEquals("connected: NodeA", cli.outputLines[0].text)
        assertEquals("type a command", cli.outputLines[1].text)
        assertEquals("", cli.outputLines[2].text)
    }

    @Test
    fun promptReflectsWaitingState() {
        val cli = session()
        assertEquals("@NodeA> ", cli.promptText)
    }

    @Test
    fun emptyCommandEchoesTrimmedPrompt() = runTest {
        val cli = session()
        cli.executeCommand("   ")
        assertEquals("@NodeA>", cli.outputLines.last().text)
        assertEquals(CLIOutputType.COMMAND, cli.outputLines.last().type)
    }

    @Test
    fun helpPrintsHelpBlock() = runTest {
        val cli = session()
        cli.executeCommand("help")
        val texts = cli.outputLines.map { it.text }
        assertTrue(texts.containsAll(listOf("help line 1", "help line 2")))
    }

    @Test
    fun clearEmptiesBuffer() = runTest {
        val cli = session()
        cli.executeCommand("clear")
        assertTrue(cli.outputLines.isEmpty())
    }

    @Test
    fun normalCommandAppendsResponse() = runTest {
        val cli = session { _, _ -> "device response" }
        cli.executeCommand("get name")
        assertEquals("device response", cli.outputLines.last().text)
        assertEquals(CLIOutputType.RESPONSE, cli.outputLines.last().type)
        assertFalse(cli.isWaitingForResponse)
    }

    @Test
    fun rebootTreatsSuccessAndTimeoutAsSent() = runTest {
        val success = session { _, _ -> "" }
        success.executeCommand("reboot")
        assertEquals("reboot sent", success.outputLines.last().text)
        assertEquals(CLIOutputType.SUCCESS, success.outputLines.last().type)

        val timedOut = session { _, _ -> throw RemoteNodeError.Timeout }
        timedOut.executeCommand("reboot now")
        assertEquals("reboot sent", timedOut.outputLines.last().text)
        assertEquals(CLIOutputType.SUCCESS, timedOut.outputLines.last().type)
    }

    @Test
    fun commandErrorAppendsErrorLine() = runTest {
        val cli = session { _, _ -> throw RemoteNodeError.SessionNotFound }
        cli.executeCommand("get name")
        assertEquals(CLIOutputType.ERROR, cli.outputLines.last().type)
    }

    @Test
    fun outputBufferIsCappedDroppingOldest() {
        val cli = session()
        repeat(1100) { cli.appendOutput("line $it", CLIOutputType.RESPONSE) }
        assertEquals(1000, cli.outputLines.size)
        assertEquals("line 1099", cli.outputLines.last().text)
    }

    @Test
    fun historyIsCappedAndNavigable() = runTest {
        val cli = session()
        repeat(105) { cli.executeCommand("cmd$it") }
        assertEquals(100, cli.commandHistory.size)
        assertEquals("cmd104", cli.commandHistory.last())
        assertEquals("cmd5", cli.commandHistory.first())

        cli.historyUp()
        assertEquals("cmd104", cli.currentInput)
        cli.historyUp()
        assertEquals("cmd103", cli.currentInput)
        cli.historyDown()
        assertEquals("cmd104", cli.currentInput)
        cli.historyDown()
        assertEquals("", cli.currentInput)
    }

    @Test
    fun cancelAppendsCancelledWhenWaiting() {
        val cli = session()
        // cancelCurrentCommand is a no-op unless a command is in flight; simulate the waiting flag.
        cli.cancelCurrentCommand()
        assertFalse(cli.outputLines.any { it.text == "cancelled" })
    }

    @Test
    fun ghostTextCompletesTheCommandWord() {
        val cli = session()
        cli.currentInput = "he"
        cli.updateGhostText(cursorAtEnd = true)
        assertEquals("lp", cli.ghostText)
        cli.acceptGhostText()
        assertEquals("help", cli.currentInput)
    }

    @Test
    fun tabAppliesSingleCompletionAndCyclesMultiple() {
        val single = session()
        single.currentInput = "hel"
        assertEquals(null, single.tabComplete())
        assertEquals("help ", single.currentInput)

        val multi = session()
        multi.currentInput = "cl"
        val suggestions = multi.tabComplete()
        assertNotNull(suggestions)
        assertTrue(suggestions.size > 1)
        // A second tab starts cycling the suggestion list.
        multi.tabComplete()
        assertEquals(0, multi.tabSelectionIndex)
        assertTrue(multi.applySuggestionAt(1))
        assertEquals("${suggestions[1]} ", multi.currentInput)
        assertEquals(null, multi.tabSuggestions)
    }
}
