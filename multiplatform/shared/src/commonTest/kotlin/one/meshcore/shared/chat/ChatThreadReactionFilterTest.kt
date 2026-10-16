package one.meshcore.shared.chat

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.chat.reactions.ReactionParser
import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.model.MessageStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A reaction the user sends is an ordinary text message on the wire; it renders as a badge on its target,
 * so the timeline must drop it. A failed one stays so it can be retried. Mirrors the Swift
 * `filterOutgoingReactionMessages` / `isHiddenOutgoingReaction`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatThreadReactionFilterTest {
    private val targetText = "meet at the ridge"
    private val targetTimestamp = 1_700_000_000L

    private fun plain(timestamp: Long) = ChatFixtures.message(
        text = targetText,
        timestamp = timestamp,
        createdAt = timestamp.toDouble(),
        direction = MessageDirection.INCOMING,
    )

    private fun outgoingText(text: String, timestamp: Long, status: MessageStatus, channelIndex: Int? = null) =
        ChatFixtures.message(
            text = text,
            timestamp = timestamp,
            createdAt = timestamp.toDouble(),
            direction = MessageDirection.OUTGOING,
            status = status,
            channelIndex = channelIndex,
        )

    private fun dmReaction(status: MessageStatus, timestamp: Long) = outgoingText(
        ReactionParser.buildDmReactionText("👍", targetText, targetTimestamp),
        timestamp,
        status,
    )

    private fun channelReaction(status: MessageStatus, timestamp: Long) = outgoingText(
        ReactionParser.buildReactionText("👍", "Alpha", targetText, targetTimestamp),
        timestamp,
        status,
        channelIndex = 1,
    )

    @Test
    fun sentDmReactionIsHiddenAndFailedOneStaysVisible() = runTest {
        val sent = dmReaction(MessageStatus.SENT, 1_002)
        val failed = dmReaction(MessageStatus.FAILED, 1_003)
        val store = FakeChatThreadStore(rows = listOf(plain(1_001), sent, failed))
        val (viewModel, _) = makeThreadViewModel(store = store)

        viewModel.loadInitial()
        runCurrent()

        val ids = viewModel.renderState.value.items.map { it.id }
        assertEquals(2, ids.size)
        assertTrue(failed.id in ids)
        assertTrue(sent.id !in ids)
    }

    @Test
    fun sentChannelReactionIsHiddenFromAChannelThread() = runTest {
        val sent = channelReaction(MessageStatus.SENT, 1_002)
        val store = FakeChatThreadStore(rows = listOf(plain(1_001).copy(channelIndex = 1), sent))
        val (viewModel, _) = makeThreadViewModel(
            store = store,
            conversationID = ChatConversationID.channel(ChatFixtures.RADIO, 1),
        )

        viewModel.loadInitial()
        runCurrent()

        assertTrue(sent.id !in viewModel.renderState.value.items.map { it.id })
    }

    @Test
    fun anOrdinaryOutgoingMessageIsNeverMistakenForAReaction() = runTest {
        val ordinary = outgoingText("👍 nice one", 1_002, MessageStatus.SENT)
        val store = FakeChatThreadStore(rows = listOf(plain(1_001), ordinary))
        val (viewModel, _) = makeThreadViewModel(store = store)

        viewModel.loadInitial()
        runCurrent()

        assertTrue(ordinary.id in viewModel.renderState.value.items.map { it.id })
    }
}
