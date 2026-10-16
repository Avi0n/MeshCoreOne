package one.meshcore.shared.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.MessageDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * A failed seen-mark stays quiet and the status update continues. Cancellation and an error stop
 * that update.
 */
class ChatThreadFailedSendSeenFaultTest {
    @Test
    fun aSeenMarkFaultStillContinuesTheStatusUpdate() = runTest {
        val message = failedSendRow()
        val store = storeFailingSeenMark(message, IllegalStateException("disk"))
        var conversationChanges = 0
        val (viewModel, _) = makeThreadViewModel(
            store = store,
            onConversationsChanged = { conversationChanges += 1 },
        )

        viewModel.handleStatus(MessageStatusEvent.Failed(message.id))

        assertNull(viewModel.errorMessage)
        assertNull(viewModel.errorBannerMessage)
        assertEquals(0, store.markFailedSendsSeenCalls)
        assertEquals(1, conversationChanges)
    }

    @Test
    fun aCancelledSeenMarkStopsTheStatusUpdate() = runTest {
        val message = failedSendRow()
        val stopped = CancellationException("stopped")
        val store = storeFailingSeenMark(message, stopped)
        var conversationChanges = 0
        val (viewModel, _) = makeThreadViewModel(
            store = store,
            onConversationsChanged = { conversationChanges += 1 },
        )

        val cancelled = assertFailsWith<CancellationException> {
            viewModel.handleStatus(MessageStatusEvent.Failed(message.id))
        }

        assertSame(stopped, cancelled)
        assertNull(viewModel.errorMessage)
        assertNull(viewModel.errorBannerMessage)
        assertEquals(0, store.markFailedSendsSeenCalls)
        assertEquals(0, conversationChanges)
    }

    @Test
    fun aSeenMarkErrorStopsTheStatusUpdate() = runTest {
        val message = failedSendRow()
        val probe = SeenMarkProbeError()
        val store = storeFailingSeenMark(message, probe)
        var conversationChanges = 0
        val (viewModel, _) = makeThreadViewModel(
            store = store,
            onConversationsChanged = { conversationChanges += 1 },
        )

        val thrown = assertFailsWith<SeenMarkProbeError> {
            viewModel.handleStatus(MessageStatusEvent.Failed(message.id))
        }

        assertSame(probe, thrown)

        assertNull(viewModel.errorMessage)
        assertNull(viewModel.errorBannerMessage)
        assertEquals(0, store.markFailedSendsSeenCalls)
        assertEquals(0, conversationChanges)
    }

    private fun storeFailingSeenMark(message: MessageDto, failure: Throwable): FakeChatThreadStore =
        FakeChatThreadStore(rows = listOf(message)).apply { markFailedSendsSeenFailure = failure }

    private fun failedSendRow(): MessageDto = ChatFixtures.message(
        text = "undelivered",
        contactID = ChatFixtures.CONTACT,
    )
}

private class SeenMarkProbeError : Error("probe")
