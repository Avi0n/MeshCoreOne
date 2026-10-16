package one.meshcore.shared.remotenode.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.remotenode.CLIOutputType
import one.meshcore.shared.remotenode.NodeCliMessages
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * View-model adaptation over [one.meshcore.shared.remotenode.NodeCliSession]: input publishing, clear,
 * and the not-connected error path when the admin service provider returns null.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NodeCliViewModelTest {
    private val messages = NodeCliMessages(
        bannerConnected = { name -> "Connected to $name" },
        bannerHint = "Type help",
        cancelled = "cancelled",
        rebootSent = "reboot sent",
        promptSuffix = ">",
        help = listOf("help line"),
    )

    @Test
    fun refreshingLocalizedCopyKeepsStateAndUsesTheLatestMessages() = runTest {
        var currentMessages = messages
        val vm = NodeCliViewModel(
            scope = backgroundScope,
            sessionName = "r1",
            messages = { currentMessages },
            dependencies = NodeCliViewModel.Dependencies(
                repeaterAdminService = { null },
                sessionId = { null },
            ),
        )
        vm.setInput("draft")
        val originalLines = vm.state.value.outputLines

        currentMessages = messages.copy(promptSuffix = "#", help = listOf("localized help"))
        vm.refreshLocalizedCopy()

        assertEquals("draft", vm.state.value.currentInput)
        assertEquals(originalLines, vm.state.value.outputLines)
        assertEquals("@r1# ", vm.state.value.promptText)

        vm.setInput("help")
        vm.submit()
        runCurrent()
        assertEquals("localized help", vm.state.value.outputLines.last().text)
    }

    /** A repeater CLI with no admin service and no session, so an edit can prove it did not read them. */
    private fun TestScope.quietRepeaterCli(): QuietRepeaterCli {
        val reads = QuietRepeaterCliReads()
        val vm = NodeCliViewModel(
            scope = backgroundScope,
            sessionName = "r1",
            messages = { messages },
            dependencies = NodeCliViewModel.Dependencies(
                repeaterAdminService = {
                    reads.serviceReads += 1
                    null
                },
                sessionId = {
                    reads.sessionReads += 1
                    null
                },
            ),
        )
        return QuietRepeaterCli(vm, reads)
    }

    @Test
    fun keepingAnEmptyRepeaterCliLineDoesNotRadio() = runTest {
        val quiet = quietRepeaterCli()
        val vm = quiet.vm
        assertEquals("", vm.state.value.currentInput)
        assertFalse(vm.state.value.isWaitingForResponse)
        assertTrue(vm.state.value.outputLines.isNotEmpty())
        assertEquals(0, quiet.reads.serviceReads)
        assertEquals(0, quiet.reads.sessionReads)

        vm.setInput("")
        runCurrent()

        assertEquals(0, quiet.reads.serviceReads)
        assertEquals(0, quiet.reads.sessionReads)
        assertEquals("", vm.state.value.currentInput)
        assertFalse(vm.state.value.isWaitingForResponse)
        assertTrue(vm.state.value.outputLines.isNotEmpty())
    }

    @Test
    fun setInputPublishesCurrentInput() = runTest {
        val vm = NodeCliViewModel(
            scope = backgroundScope,
            sessionName = "r1",
            messages = { messages },
            dependencies = NodeCliViewModel.Dependencies(
                repeaterAdminService = { null },
                sessionId = { Uuid.normalized("A0000000-0000-0000-0000-000000000001") },
            ),
        )
        vm.setInput("status")
        assertEquals("status", vm.state.value.currentInput)
        assertTrue(vm.state.value.outputLines.isNotEmpty()) // banner
    }

    @Test
    fun clearEmptiesOutputBuffer() = runTest {
        val vm = NodeCliViewModel(
            scope = backgroundScope,
            sessionName = "r1",
            messages = { messages },
            dependencies = NodeCliViewModel.Dependencies(
                repeaterAdminService = { null },
                sessionId = { Uuid.normalized("A0000000-0000-0000-0000-000000000001") },
            ),
        )
        assertTrue(vm.state.value.outputLines.isNotEmpty())
        vm.clear()
        runCurrent()
        assertTrue(vm.state.value.outputLines.isEmpty())
        assertFalse(vm.state.value.isWaitingForResponse)
    }

    @Test
    fun submitWhileDisconnectedAppendsErrorLine() = runTest {
        val vm = NodeCliViewModel(
            scope = backgroundScope,
            sessionName = "r1",
            messages = { messages },
            dependencies = NodeCliViewModel.Dependencies(
                repeaterAdminService = { null },
                sessionId = { null },
            ),
        )
        vm.setInput("ver")
        vm.submit()
        runCurrent()

        val last = vm.state.value.outputLines.last()
        assertEquals(CLIOutputType.ERROR, last.type)
        assertFalse(vm.state.value.isWaitingForResponse)
    }

    @Test
    fun sendRawCommandOverrideRoutesToTheRoomSender() = runTest {
        val sent = mutableListOf<String>()
        val vm = NodeCliViewModel(
            scope = backgroundScope,
            sessionName = "room1",
            messages = { messages },
            dependencies = NodeCliViewModel.Dependencies(
                // The room CLI has no repeater admin service; it sends over the room admin service.
                repeaterAdminService = { null },
                sessionId = { Uuid.normalized("A0000000-0000-0000-0000-000000000001") },
                sendRawCommand = { command, _ ->
                    sent += command
                    "guest.password: none"
                },
            ),
        )

        vm.setInput("get guest.password")
        vm.submit()
        runCurrent()

        assertEquals(listOf("get guest.password"), sent)
        assertTrue(vm.state.value.outputLines.any { it.text.contains("guest.password: none") })
    }

    @Test
    fun submittingABlankRepeaterCliDoesNotRadio() = runTest {
        val quiet = quietRepeaterCli()
        val vm = quiet.vm
        assertEquals("", vm.state.value.currentInput)
        val lines = vm.state.value.outputLines.size

        vm.submit()
        runCurrent()

        assertEquals(0, quiet.reads.serviceReads)
        assertEquals(0, quiet.reads.sessionReads)
        assertEquals("", vm.state.value.currentInput)
        assertFalse(vm.state.value.isWaitingForResponse)
        assertEquals(lines + 1, vm.state.value.outputLines.size)
        val echoed = vm.state.value.outputLines.last()
        assertEquals("@r1>", echoed.text)
        assertEquals(CLIOutputType.COMMAND, echoed.type)
    }

    @Test
    fun cancellingAnIdleRepeaterCliDoesNotRadio() = runTest {
        val quiet = quietRepeaterCli()
        val vm = quiet.vm
        val lines = vm.state.value.outputLines.size
        assertEquals("", vm.state.value.currentInput)

        vm.cancelCurrentCommand()
        runCurrent()

        assertEquals(0, quiet.reads.serviceReads)
        assertEquals(0, quiet.reads.sessionReads)
        assertEquals(lines, vm.state.value.outputLines.size)
        assertEquals("", vm.state.value.currentInput)
        assertFalse(vm.state.value.isWaitingForResponse)
        assertFalse(vm.state.value.outputLines.any { it.text == "cancelled" })
    }

    @Test
    fun closeCancelsTheOwnedCommand() = runTest {
        var cancelled = false
        var sendCalls = 0
        val vm = NodeCliViewModel(
            scope = backgroundScope,
            sessionName = "room1",
            messages = { messages },
            dependencies = NodeCliViewModel.Dependencies(
                repeaterAdminService = { null },
                sessionId = { Uuid.normalized("A0000000-0000-0000-0000-000000000001") },
                sendRawCommand = { _, _ ->
                    sendCalls += 1
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled = true
                    }
                },
            ),
        )
        vm.setInput("status")
        vm.submit()
        runCurrent()
        assertTrue(vm.state.value.isWaitingForResponse)

        vm.close()
        runCurrent()

        assertTrue(cancelled)
        assertFalse(vm.state.value.isWaitingForResponse)
        assertEquals("cancelled", vm.state.value.outputLines.last().text)

        vm.setInput("again")
        vm.submit()
        vm.clear()
        runCurrent()

        assertEquals(1, sendCalls)
    }

    @Test
    fun typingARepeaterCommandLineDoesNotRadio() = runTest {
        val quiet = quietRepeaterCli()
        val vm = quiet.vm
        assertEquals("", vm.state.value.currentInput)
        assertFalse(vm.state.value.isWaitingForResponse)
        val lines = vm.state.value.outputLines.size

        vm.setInput("status")
        runCurrent()

        assertEquals(0, quiet.reads.serviceReads)
        assertEquals(0, quiet.reads.sessionReads)
        assertEquals("status", vm.state.value.currentInput)
        assertEquals(lines, vm.state.value.outputLines.size)
        assertFalse(vm.state.value.isWaitingForResponse)
        assertEquals("", vm.state.value.ghostText)
    }

    @Test
    fun clearingEmptyRepeaterTabSuggestionsDoesNotRadio() = runTest {
        val quiet = quietRepeaterCli()
        val vm = quiet.vm
        assertEquals("", vm.state.value.currentInput)
        assertEquals("", vm.state.value.ghostText)
        assertNull(vm.state.value.tabSuggestions)
        assertNull(vm.state.value.tabSelectionIndex)
        assertFalse(vm.state.value.isWaitingForResponse)
        assertEquals(0, quiet.reads.serviceReads)
        assertEquals(0, quiet.reads.sessionReads)
        val lines = vm.state.value.outputLines

        vm.clearTabState()
        runCurrent()

        assertEquals(0, quiet.reads.serviceReads)
        assertEquals(0, quiet.reads.sessionReads)
        assertEquals("", vm.state.value.currentInput)
        assertEquals("", vm.state.value.ghostText)
        assertNull(vm.state.value.tabSuggestions)
        assertNull(vm.state.value.tabSelectionIndex)
        assertFalse(vm.state.value.isWaitingForResponse)
        assertEquals(lines, vm.state.value.outputLines)
    }

    @Test
    fun recallingAnEmptyRepeaterCommandHistoryDoesNotRadio() = runTest {
        val quiet = quietRepeaterCli()
        val vm = quiet.vm
        assertEquals("", vm.state.value.currentInput)
        assertEquals("", vm.state.value.ghostText)
        assertNull(vm.state.value.tabSuggestions)
        assertNull(vm.state.value.tabSelectionIndex)
        assertFalse(vm.state.value.isWaitingForResponse)
        assertEquals(0, quiet.reads.serviceReads)
        assertEquals(0, quiet.reads.sessionReads)
        val lines = vm.state.value.outputLines

        vm.historyUp()
        runCurrent()

        assertEquals(0, quiet.reads.serviceReads)
        assertEquals(0, quiet.reads.sessionReads)
        assertEquals("", vm.state.value.currentInput)
        assertEquals("", vm.state.value.ghostText)
        assertNull(vm.state.value.tabSuggestions)
        assertNull(vm.state.value.tabSelectionIndex)
        assertFalse(vm.state.value.isWaitingForResponse)
        assertEquals(lines, vm.state.value.outputLines)
    }
}

private class QuietRepeaterCli(val vm: NodeCliViewModel, val reads: QuietRepeaterCliReads)

private class QuietRepeaterCliReads {
    var serviceReads = 0
    var sessionReads = 0
}
