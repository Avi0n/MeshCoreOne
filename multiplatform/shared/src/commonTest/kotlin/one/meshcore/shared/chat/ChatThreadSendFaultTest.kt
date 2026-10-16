package one.meshcore.shared.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Send and delete faults on an open thread. An ordinary failure still reports the shipped sentence.
 * Cancellation and an error propagate and do not record that failure.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatThreadSendFaultTest {
    @Test
    fun aDeleteExceptionKeepsTheRowAndReportsTheShippedSentence() = runTest {
        val messages = threadRows()
        val store = FakeChatThreadStore(rows = messages).apply {
            deleteFailure = IllegalStateException("db locked")
        }
        var notifications = 0
        val (viewModel, _) = makeThreadViewModel(
            store = store,
            onConversationsChanged = { notifications += 1 },
        )
        viewModel.loadInitial()
        runCurrent()
        notifications = 0

        viewModel.deleteMessage(messages[1].id)
        runCurrent()

        assertEquals(
            ChatErrorMessages.developerEnglish().deleteMessageFailed,
            viewModel.renderState.value.errorMessage,
        )
        assertEquals(messages.map { it.id }, viewModel.renderState.value.items.map { it.id })
        assertTrue(store.deletedIds.isEmpty())
        assertEquals(0, notifications)
    }

    @Test
    fun aCancelledDeletePropagatesAndKeepsTheRow() = runTest {
        val messages = threadRows()
        val stopped = CancellationException("superseded")
        val store = FakeChatThreadStore(rows = messages).apply {
            deleteFailure = stopped
        }
        val (viewModel, _) = makeThreadViewModel(store = store)
        viewModel.loadInitial()
        runCurrent()

        val cancelled = assertFailsWith<CancellationException> {
            viewModel.deleteMessage(messages[1].id)
        }

        assertSame(stopped, cancelled)
        assertNull(viewModel.renderState.value.errorMessage)
        assertEquals(messages.map { it.id }, viewModel.renderState.value.items.map { it.id })
        assertTrue(store.deletedIds.isEmpty())
    }

    @Test
    fun aDeleteErrorPropagatesAndKeepsTheRow() = runTest {
        val messages = threadRows()
        val probe = ChatThreadSendProbeError()
        val store = FakeChatThreadStore(rows = messages).apply {
            deleteFailure = probe
        }
        val (viewModel, _) = makeThreadViewModel(store = store)
        viewModel.loadInitial()
        runCurrent()

        val thrown = assertFailsWith<ChatThreadSendProbeError> {
            viewModel.deleteMessage(messages[1].id)
        }
        assertSame(probe, thrown)

        assertNull(viewModel.renderState.value.errorMessage)
        assertEquals(messages.map { it.id }, viewModel.renderState.value.items.map { it.id })
        assertTrue(store.deletedIds.isEmpty())
    }

    @Test
    fun aPendingCreateExceptionReportsSendFailedAndShowsNoBubble() = runTest {
        val (viewModel, threadStore) = dmSender(saveFailure = IllegalStateException("db locked"))
        viewModel.loadInitial()
        runCurrent()

        viewModel.sendMessage("hi")
        runCurrent()

        assertEquals(ChatErrorMessages.developerEnglish().sendFailed, viewModel.renderState.value.sendErrorMessage)
        assertTrue(viewModel.renderState.value.items.isEmpty())
        assertTrue(threadStore.failedIds.isEmpty())
    }

    @Test
    fun aCancelledPendingCreatePropagatesAndShowsNoBubble() = runTest {
        val stopped = CancellationException("superseded")
        val (viewModel, _) = dmSender(saveFailure = stopped)
        viewModel.loadInitial()
        runCurrent()

        val cancelled = assertFailsWith<CancellationException> { viewModel.sendMessage("hi") }

        assertSame(stopped, cancelled)
        assertNull(viewModel.renderState.value.sendErrorMessage)
        assertTrue(viewModel.renderState.value.items.isEmpty())
    }

    @Test
    fun aPendingCreateErrorPropagatesAndShowsNoBubble() = runTest {
        val probe = ChatThreadSendProbeError()
        val (viewModel, _) = dmSender(saveFailure = probe)
        viewModel.loadInitial()
        runCurrent()

        val thrown = assertFailsWith<ChatThreadSendProbeError> { viewModel.sendMessage("hi") }
        assertSame(probe, thrown)

        assertNull(viewModel.renderState.value.sendErrorMessage)
        assertTrue(viewModel.renderState.value.items.isEmpty())
    }

    @Test
    fun aCancelledEnqueuePropagatesAndLeavesTheBubblePending() = runTest {
        val stopped = CancellationException("superseded")
        val (viewModel, threadStore) = dmSender(newPendingSendId = { throw stopped })
        viewModel.loadInitial()
        runCurrent()
        threadStore.markFailedSendsSeenCalls = 0

        val cancelled = assertFailsWith<CancellationException> { viewModel.sendMessage("hi") }

        assertSame(stopped, cancelled)
        assertNull(viewModel.renderState.value.sendErrorMessage)
        assertEquals(MessageStatus.PENDING, viewModel.renderState.value.items.single().footer.status)
        assertTrue(threadStore.failedIds.isEmpty())
        assertEquals(0, threadStore.markFailedSendsSeenCalls)
    }

    @Test
    fun anEnqueueErrorPropagatesAndLeavesTheBubblePending() = runTest {
        val probe = ChatThreadSendProbeError()
        val (viewModel, threadStore) = dmSender(newPendingSendId = { throw probe })
        viewModel.loadInitial()
        runCurrent()
        threadStore.markFailedSendsSeenCalls = 0

        val thrown = assertFailsWith<ChatThreadSendProbeError> { viewModel.sendMessage("hi") }
        assertSame(probe, thrown)

        assertNull(viewModel.renderState.value.sendErrorMessage)
        assertEquals(MessageStatus.PENDING, viewModel.renderState.value.items.single().footer.status)
        assertTrue(threadStore.failedIds.isEmpty())
        assertEquals(0, threadStore.markFailedSendsSeenCalls)
    }
}

