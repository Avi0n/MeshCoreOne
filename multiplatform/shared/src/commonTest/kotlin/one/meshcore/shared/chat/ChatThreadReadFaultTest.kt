package one.meshcore.shared.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.model.MessageDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A failed unread clear leaves the opened page up and does not surface a load error. Cancellation
 * and an error leave that page up without a load error, and they propagate.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatThreadReadFaultTest {
    @Test
    fun aBookkeepingFaultLeavesThePageWithoutALoadError() = runTest {
        val store = openedStore(IllegalStateException("disk"))
        var conversationChanges = 0
        val (viewModel, coordinator) = makeThreadViewModel(
            store = store,
            onConversationsChanged = { conversationChanges += 1 },
        )

        viewModel.loadInitial()
        runCurrent()

        assertNull(viewModel.errorMessage)
        assertNull(viewModel.errorBannerMessage)
        assertTrue(viewModel.renderState.value.items.isNotEmpty())
        assertEquals(ChatRenderState.LoadPhase.LOADED, coordinator.renderState.phase)
        assertTrue(viewModel.initialLoadSettled)
        assertEquals(0, store.clearUnreadCalls)
        assertEquals(0, store.markFailedSendsSeenCalls)
        assertEquals(0, conversationChanges)
    }

    @Test
    fun aCancelledReadKeepsStagingWithoutALoadError() = runTest {
        val stopped = CancellationException("stopped")
        val store = openedStore(stopped)
        var conversationChanges = 0
        val (viewModel, coordinator) = makeThreadViewModel(
            store = store,
            onConversationsChanged = { conversationChanges += 1 },
        )

        val cancelled = assertFailsWith<CancellationException> {
            viewModel.loadInitial()
        }
        runCurrent()

        assertSame(stopped, cancelled)
        assertNull(viewModel.errorMessage)
        assertNull(viewModel.errorBannerMessage)
        assertTrue(viewModel.renderState.value.items.isNotEmpty())
        assertEquals(ChatRenderState.LoadPhase.LOADED, coordinator.renderState.phase)
        assertFalse(viewModel.initialLoadSettled)
        assertNull(viewModel.renderState.value.completedInitialLoadGeneration)
        assertEquals(0, store.clearUnreadCalls)
        assertEquals(0, store.markFailedSendsSeenCalls)
        assertEquals(0, conversationChanges)
    }

    @Test
    fun aReadErrorKeepsStagingWithoutALoadError() = runTest {
        val probe = ReadBookkeepingProbeError()
        val store = openedStore(probe)
        var conversationChanges = 0
        val (viewModel, coordinator) = makeThreadViewModel(
            store = store,
            onConversationsChanged = { conversationChanges += 1 },
        )

        val thrown = assertFailsWith<ReadBookkeepingProbeError> {
            viewModel.loadInitial()
        }
        runCurrent()

        assertSame(probe, thrown)
        assertNull(viewModel.errorMessage)
        assertNull(viewModel.errorBannerMessage)
        assertTrue(viewModel.renderState.value.items.isNotEmpty())
        assertEquals(ChatRenderState.LoadPhase.LOADED, coordinator.renderState.phase)
        assertFalse(viewModel.initialLoadSettled)
        assertNull(viewModel.renderState.value.completedInitialLoadGeneration)
        assertEquals(0, store.clearUnreadCalls)
        assertEquals(0, store.markFailedSendsSeenCalls)
        assertEquals(0, conversationChanges)
    }

    private fun openedStore(failure: Throwable): FakeChatThreadStore =
        FakeChatThreadStore(rows = listOf(readFaultRow())).apply { clearUnreadFailure = failure }

    private fun readFaultRow(): MessageDto = ChatFixtures.message(
        text = "opened",
        timestamp = 1_000L,
        createdAt = 1_000.0,
        direction = MessageDirection.INCOMING,
        contactID = ChatFixtures.CONTACT,
    )
}

private class ReadBookkeepingProbeError : Error("probe")
