package one.meshcore.shared.chat

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.chat.reactions.ReactionParser
import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ChatThreadDeletionPagingTest {
    @Test
    fun deletingALoadedRowKeepsEveryOlderMessageReachable() = runTest {
        for (isChannel in listOf(false, true)) {
            for (includeHiddenReaction in listOf(false, true)) {
                val total = ChatCoordinator.PAGE_SIZE + 10
                val rows = pagingRows(total, isChannel, includeHiddenReaction)
                val hiddenID = rows[total - 2].id.takeIf { includeHiddenReaction }
                val store = FakeChatThreadStore(rows)
                val conversation = if (isChannel) {
                    ChatConversationID.channel(ChatFixtures.RADIO, 0)
                } else {
                    ChatConversationID.dm(ChatFixtures.RADIO, ChatFixtures.CONTACT)
                }
                val (viewModel, coordinator) = makeThreadViewModel(store = store, conversationID = conversation)
                viewModel.loadInitial()
                runCurrent()
                assertEquals(
                    rows.takeLast(ChatCoordinator.PAGE_SIZE).filterNot {
                        it.id == hiddenID
                    }.map { it.id },
                    coordinator.messages.map { it.id },
                )

                viewModel.deleteMessage(rows.last().id)
                runCurrent()
                assertNull(viewModel.renderState.value.errorMessage)

                viewModel.loadOlderMessages()
                runCurrent()
                val expected = rows.dropLast(1).filterNot { it.id == hiddenID }.map { it.id }
                assertEquals(expected, coordinator.messages.map { it.id })
                assertEquals(expected, viewModel.renderState.value.items.map { it.id })
                assertEquals(total - 1, coordinator.renderState.totalFetchedCount)
                assertFalse(coordinator.renderState.hasMoreMessages)
                assertNull(viewModel.renderState.value.errorMessage)
                viewModel.cleanup()
            }
        }
    }

    @Test
    fun anUnbakedLoadedRowAdjustsTheRawOffsetOnlyOnce() = runTest {
        val row = incomingThreadRows(1).single()
        val (viewModel, coordinator) = makeThreadViewModel(store = FakeChatThreadStore(listOf(row)))
        viewModel.writer?.replaceAll(listOf(row))
        viewModel.writer?.updateRenderState { it.copy(totalFetchedCount = 3) }
        assertTrue(coordinator.renderState.items.isEmpty())

        viewModel.deleteMessage(row.id)
        assertTrue(coordinator.messages.isEmpty())
        assertEquals(2, coordinator.renderState.totalFetchedCount)

        viewModel.deleteMessage(row.id)
        viewModel.deleteMessage(Uuid.random())
        assertEquals(2, coordinator.renderState.totalFetchedCount)
    }

    @Test
    fun removingALoadedRowDoesNotMakeTheRawOffsetNegative() = runTest {
        val row = incomingThreadRows(1).single()
        val (viewModel, coordinator) = makeThreadViewModel(store = FakeChatThreadStore(listOf(row)))
        viewModel.writer?.replaceAll(listOf(row))
        viewModel.writer?.updateRenderState { it.copy(totalFetchedCount = 0) }
        viewModel.deleteMessage(row.id)
        assertEquals(0, coordinator.renderState.totalFetchedCount)
        assertTrue(coordinator.messages.isEmpty())
    }

    private fun pagingRows(count: Int, isChannel: Boolean, includeHiddenReaction: Boolean): List<MessageDto> =
        (0 until count).map { index ->
            val row = if (isChannel) {
                ChatFixtures.channelMessage(timestamp = 1_000L + index).copy(createdAt = 1_000.0 + index)
            } else {
                ChatFixtures.message(
                    text = "row-$index",
                    timestamp = 1_000L + index,
                    createdAt = 1_000.0 + index,
                    direction = MessageDirection.INCOMING,
                    contactID = ChatFixtures.CONTACT,
                )
            }
            if (includeHiddenReaction && index == count - 2) {
                row.copy(
                    direction = MessageDirection.OUTGOING,
                    status = MessageStatus.SENT,
                    text = if (isChannel) {
                        ReactionParser.buildReactionText("👍", "Sender", "target", 1)
                    } else {
                        ReactionParser.buildDmReactionText("👍", "target", 1)
                    },
                )
            } else {
                row
            }
        }
}
