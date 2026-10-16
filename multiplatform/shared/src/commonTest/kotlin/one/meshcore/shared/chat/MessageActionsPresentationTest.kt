package one.meshcore.shared.chat

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The 300 ms wait that lets the actions sheet finish dismissing before a follow-on
 * sheet or focus request is issued. Mirrors iOS `MessageActionsPresentation.dismissalDelay`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MessageActionsPresentationTest {
    @Test
    fun launchAfterDismissRunsOnlyAfterTheDelay() = runTest {
        var count = 0
        launchAfterMessageActionsDismiss { count += 1 }
        assertEquals(0, count)
        advanceTimeBy(ChatConstants.MESSAGE_ACTIONS_DISMISSAL_MS)
        runCurrent()
        assertEquals(1, count)
    }
}
