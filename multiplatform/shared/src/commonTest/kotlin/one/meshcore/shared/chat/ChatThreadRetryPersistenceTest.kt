package one.meshcore.shared.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Retrying a failed DM must move the PERSISTED row out of `.failed`, not just the in-memory timeline. The
 * status guards treat `.failed` as terminal, so a row left `.failed` on disk refuses every later terminal
 * write: the live bubble shows delivered, the next cold load shows "Failed" with a retry button, and the
 * responder resends a message the peer already has. Mirrors the Swift `replacePendingSendForRetry`
 * transaction, and runs against the production [DefaultSendQueueStore] composition rather than a double.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatThreadRetryPersistenceTest {
    private companion object {
        const val ACK_CODE = 0x11223344L
        const val CHANNEL_INDEX = 0
    }

    private class Harness(
        val viewModel: ChatThreadViewModel,
        val messages: InMemoryMessageStore,
        val pending: InMemoryPendingSendStore,
        val messageID: Uuid,
        val contact: ContactDto,
    )

    private suspend fun TestScope.harness(): Harness {
        val messages = InMemoryMessageStore()
        val pending = InMemoryPendingSendStore()
        val contact = ChatFixtures.contact(name = "Bob", id = ChatFixtures.CONTACT)
        val queueStore = DefaultSendQueueStore(pending, messages) { id -> contact.takeIf { it.id == id } }
        val ids = SequentialIds()
        val messageService = testMessageService(FakeChatSession(), messages, backgroundScope, ids = ids)
        val sendQueue = ChatSendQueueService(
            radioID = ChatFixtures.RADIO,
            collaborators = ChatSendQueueCollaborators(
                store = queueStore,
                messageService = messageService,
                channelResolver = { true },
                scope = backgroundScope,
                now = { TEST_NOW_SECONDS },
                newPendingSendId = PendingSendIds()::next,
            ),
        )
        // A DM the send path already gave up on: `.failed` on disk with its queue row still parked.
        val failed = messageService.createPendingMessage("hi", contact)
        messages.updateMessageStatus(failed.id, MessageStatus.FAILED)
        pending.insertPendingSendAssigningSequence(
            DirectMessageEnvelope(failed.id, contact.id)
                .toPendingSendDto(PendingSendIds().next(), ChatFixtures.RADIO, 1, TEST_NOW_SECONDS, attemptCount = 3),
        )
        val (viewModel, _) = makeThreadViewModel(
            store = FakeChatThreadStore(rows = listOf(failed)),
            currentContact = { contact },
            messageService = { messageService },
            sendQueue = { sendQueue },
        )
        return Harness(viewModel, messages, pending, failed.id, contact)
    }

    @Test
    fun `retry leaves the persisted row out of failed`() = runTest {
        val harness = harness()
        assertEquals(MessageStatus.FAILED, harness.messages.fetchMessage(harness.messageID)?.status)

        harness.viewModel.retryMessage(harness.messageID)

        assertEquals(
            MessageStatus.PENDING,
            harness.messages.fetchMessage(harness.messageID)?.status,
            "the retry transaction must flip the stored row back to pending",
        )
        assertEquals(
            false,
            harness.pending.fetchPendingSendsForMessage(harness.messageID).single().isResend,
            "failed-bubble Retry is a first-send retry, not Send Again",
        )
    }

    @Test
    fun `send again of a failed DM is a resend`() = runTest {
        val harness = harness()
        harness.viewModel.retryMessage(harness.messageID, isResend = true)
        assertEquals(
            true,
            harness.pending.fetchPendingSendsForMessage(harness.messageID).single().isResend,
        )
    }

    @Test
    fun `a terminal delivered write is accepted after a retry`() = runTest {
        val harness = harness()

        harness.viewModel.retryMessage(harness.messageID)
        harness.messages.updateMessageAck(harness.messageID, ACK_CODE, MessageStatus.DELIVERED, roundTripTime = 900)

        assertEquals(
            MessageStatus.DELIVERED,
            harness.messages.fetchMessage(harness.messageID)?.status,
            "the end-to-end ACK must not be refused by a stale failed row",
        )
    }

    @Test
    fun `retry replaces the parked queue row instead of adding a second`() = runTest {
        val harness = harness()

        harness.viewModel.retryMessage(harness.messageID)

        assertEquals(1, harness.pending.fetchPendingSendsForMessage(harness.messageID).size)
    }

    @Test
    fun `channel retry replaces the parked queue row and leaves pending`() = runTest {
        val messages = InMemoryMessageStore()
        val pending = InMemoryPendingSendStore()
        val queueStore = DefaultSendQueueStore(pending, messages) { null }
        val ids = SequentialIds()
        val messageService = testMessageService(FakeChatSession(), messages, backgroundScope, ids = ids)
        val sendQueue = ChatSendQueueService(
            radioID = ChatFixtures.RADIO,
            collaborators = ChatSendQueueCollaborators(
                store = queueStore,
                messageService = messageService,
                channelResolver = { true },
                scope = backgroundScope,
                now = { TEST_NOW_SECONDS },
                newPendingSendId = PendingSendIds()::next,
            ),
        )
        val failed = messageService.createPendingChannelMessage("chan", CHANNEL_INDEX, ChatFixtures.RADIO)
        messages.updateMessageStatus(failed.id, MessageStatus.FAILED)
        pending.insertPendingSendAssigningSequence(
            ChannelMessageEnvelope(
                messageID = failed.id,
                channelIndex = CHANNEL_INDEX,
                isResend = true,
                messageText = failed.text,
                messageTimestamp = failed.timestamp,
                localNodeName = "Test",
            ).toPendingSendDto(PendingSendIds().next(), ChatFixtures.RADIO, 1, TEST_NOW_SECONDS, attemptCount = 3),
        )
        val (viewModel, _) = makeThreadViewModel(
            store = FakeChatThreadStore(rows = listOf(failed)),
            conversationID = ChatConversationID.channel(ChatFixtures.RADIO, CHANNEL_INDEX),
            messageService = { messageService },
            sendQueue = { sendQueue },
        )

        viewModel.loadInitial()
        viewModel.retryMessage(failed.id)

        assertEquals(MessageStatus.PENDING, messages.fetchMessage(failed.id)?.status)
        assertEquals(1, pending.fetchPendingSendsForMessage(failed.id).size)
    }

    @Test
    fun `channel send again of a sent row does not drop it to pending`() = runTest {
        val messages = InMemoryMessageStore()
        val pending = InMemoryPendingSendStore()
        val queueStore = DefaultSendQueueStore(pending, messages) { null }
        val ids = SequentialIds()
        val messageService = testMessageService(FakeChatSession(), messages, backgroundScope, ids = ids)
        val sendQueue = ChatSendQueueService(
            radioID = ChatFixtures.RADIO,
            collaborators = ChatSendQueueCollaborators(
                store = queueStore,
                messageService = messageService,
                channelResolver = { true },
                scope = backgroundScope,
                now = { TEST_NOW_SECONDS },
                newPendingSendId = PendingSendIds()::next,
            ),
        )
        val sent = messageService.createPendingChannelMessage("chan", CHANNEL_INDEX, ChatFixtures.RADIO)
        messages.updateMessageStatus(sent.id, MessageStatus.SENT)
        val (viewModel, _) = makeThreadViewModel(
            store = FakeChatThreadStore(rows = listOf(sent)),
            conversationID = ChatConversationID.channel(ChatFixtures.RADIO, CHANNEL_INDEX),
            messageService = { messageService },
            sendQueue = { sendQueue },
        )

        viewModel.loadInitial()
        viewModel.retryMessage(sent.id, isResend = true)

        assertEquals(
            MessageStatus.SENT,
            messages.fetchMessage(sent.id)?.status,
            "Send Again must not clobber terminal .sent so a later drain cannot fail an already-broadcast packet",
        )
        assertEquals(1, pending.fetchPendingSendsForMessage(sent.id).size)
        assertEquals(true, pending.fetchPendingSendsForMessage(sent.id).single().isResend)
    }

    @Test
    fun `channel send again without a send queue reports unable to send`() = runTest {
        val sent = ChatFixtures.message(
            status = MessageStatus.SENT,
            contactID = null,
            channelIndex = CHANNEL_INDEX,
        )
        val store = FakeChatThreadStore(rows = listOf(sent))
        val (viewModel, _) = makeThreadViewModel(
            store = store,
            conversationID = ChatConversationID.channel(ChatFixtures.RADIO, CHANNEL_INDEX),
            sendQueue = { null },
        )
        viewModel.loadInitial()
        runCurrent()
        viewModel.retryMessage(sent.id, isResend = true)
        runCurrent()

        assertEquals(emptyList(), store.failedIds)
        assertEquals(MessageStatus.SENT, viewModel.renderState.value.items.single().footer.status)
        assertEquals(
            ChatErrorMessages.developerEnglish().unableToSend,
            viewModel.renderState.value.sendErrorMessage,
        )
    }

    @Test
    fun `retry without a live send queue leaves the failed DM unparked`() = runTest {
        val failed = ChatFixtures.message(
            status = MessageStatus.FAILED,
            contactID = ChatFixtures.CONTACT,
        )
        val store = FakeChatThreadStore(rows = listOf(failed))
        val (viewModel, _) = makeThreadViewModel(
            store = store,
            currentContact = { ChatFixtures.contact(name = "Bob", id = ChatFixtures.CONTACT) },
            sendQueue = { null },
        )
        viewModel.loadInitial()
        runCurrent()
        viewModel.retryMessage(failed.id)
        runCurrent()

        assertEquals(0, store.retriedPendingSends.size)
        assertEquals(MessageStatus.FAILED, viewModel.renderState.value.items.single().footer.status)
        assertEquals(null, viewModel.renderState.value.sendErrorMessage)
    }

    @Test
    fun `send again without a live send queue leaves the failed DM unparked`() = runTest {
        val failed = ChatFixtures.message(
            status = MessageStatus.FAILED,
            contactID = ChatFixtures.CONTACT,
        )
        val store = FakeChatThreadStore(rows = listOf(failed))
        val (viewModel, _) = makeThreadViewModel(
            store = store,
            currentContact = { ChatFixtures.contact(name = "Bob", id = ChatFixtures.CONTACT) },
            sendQueue = { null },
        )
        viewModel.loadInitial()
        runCurrent()
        viewModel.retryMessage(failed.id, isResend = true)
        runCurrent()

        assertEquals(0, store.retriedPendingSends.size)
        assertEquals(MessageStatus.FAILED, viewModel.renderState.value.items.single().footer.status)
        assertEquals(
            ChatErrorMessages.developerEnglish().unableToSend,
            viewModel.renderState.value.sendErrorMessage,
        )
    }

    @Test
    fun `retry without a live send queue leaves the failed channel unparked`() = runTest {
        val failed = ChatFixtures.message(
            status = MessageStatus.FAILED,
            contactID = null,
            channelIndex = CHANNEL_INDEX,
        )
        val store = FakeChatThreadStore(rows = listOf(failed))
        val (viewModel, _) = makeThreadViewModel(
            store = store,
            conversationID = ChatConversationID.channel(ChatFixtures.RADIO, CHANNEL_INDEX),
            sendQueue = { null },
        )
        viewModel.loadInitial()
        runCurrent()
        viewModel.retryMessage(failed.id)
        runCurrent()

        assertEquals(0, store.retriedPendingSends.size)
        assertEquals(MessageStatus.FAILED, viewModel.renderState.value.items.single().footer.status)
        assertEquals(null, viewModel.renderState.value.sendErrorMessage)
    }

    @Test
    fun `a second retry while the first is in flight is ignored`() = runTest {
        val failed = ChatFixtures.message(
            status = MessageStatus.FAILED,
            contactID = ChatFixtures.CONTACT,
        )
        val contact = ChatFixtures.contact(name = "Bob", id = ChatFixtures.CONTACT)
        val messages = InMemoryMessageStore()
        messages.saveMessage(failed)
        val inner = DefaultSendQueueStore(InMemoryPendingSendStore(), messages) { id ->
            contact.takeIf { it.id == id }
        }
        val gate = CompletableDeferred<Unit>()
        val gated = GatedSendQueueStore(inner, gate = gate)
        val sendQueue = ChatSendQueueService(
            radioID = ChatFixtures.RADIO,
            collaborators = ChatSendQueueCollaborators(
                store = gated,
                messageService = testMessageService(
                    FakeChatSession(),
                    messages,
                    backgroundScope,
                    ids = SequentialIds(),
                ),
                channelResolver = { true },
                scope = backgroundScope,
                now = { TEST_NOW_SECONDS },
                newPendingSendId = PendingSendIds()::next,
            ),
        )
        val store = FakeChatThreadStore(rows = listOf(failed))
        val (viewModel, _) = makeThreadViewModel(
            store = store,
            currentContact = { contact },
            messageService = { sendQueue.messageService },
            sendQueue = { sendQueue },
        )
        viewModel.loadInitial()

        val first = async { viewModel.retryMessage(failed.id) }
        runCurrent()
        val second = async { viewModel.retryMessage(failed.id) }
        runCurrent()
        assertEquals(1, gated.replaceCalls)
        gate.complete(Unit)
        first.await()
        second.await()
        assertEquals(1, gated.replaceCalls)
    }

    @Test
    fun `retry persist fault keeps failed and reports error`() = runTest {
        val failed = ChatFixtures.message(
            status = MessageStatus.FAILED,
            contactID = ChatFixtures.CONTACT,
        )
        val contact = ChatFixtures.contact(name = "Bob", id = ChatFixtures.CONTACT)
        val messages = InMemoryMessageStore()
        messages.saveMessage(failed)
        val inner = DefaultSendQueueStore(InMemoryPendingSendStore(), messages) { id ->
            contact.takeIf { it.id == id }
        }
        val gated = GatedSendQueueStore(inner, replaceFailure = { IllegalStateException("room down") })
        val sendQueue = ChatSendQueueService(
            radioID = ChatFixtures.RADIO,
            collaborators = ChatSendQueueCollaborators(
                store = gated,
                messageService = testMessageService(
                    FakeChatSession(),
                    messages,
                    backgroundScope,
                    ids = SequentialIds(),
                ),
                channelResolver = { true },
                scope = backgroundScope,
                now = { TEST_NOW_SECONDS },
                newPendingSendId = PendingSendIds()::next,
            ),
        )
        val store = FakeChatThreadStore(rows = listOf(failed))
        val (viewModel, _) = makeThreadViewModel(
            store = store,
            currentContact = { contact },
            messageService = { sendQueue.messageService },
            sendQueue = { sendQueue },
        )
        viewModel.loadInitial()
        runCurrent()
        viewModel.retryMessage(failed.id)
        runCurrent()

        assertEquals(1, gated.replaceCalls)
        assertEquals(MessageStatus.FAILED, viewModel.renderState.value.items.single().footer.status)
        assertEquals(
            ChatErrorMessages.developerEnglish().sendQueuePersistFailed,
            viewModel.renderState.value.sendErrorMessage,
        )
    }

    /**
     * Stands in for a live [ChatSendQueueService] store so retry can be gated or failed at
     * [SendQueueStore.replacePendingSendForRetry] — the persist [ChatThreadViewModel] awaits under
     * `retryInFlight`.
     */
    private class GatedSendQueueStore(
        private val inner: SendQueueStore,
        val gate: CompletableDeferred<Unit>? = null,
        val replaceFailure: (() -> Throwable)? = null,
    ) : SendQueueStore by inner {
        var replaceCalls = 0
            private set

        override suspend fun replacePendingSendForRetry(messageID: Uuid, dto: PendingSendDto): Int {
            replaceCalls += 1
            replaceFailure?.let { throw it() }
            gate?.await()
            return inner.replacePendingSendForRetry(messageID, dto)
        }
    }
}
