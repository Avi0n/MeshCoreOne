package one.meshcore.shared.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.model.MessageStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ChatThreadStatusCancellationTest {
    @Test
    fun cancelledMentionWriteDoesNotMarkTheRenderedMentionSeen() = runTest {
        val store = FakeChatThreadStore()
        val (viewModel, _) = makeThreadViewModel(store = store)
        viewModel.loadInitial()
        runCurrent()
        store.markMentionSeenFailure = CancellationException("store cancelled")
        val mention = ChatFixtures.message(direction = MessageDirection.INCOMING)
            .copy(containsSelfMention = true, mentionSeen = false)

        viewModel.handleIncoming(IncomingMessageEvent.DirectMessageReceived(mention, ChatFixtures.CONTACT))
        runCurrent()

        assertFalse(viewModel.renderState.value.items.single().envelope.mentionSeen)
        assertTrue(store.markedMentionIds.isEmpty())
        assertNull(viewModel.renderState.value.errorMessage)
    }

    @Test
    fun cancelledFailureWriteDoesNotPublishASendFailure() = runTest {
        val messageStore = FakeSendQueueStore().apply {
            insertError = { IllegalStateException("disk full") }
        }
        val ids = SequentialIds()
        val messageService = testMessageService(FakeChatSession(), messageStore.messages, backgroundScope, ids = ids)
        val queue = ChatSendQueueService(
            radioID = ChatFixtures.RADIO,
            collaborators = ChatSendQueueCollaborators(
                store = messageStore,
                messageService = messageService,
                channelResolver = { true },
                scope = backgroundScope,
                now = { TEST_NOW_SECONDS },
                newPendingSendId = ids::next,
            ),
        )
        val threadStore = FakeChatThreadStore().apply {
            extraFetch = { id -> messageStore.messages.fetchMessage(id) }
            markMessageFailedFailure = CancellationException("store cancelled")
        }
        val contact = ChatFixtures.contact(name = "Bob", id = ChatFixtures.CONTACT)
        val (viewModel, _) = makeThreadViewModel(
            store = threadStore,
            currentContact = { contact },
            messageService = { messageService },
            sendQueue = { queue },
        )
        viewModel.loadInitial()
        runCurrent()
        threadStore.markFailedSendsSeenCalls = 0

        assertFailsWith<CancellationException> { viewModel.sendMessage("hi") }
        runCurrent()

        assertNull(viewModel.renderState.value.sendErrorMessage)
        assertEquals(MessageStatus.PENDING, viewModel.renderState.value.items.single().footer.status)
        assertEquals(0, threadStore.markFailedSendsSeenCalls)
    }
}
