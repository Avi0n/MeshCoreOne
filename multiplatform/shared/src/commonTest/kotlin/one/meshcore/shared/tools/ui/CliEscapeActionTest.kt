package one.meshcore.shared.tools.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/** Mirrors iOS `onKeyPress(.escape)`: clear an open tab cycle first, then cancel a waiting command, else dismiss. */
class CliEscapeActionTest {
    @Test
    fun clearsTabCycleBeforeAnythingElse() {
        val state = ToolsCliState(
            tabSuggestions = listOf("help", "logout"),
            tabSelectionIndex = 0,
            isWaitingForResponse = true,
        )
        assertEquals(CliEscapeAction.CLEAR_TAB, cliEscapeAction(state))
    }

    @Test
    fun cancelsWhenWaitingWithNoTabCycle() {
        assertEquals(CliEscapeAction.CANCEL, cliEscapeAction(ToolsCliState(isWaitingForResponse = true)))
    }

    @Test
    fun dismissesKeyboardWhenIdle() {
        assertEquals(CliEscapeAction.DISMISS, cliEscapeAction(ToolsCliState()))
    }
}
