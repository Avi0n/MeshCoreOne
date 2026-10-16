package one.meshcore.shared.chat.reactions

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.chat.ChatFixtures
import one.meshcore.shared.chat.FakeChatSession
import one.meshcore.shared.chat.InMemoryMessageStore
import one.meshcore.shared.chat.SequentialIds
import one.meshcore.shared.chat.testMessageService
import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.model.MessageStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Outgoing reactions go through [MessageServiceReactionSender] and [one.meshcore.shared.chat.MessageService].
 * The session is [FakeChatSession]: it records the payload and never opens a radio. The summary is written
 * only after that send returns. A send error leaves the summary unset.
 */
class ReactionMessageServiceSendTest {
    @Test
    fun channelReactionPersistsTheSummaryOnlyAfterTheSessionAccepts() = runTest {
        val message = channelMessage()
        val reactionStore = InMemoryReactionStore()
        val messages = InMemoryMessageStore()
        val session = FakeChatSession()
        var summaryAbsentAtRadioSend = false
        session.beforeScopedSend = {
            summaryAbsentAtRadioSend = reactionStore.reactions.isEmpty() && reactionStore.summaries[message.id] == null
        }
        val expectedId = SequentialIds().next()

        val result = reactionService().react(
            CHANNEL_EMOJI,
            message,
            LOCAL_NODE,
            reactionStore,
            messageSender(session, messages, backgroundScope),
        )

        val sent = assertIs<ReactionSendResult.Sent>(result)
        assertEquals(CHANNEL_SUMMARY, sent.summary)
        assertTrue(summaryAbsentAtRadioSend)
        assertEquals(CHANNEL_INDEX, session.channelSends.single().first)
        assertTrue(session.channelSends.single().second.startsWith(CHANNEL_WIRE_PREFIX))
        assertEquals(CHANNEL_SUMMARY, reactionStore.summaries[message.id])
        val saved = messages.fetchMessage(expectedId)
        assertEquals(session.channelSends.single().second, saved?.text)
        assertEquals(MessageStatus.SENT, saved?.status)
    }

    @Test
    fun channelReactionFailureLeavesTheSummaryUnset() = runTest {
        val message = channelMessage()
        val reactionStore = InMemoryReactionStore()
        val messages = InMemoryMessageStore()
        val session = FakeChatSession().apply { directSendError = { IllegalStateException("radio offline") } }
        val expectedId = SequentialIds().next()

        val result = reactionService().react(
            CHANNEL_EMOJI,
            message,
            LOCAL_NODE,
            reactionStore,
            messageSender(session, messages, backgroundScope),
        )

        assertIs<ReactionSendResult.Failed>(result)
        assertTrue(reactionStore.reactions.isEmpty())
        assertNull(reactionStore.summaries[message.id])
        assertTrue(session.channelSends.isEmpty())
        assertEquals(MessageStatus.FAILED, messages.fetchMessage(expectedId)?.status)
    }

    @Test
    fun directReactionPersistsTheSummaryOnlyAfterTheSessionAccepts() = runTest {
        val contact = ChatFixtures.contact(name = "Alice", id = ChatFixtures.CONTACT)
        val message = deliveredDirectMessage(contact.id)
        val reactionStore = InMemoryReactionStore(contacts = listOf(contact))
        val messages = InMemoryMessageStore()
        val session = FakeChatSession().apply { ackArrives = true }
        val expectedId = SequentialIds().next()

        val result = reactionService().react(
            DIRECT_EMOJI,
            message,
            LOCAL_NODE,
            reactionStore,
            messageSender(session, messages, backgroundScope),
        )

        val sent = assertIs<ReactionSendResult.Sent>(result)
        assertEquals(DIRECT_SUMMARY, sent.summary)
        assertEquals(1, session.directSendAttempts.size)
        assertEquals(DIRECT_SUMMARY, reactionStore.summaries[message.id])
        val saved = messages.fetchMessage(expectedId)
        assertEquals(MessageStatus.SENT, saved?.status)
        assertTrue(saved?.text?.startsWith(DIRECT_WIRE_PREFIX) == true)
    }

    @Test
    fun directReactionFailureLeavesTheSummaryUnset() = runTest {
        val contact = ChatFixtures.contact(name = "Alice", id = ChatFixtures.CONTACT)
        val message = deliveredDirectMessage(contact.id)
        val reactionStore = InMemoryReactionStore(contacts = listOf(contact))
        val messages = InMemoryMessageStore()
        val session = FakeChatSession().apply { directSendError = { IllegalStateException("radio offline") } }
        val expectedId = SequentialIds().next()

        val result = reactionService().react(
            DIRECT_EMOJI,
            message,
            LOCAL_NODE,
            reactionStore,
            messageSender(session, messages, backgroundScope),
        )

        assertIs<ReactionSendResult.Failed>(result)
        assertTrue(reactionStore.reactions.isEmpty())
        assertNull(reactionStore.summaries[message.id])
        assertTrue(session.directSendAttempts.isEmpty())
        assertEquals(MessageStatus.FAILED, messages.fetchMessage(expectedId)?.status)
    }

    private fun reactionService() = ReactionService(newReactionId = { ChatFixtures.freshUuid() }, now = { NOW })

    private fun channelMessage() = ChatFixtures.message(
        text = "Hello world",
        timestamp = TARGET_TIMESTAMP,
        direction = MessageDirection.INCOMING,
        status = MessageStatus.DELIVERED,
        contactID = null,
        channelIndex = CHANNEL_INDEX,
        senderNodeName = "Alice",
    )

    private fun messageSender(session: FakeChatSession, messages: InMemoryMessageStore, scope: CoroutineScope) =
        MessageServiceReactionSender { testMessageService(session, messages, scope) }

    private companion object {
        const val NOW = 1_704_067_500.0
        const val TARGET_TIMESTAMP = 1_704_067_200L
        const val CHANNEL_INDEX = 3
        const val LOCAL_NODE = "Me"
        const val CHANNEL_EMOJI = "👍"
        const val CHANNEL_SUMMARY = "👍:1"
        const val CHANNEL_WIRE_PREFIX = "@[Alice]👍\n"
        const val DIRECT_EMOJI = "❤️"
        const val DIRECT_SUMMARY = "❤️:1"
        const val DIRECT_WIRE_PREFIX = "❤️\n"
    }
}
