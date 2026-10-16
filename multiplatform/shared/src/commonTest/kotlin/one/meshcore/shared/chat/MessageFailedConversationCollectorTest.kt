package one.meshcore.shared.chat

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MessageFailedConversationCollectorTest {
    private val messageId = Uuid.normalized("22222222-2222-2222-2222-222222222222")

    @Test
    fun failedAndResentReloadTheConversationList() = runTest(UnconfinedTestDispatcher()) {
        val status = MutableSharedFlow<MessageStatusEvent>()
        var reloads = 0
        val collector = MessageFailedConversationCollector(
            statusEvents = status,
            scope = this,
            onConversationsChanged = { reloads++ },
        )
        collector.start()
        status.emit(MessageStatusEvent.Failed(messageId))
        assertEquals(1, reloads)
        status.emit(MessageStatusEvent.Resent(messageId))
        assertEquals(2, reloads)
        status.emit(MessageStatusEvent.StatusResolved(messageId, MessageStatus.SENT))
        assertEquals(2, reloads)
        collector.stop()
    }

    @Test
    fun predicateMatchesIosFailAndResendOnly() {
        assertTrue(MessageStatusEvent.Failed(messageId).shouldRefreshFailedSendIndicators())
        assertTrue(MessageStatusEvent.Resent(messageId).shouldRefreshFailedSendIndicators())
        assertFalse(
            MessageStatusEvent.StatusResolved(messageId, MessageStatus.SENT)
                .shouldRefreshFailedSendIndicators(),
        )
        assertFalse(
            MessageStatusEvent.Retrying(messageId, attempt = 1, maxAttempts = 3)
                .shouldRefreshFailedSendIndicators(),
        )
        assertTrue(
            MessageStatusEvent.StatusResolved(messageId, MessageStatus.SENT)
                .shouldRefreshFailedSendIndicators(hasFailedSendBadges = true),
        )
    }
}