private class ChatThreadSendProbeError : Error("probe")

private fun threadRows(): List<MessageDto> = listOf(
    ChatFixtures.message(text = "one"),
    ChatFixtures.message(text = "two"),
    ChatFixtures.message(text = "three"),
)

@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.dmSender(
    saveFailure: Throwable? = null,
    newPendingSendId: (() -> Uuid)? = null,
): Pair<ChatThreadViewModel, FakeChatThreadStore> {
    val queueStore = FakeSendQueueStore()
    val messageStore = if (saveFailure == null) {
        queueStore.messages
    } else {
        SaveFailingMessageStore(queueStore.messages, saveFailure)
    }
    val ids = SequentialIds()
    val messageService = testMessageService(FakeChatSession(), messageStore, backgroundScope, ids = ids)
    val sendQueue = ChatSendQueueService(
        radioID = ChatFixtures.RADIO,
        collaborators = ChatSendQueueCollaborators(
            store = queueStore,
            messageService = messageService,
            channelResolver = { true },
            scope = backgroundScope,
            now = { TEST_NOW_SECONDS },
            newPendingSendId = newPendingSendId ?: ids::next,
        ),
    )
    val threadStore = FakeChatThreadStore().apply {
        extraFetch = { id -> messageStore.fetchMessage(id) }
    }
    val contact = ChatFixtures.contact(name = "Bob", id = ChatFixtures.CONTACT)
    val (viewModel, _) = makeThreadViewModel(
        store = threadStore,
        currentContact = { contact },
        messageService = { messageService },
        sendQueue = { sendQueue },
    )
    return viewModel to threadStore
}

private class SaveFailingMessageStore(private val inner: InMemoryMessageStore, private val failure: Throwable) :
    MessageStore by inner {
    override suspend fun saveMessage(message: MessageDto): Unit = throw failure
}
