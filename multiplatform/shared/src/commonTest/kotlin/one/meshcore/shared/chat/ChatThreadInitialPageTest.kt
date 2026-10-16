package one.meshcore.shared.chat

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The open-a-conversation page must hold the whole unread backlog plus a little read context, and the
 * divider must show for any backlog at all. Together they keep the divider on the real boundary row
 * instead of pinning it to the oldest loaded message. Mirrors the iOS `ChatCoordinator.initialPageSize`
 * and the zero divider threshold.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatThreadInitialPageTest {
    @Test
    fun `initial page is sized to the unread backlog`() = runTest {
        val store = FakeChatThreadStore(rows = incomingThreadRows(TOTAL_ROWS)).apply {
            storedUnreadCount = BIG_BACKLOG
        }
        val (viewModel, _) = makeThreadViewModel(store = store)

        viewModel.loadInitial()
        runCurrent()

        assertEquals(BIG_BACKLOG + ChatCoordinator.DIVIDER_READ_CONTEXT, store.fetchLimits.first())
        assertEquals(BIG_BACKLOG + ChatCoordinator.DIVIDER_READ_CONTEXT, viewModel.renderState.value.items.size)
    }

    @Test
    fun `divider lands on the boundary row of a backlog larger than one page`() = runTest {
        val backing = incomingThreadRows(TOTAL_ROWS)
        val store = FakeChatThreadStore(rows = backing).apply { storedUnreadCount = BIG_BACKLOG }
        val (viewModel, _) = makeThreadViewModel(store = store)

        viewModel.loadInitial()
        runCurrent()

        // The first unread row: BIG_BACKLOG rows back from the newest. With a page of PAGE_SIZE this row is
        // not even loaded, so the divider collapses onto the oldest loaded message.
        val boundary = backing[TOTAL_ROWS - BIG_BACKLOG]
        assertEquals(boundary.id, viewModel.renderState.value.newMessagesDividerMessageID)
    }

    @Test
    fun `a small backlog still draws the divider`() = runTest {
        val backing = incomingThreadRows(SMALL_TOTAL)
        val store = FakeChatThreadStore(rows = backing).apply { storedUnreadCount = SMALL_BACKLOG }
        val (viewModel, _) = makeThreadViewModel(store = store)

        viewModel.loadInitial()
        runCurrent()

        val dividerID = viewModel.renderState.value.newMessagesDividerMessageID
        assertNotNull(dividerID, "any unread backlog draws the divider, as on iOS")
        assertEquals(backing[SMALL_TOTAL - SMALL_BACKLOG].id, dividerID)
    }

    @Test
    fun `initial page caps a huge unread backlog`() {
        val unread = ChatCoordinator.MAX_INITIAL_PAGE_SIZE * 10
        assertEquals(
            ChatCoordinator.MAX_INITIAL_PAGE_SIZE,
            ChatCoordinator.initialPageSize(unread),
        )
    }

    private companion object {
        const val TOTAL_ROWS = 200
        const val BIG_BACKLOG = 120
        const val SMALL_TOTAL = 20
        const val SMALL_BACKLOG = 4
    }
}
