package one.meshcore.shared.chat

import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.MessageStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ChatSendQueueTerminalAdmissionTest {
    @Test
    fun aStoppedDirectQueueRejectsFreshAdmissionBeforePersistence() =
        verify(channel = false, retry = false, stopped = true)

    @Test
    fun aStoppedChannelQueueRejectsFreshAdmissionBeforePersistence() =
        verify(channel = true, retry = false, stopped = true)

    @Test
    fun aStoppedDirectQueueRejectsRetryBeforePersistence() = verify(channel = false, retry = true, stopped = true)

    @Test
    fun aStoppedChannelQueueRejectsRetryBeforePersistence() = verify(channel = true, retry = true, stopped = true)

    @Test
    fun aLiveDirectQueueStillPersistsFreshAdmission() = verify(channel = false, retry = false, stopped = false)

    @Test
    fun aLiveChannelQueueStillPersistsFreshAdmission() = verify(channel = true, retry = false, stopped = false)

    @Test
    fun aLiveDirectQueueStillPersistsRetry() = verify(channel = false, retry = true, stopped = false)

    @Test
    fun aLiveChannelQueueStillPersistsRetry() = verify(channel = true, retry = true, stopped = false)

    private fun verify(channel: Boolean, retry: Boolean, stopped: Boolean) = runTest {
        val store = FakeSendQueueStore()
        val message = ChatFixtures.message(
            status = if (retry) MessageStatus.FAILED else MessageStatus.PENDING,
            contactID = ChatFixtures.CONTACT.takeUnless { channel },
            channelIndex = CHANNEL_INDEX.takeIf { channel },
        )
        store.messages.saveMessage(message)
        val queue = loggedSendQueue(backgroundScope, mutableListOf(), store, ChatFixtures.RADIO, TEST_NOW_SECONDS)
        if (stopped) queue.shutdown()
        try {
            val outcome = runCatching {
                if (channel) {
                    val envelope = ChannelMessageEnvelope(
                        messageID = message.id,
                        channelIndex = CHANNEL_INDEX,
                        isResend = retry,
                        messageText = message.text,
                        messageTimestamp = message.timestamp,
                        localNodeName = null,
                    )
                    if (retry) queue.retryChannel(envelope) else queue.enqueueChannel(envelope)
                } else {
                    val envelope = DirectMessageEnvelope(message.id, ChatFixtures.CONTACT, isResend = retry)
                    if (retry) queue.retryDM(envelope) else queue.enqueueDM(envelope)
                }
            }
            val pending = store.fetchPendingSends(ChatFixtures.RADIO)
            val stored = store.messages.fetchMessage(message.id)
            println("terminal-admission channel=$channel retry=$retry stopped=$stopped rows=${pending.size}")
            if (stopped) {
                assertIs<ChatSendQueueServiceException.NotConnected>(outcome.exceptionOrNull())
                assertEquals(emptyList(), pending)
                assertEquals(message, stored)
            } else {
                assertNull(outcome.exceptionOrNull())
                assertEquals(message.id, pending.single().messageID)
                assertEquals(message.radioID, pending.single().radioID)
                assertEquals(MessageStatus.PENDING, stored?.status)
            }
        } finally {
            queue.shutdown()
        }
    }

    private companion object {
        const val CHANNEL_INDEX = 1
    }
}
