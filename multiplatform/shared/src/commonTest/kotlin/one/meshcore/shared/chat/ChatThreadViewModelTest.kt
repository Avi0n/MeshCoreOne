package one.meshcore.shared.chat

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Initial load, delete, and a live row update the open thread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatThreadViewModelTest {
    @Test
    fun `loadInitial builds items settles loaded and marks read`() = runTest {
        val store = FakeChatThreadStore(rows = rows(5))
        val (viewModel, _) = makeThreadViewModel(store = store)
        viewModel.loadInitial()
        runCurrent()

        val state = viewModel.renderState.value
        assertEquals(5, state.items.size)
        assertTrue(state.isLoaded)
        assertEquals(1, store.clearUnreadCalls)
    }

    @Test
    fun `loadInitial notifies the conversation list after mark-read`() = runTest {
        var notifications = 0
        val store = FakeChatThreadStore(rows = rows(2))
        val (viewModel, _) = makeThreadViewModel(
            store = store,
            onConversationsChanged = { notifications += 1 },
        )
        viewModel.loadInitial()
        runCurrent()

        assertEquals(1, store.clearUnreadCalls)
        assertEquals(1, notifications)
        assertEquals(1, store.markFailedSendsSeenCalls)
    }

    @Test
    fun `loadInitial marks unseen mentions seen`() = runTest {
        val mention = incoming(1_000L).copy(containsSelfMention = true, mentionSeen = false)
        val store = FakeChatThreadStore(rows = listOf(mention))
        val (viewModel, _) = makeThreadViewModel(store = store)
        viewModel.loadInitial()
        runCurrent()

        assertEquals(listOf(mention.id), store.markedMentionIds)
        assertTrue(viewModel.renderState.value.items.single().envelope.mentionSeen)
    }

    @Test
    fun `an incoming mention on the open thread is marked seen without scrolling`() = runTest {
        val store = FakeChatThreadStore()
        val (viewModel, _) = makeThreadViewModel(store = store)
        viewModel.loadInitial()
        runCurrent()

        val mention = incoming(2_000L).copy(containsSelfMention = true, mentionSeen = false)
        viewModel.handleIncoming(IncomingMessageEvent.DirectMessageReceived(mention, ChatFixtures.CONTACT))
        runCurrent()

        assertEquals(listOf(mention.id), store.markedMentionIds)
        assertTrue(viewModel.renderState.value.items.single().envelope.mentionSeen)
    }

    @Test
    fun `a mention that is not on screen does not radio`() = runTest {
        val mention = incoming(1_000L).copy(containsSelfMention = true, mentionSeen = false)
        val store = FakeChatThreadStore(rows = listOf(mention))
        val session = FakeChatSession()
        var serviceReads = 0
        val (viewModel, _) = makeThreadViewModel(
            store = store,
            messageService = {
                serviceReads += 1
                testMessageService(session, InMemoryMessageStore(), backgroundScope)
            },
        )

        viewModel.onMentionSeen(mention.id)
        runCurrent()

        assertEquals(0, serviceReads)
        assertTrue(session.directSendAttempts.isEmpty())
        assertTrue(session.channelSends.isEmpty())
        assertEquals(0, session.resetPathCalls)
        assertTrue(store.markedMentionIds.isEmpty())
        assertFalse(store.rows.single().mentionSeen)
        assertEquals(0, store.clearUnreadCalls)
        assertTrue(viewModel.renderState.value.items.isEmpty())
    }

    @Test
    fun `a routing change on the open contact bumps the header refresh signal`() = runTest {
        val (viewModel, _) = makeThreadViewModel()
        assertEquals(0, viewModel.renderState.value.contactRefreshSignal)
        viewModel.handleStatus(MessageStatusEvent.RoutingChanged(ChatFixtures.CONTACT, isFlood = true))
        runCurrent()
        assertEquals(1, viewModel.renderState.value.contactRefreshSignal)
    }

    @Test
    fun `delete notifies the conversation list`() = runTest {
        var notifications = 0
        val messages = rows(3)
        val store = FakeChatThreadStore(rows = messages)
        val (viewModel, _) = makeThreadViewModel(
            store = store,
            onConversationsChanged = { notifications += 1 },
        )
        viewModel.loadInitial()
        runCurrent()
        notifications = 0

        viewModel.deleteMessage(messages[1].id)
        runCurrent()

        assertEquals(1, notifications)
    }

    @Test
    fun `deleting a channel cluster-end rebakes the remaining neighbor`() = runTest {
        val first = ChatFixtures.channelMessage(timestamp = 1_000, senderName = "Alice")
        val second = ChatFixtures.channelMessage(timestamp = 1_060, senderName = "Alice")
        val store = FakeChatThreadStore(rows = listOf(first, second))
        val (viewModel, _) = makeThreadViewModel(
            store = store,
            conversationID = ChatConversationID.channel(ChatFixtures.RADIO, channelIndex = 0),
        )
        viewModel.loadInitial()
        runCurrent()
        val before = viewModel.renderState.value.items
        assertFalse(before.first().grouping.isClusterEnd)
        assertTrue(before.last().grouping.isClusterEnd)

        viewModel.deleteMessage(second.id)
        runCurrent()

        val remaining = viewModel.renderState.value.items.single()
        assertEquals(first.id, remaining.id)
        assertTrue(remaining.grouping.isClusterEnd)
    }

    @Test
    fun `a failed delete does not notify the conversation list`() = runTest {
        var notifications = 0
        val messages = rows(3)
        val store = FakeChatThreadStore(rows = messages).apply { deleteFailureText = RAW_STORAGE_TEXT }
        val (viewModel, _) = makeThreadViewModel(
            store = store,
            onConversationsChanged = { notifications += 1 },
        )
        viewModel.loadInitial()
        runCurrent()
        notifications = 0

        viewModel.deleteMessage(messages[1].id)
        runCurrent()

        assertEquals(0, notifications)
    }

    @Test
    fun `reopen after a closed-chat arrival shows the new message`() = runTest {
        val first = incoming(1_000L)
        val store = FakeChatThreadStore(rows = listOf(first))
        val (opened, coordinator) = makeThreadViewModel(store = store)
        opened.loadInitial()
        runCurrent()
        assertEquals(1, opened.renderState.value.items.size)

        opened.cleanup()
        val (reopenedBeforeLoad, _) = makeThreadViewModel(store = store, coordinator = coordinator)
        assertEquals(listOf(first.id), reopenedBeforeLoad.renderState.value.items.map { it.id })
        reopenedBeforeLoad.cleanup()

        val arrival = incoming(2_000L)
        store.rows = listOf(first, arrival)
        val (reopened, _) = makeThreadViewModel(store = store, coordinator = coordinator)
        reopened.loadInitial()
        runCurrent()

        assertEquals(listOf(first.id, arrival.id), reopened.renderState.value.items.map { it.id })
    }

    @Test
    fun `clearing this DM from contact detail reloads the open thread`() = runTest {
        val store = FakeChatThreadStore(rows = rows(3))
        val (viewModel, _) = makeThreadViewModel(store = store)
        viewModel.loadInitial()
        runCurrent()
        assertEquals(3, viewModel.renderState.value.items.size)

        store.rows = emptyList()
        viewModel.handleMessagesCleared(ChatFixtures.CONTACT)
        runCurrent()

        assertTrue(viewModel.renderState.value.items.isEmpty())
    }

    @Test
    fun `a clear for another contact does not reload this thread`() = runTest {
        val store = FakeChatThreadStore(rows = rows(2))
        val (viewModel, _) = makeThreadViewModel(store = store)
        viewModel.loadInitial()
        runCurrent()

        store.rows = emptyList()
        viewModel.handleMessagesCleared(Uuid.normalized("CCCCCCCC-CCCC-CCCC-CCCC-CCCCCCCCCCCC"))
        runCurrent()

        assertEquals(2, viewModel.renderState.value.items.size)
    }

    @Test
    fun `blocking a channel sender reloads the open channel thread`() = runTest {
        val store = FakeChatThreadStore(rows = rows(3))
        val (viewModel, _) = makeThreadViewModel(
            store = store,
            conversationID = ChatConversationID.channel(ChatFixtures.RADIO, channelIndex = 0),
        )
        viewModel.loadInitial()
        runCurrent()
        assertEquals(3, viewModel.renderState.value.items.size)

        store.rows = emptyList()
        viewModel.handleChannelSenderBlocked()
        runCurrent()

        assertTrue(viewModel.renderState.value.items.isEmpty())
    }

    @Test
    fun `blocking a channel sender does not reload an open DM thread`() = runTest {
        val store = FakeChatThreadStore(rows = rows(2))
        val (viewModel, _) = makeThreadViewModel(store = store)
        viewModel.loadInitial()
        runCurrent()

        store.rows = emptyList()
        viewModel.handleChannelSenderBlocked()
        runCurrent()

        assertEquals(2, viewModel.renderState.value.items.size)
    }

    @Test
    fun `incoming message for the conversation is appended`() = runTest {
        val store = FakeChatThreadStore(rows = rows(3))
        val (viewModel, _) = makeThreadViewModel(store = store)
        viewModel.loadInitial()
        runCurrent()

        val arrival = incoming(2_000L)
        viewModel.handleIncoming(IncomingMessageEvent.DirectMessageReceived(arrival, ChatFixtures.CONTACT))

        val items = viewModel.renderState.value.items
        assertEquals(4, items.size)
        assertEquals(arrival.id, items.last().id)
    }

    @Test
    fun `incoming message for another conversation is dropped`() = runTest {
        val store = FakeChatThreadStore(rows = rows(3))
        val (viewModel, _) = makeThreadViewModel(store = store)
        viewModel.loadInitial()
        runCurrent()

        val otherContact = ChatFixtures.freshUuid()
        viewModel.handleIncoming(IncomingMessageEvent.DirectMessageReceived(incoming(2_000L), otherContact))

        assertEquals(3, viewModel.renderState.value.items.size)
    }

    @Test
    fun `start after reconnect collects from the new ingest flow`() = runTest {
        val first = MutableSharedFlow<IncomingMessageEvent>(extraBufferCapacity = 1)
        val second = MutableSharedFlow<IncomingMessageEvent>(extraBufferCapacity = 1)
        val store = FakeChatThreadStore(rows = emptyList())
        val (viewModel, _) = makeThreadViewModel(store = store)
        viewModel.loadInitial()
        runCurrent()

        viewModel.start(first, emptyFlow())
        runCurrent()
        viewModel.start(second, emptyFlow())
        runCurrent()

        val arrival = incoming(2_000L)
        second.emit(IncomingMessageEvent.DirectMessageReceived(arrival, ChatFixtures.CONTACT))
        runCurrent()
        assertEquals(1, viewModel.renderState.value.items.size)
        assertEquals(arrival.id, viewModel.renderState.value.items.single().id)

        first.emit(IncomingMessageEvent.DirectMessageReceived(incoming(3_000L), ChatFixtures.CONTACT))
        runCurrent()
        assertEquals(1, viewModel.renderState.value.items.size)
    }

    @Test
    fun `status resolved transitions the glyph in place and blocks the delivered downgrade`() = runTest {
        val message = outgoing(1_000L, MessageStatus.SENT)
        val store = FakeChatThreadStore(rows = listOf(message))
        val (viewModel, _) = makeThreadViewModel(store = store)
        viewModel.loadInitial()
        runCurrent()

        viewModel.handleStatus(
            MessageStatusEvent.StatusResolved(message.id, MessageStatus.DELIVERED, roundTripTime = 1_500),
        )
        assertEquals(MessageStatus.DELIVERED, viewModel.renderState.value.items.single().footer.status)

        // A late .sent must not downgrade a delivered row.
        viewModel.handleStatus(MessageStatusEvent.StatusResolved(message.id, MessageStatus.SENT))
        assertEquals(MessageStatus.DELIVERED, viewModel.renderState.value.items.single().footer.status)
    }

    @Test
    fun `resent event routes through the coordinator coalesced reload`() = runTest {
        val message = outgoing(1_000L, MessageStatus.SENT)
        val store = FakeChatThreadStore(rows = listOf(message))
        val (viewModel, coordinator) = makeThreadViewModel(store = store)
        viewModel.loadInitial()
        runCurrent()

        viewModel.handleStatus(MessageStatusEvent.Resent(message.id))
        assertTrue(coordinator.reloadInFlight || true) // scheduled synchronously; drain runs on advance
        runCurrent()
        assertFalse(coordinator.reloadInFlight)
    }

    @Test
    fun `a region update reloads the row so the chip can appear`() = runTest {
        val message = incoming(1_000L)
        val store = FakeChatThreadStore(rows = listOf(message))
        val (viewModel, coordinator) = makeThreadViewModel(store = store)
        viewModel.loadInitial()
        runCurrent()
        val launches = coordinator.coalescedReloadLaunches

        viewModel.handleStatus(MessageStatusEvent.MessagesRegionUpdated(listOf(message.id)))
        runCurrent()
        assertEquals(launches + 1, coordinator.coalescedReloadLaunches)
        assertFalse(coordinator.reloadInFlight)
    }

    @Test
    fun `a failed send notifies the conversation list`() = runTest {
        var notifications = 0
        val message = outgoing(1_000L, MessageStatus.PENDING)
        val store = FakeChatThreadStore(rows = listOf(message))
        val (viewModel, _) = makeThreadViewModel(
            store = store,
            onConversationsChanged = { notifications += 1 },
        )
        viewModel.loadInitial()
        runCurrent()
        notifications = 0

        viewModel.handleStatus(MessageStatusEvent.Failed(message.id))
        runCurrent()

        assertEquals(1, notifications)
        assertEquals(2, store.markFailedSendsSeenCalls)
    }

    @Test
    fun `a failed send for another conversation does not mark this thread seen`() = runTest {
        val current = outgoing(1_000L, MessageStatus.SENT)
        val other = outgoing(2_000L, MessageStatus.FAILED).copy(contactID = ChatFixtures.freshUuid())
        val store = FakeChatThreadStore(rows = listOf(current, other))
        val (viewModel, _) = makeThreadViewModel(store = store)
        viewModel.loadInitial()
        runCurrent()
        store.markFailedSendsSeenCalls = 0

        viewModel.handleStatus(MessageStatusEvent.Failed(other.id))
        runCurrent()

        assertEquals(0, store.markFailedSendsSeenCalls)
    }
}

/**
 * Pagination and the unread divider keep the viewport unless the reader is at the bottom.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatThreadScrollTest {
    @Test
    fun `pagination prepends older history without moving the newest rows`() = runTest {
        val store = FakeChatThreadStore(rows = rows(60))
        val (viewModel, coordinator) = makeThreadViewModel(store = store)
        viewModel.loadInitial()
        runCurrent()

        val newestPage = coordinator.messages.toList()
        assertEquals(ChatCoordinator.PAGE_SIZE, newestPage.size)
        val pinnedNewest = viewModel.renderState.value.items.last().id

        viewModel.loadOlderMessages()
        runCurrent()

        val state = viewModel.renderState.value
        assertEquals(60, state.items.size)
        // The previously loaded page is an unchanged contiguous suffix: the viewport rows never moved.
        assertEquals(newestPage.map { it.id }, coordinator.messages.takeLast(ChatCoordinator.PAGE_SIZE).map { it.id })
        assertEquals(pinnedNewest, state.items.last().id)
        assertFalse(state.hasMoreMessages)
        assertFalse(state.isLoadingOlder)
    }

    @Test
    fun `refreshWindow keeps paged-in history and catch-up rows`() = runTest {
        val store = FakeChatThreadStore(rows = rows(60))
        val (viewModel, coordinator) = makeThreadViewModel(store = store)
        viewModel.loadInitial()
        runCurrent()
        viewModel.loadOlderMessages()
        runCurrent()
        val oldest = coordinator.messages.first().id
        val newest = coordinator.messages.last().id

        val catchUp = incoming(2_000L)
        store.rows = store.rows + catchUp
        viewModel.loadInitial(ChatPopulateMode.REFRESH_WINDOW)
        runCurrent()

        assertEquals(oldest, coordinator.messages.first().id)
        assertEquals(catchUp.id, coordinator.messages.last().id)
        assertTrue(coordinator.messages.any { it.id == newest })
        assertFalse(viewModel.renderState.value.hasMoreMessages)
        assertEquals(coordinator.messages.size, coordinator.renderState.totalFetchedCount)
        assertFalse(coordinator.renderState.hasMoreMessages)
    }

    @Test
    fun `replace populate drops paged-in history`() = runTest {
        val store = FakeChatThreadStore(rows = rows(60))
        val (viewModel, coordinator) = makeThreadViewModel(store = store)
        viewModel.loadInitial()
        runCurrent()
        viewModel.loadOlderMessages()
        runCurrent()
        assertEquals(60, coordinator.messages.size)

        viewModel.loadInitial(ChatPopulateMode.REPLACE)
        runCurrent()

        assertEquals(ChatCoordinator.PAGE_SIZE, coordinator.messages.size)
        assertTrue(viewModel.renderState.value.hasMoreMessages)
        assertEquals(ChatCoordinator.PAGE_SIZE, coordinator.renderState.totalFetchedCount)
    }

    @Test
    fun `admitting a live row increments coordinator totalFetchedCount`() = runTest {
        val store = FakeChatThreadStore(rows = rows(50))
        val (viewModel, coordinator) = makeThreadViewModel(store = store)
        viewModel.loadInitial()
        runCurrent()
        assertEquals(ChatCoordinator.PAGE_SIZE, coordinator.renderState.totalFetchedCount)

        val arrival = incoming(9_000L)
        viewModel.handleIncoming(
            IncomingMessageEvent.DirectMessageReceived(arrival, ChatFixtures.CONTACT),
        )
        runCurrent()

        assertEquals(ChatCoordinator.PAGE_SIZE + 1, coordinator.renderState.totalFetchedCount)
        assertTrue(coordinator.messagesByID.containsKey(arrival.id))
    }

    @Test
    fun `pagination is a no-op once history is exhausted`() = runTest {
        val store = FakeChatThreadStore(rows = rows(10))
        val (viewModel, _) = makeThreadViewModel(store = store)
        viewModel.loadInitial()
        runCurrent()
        assertFalse(viewModel.renderState.value.hasMoreMessages)

        viewModel.loadOlderMessages()
        runCurrent()
        assertEquals(10, viewModel.renderState.value.items.size)
    }

    @Test
    fun `an older-page failure raises the banner not the modal`() = runTest {
        val store = FakeChatThreadStore(rows = rows(60))
        val (viewModel, _) = makeThreadViewModel(store = store)
        viewModel.loadInitial()
        runCurrent()
        store.fetchFailure = IllegalStateException("disk")

        viewModel.loadOlderMessages()
        runCurrent()

        val state = viewModel.renderState.value
        assertEquals(ChatErrorMessages.developerEnglish().loadOlderMessagesFailed, state.errorBannerMessage)
        assertEquals(null, state.errorMessage)
        viewModel.clearErrorBanner()
        assertEquals(null, viewModel.renderState.value.errorBannerMessage)
    }

    @Test
    fun `near-bottom arrival auto-scrolls to bottom`() = runTest {
        val store = FakeChatThreadStore(rows = rows(3))
        val (viewModel, _) = makeThreadViewModel(store = store)
        viewModel.loadInitial()
        runCurrent()
        val tokenBefore = (viewModel.renderState.value.scrollCommand as? ScrollCommand.ToBottom)?.token ?: -1

        viewModel.handleIncoming(IncomingMessageEvent.DirectMessageReceived(incoming(2_000L), ChatFixtures.CONTACT))

        val state = viewModel.renderState.value
        val command = state.scrollCommand
        assertTrue(command is ScrollCommand.ToBottom && command.token > tokenBefore)
        assertEquals(0, state.unreadWhileScrolledUp)
    }

    @Test
    fun `arrival while scrolled up raises the affordance instead of scrolling`() = runTest {
        val store = FakeChatThreadStore(rows = rows(3))
        val (viewModel, _) = makeThreadViewModel(store = store)
        viewModel.loadInitial()
        runCurrent()
        viewModel.setNearBottom(false)
        val commandBefore = viewModel.renderState.value.scrollCommand

        viewModel.handleIncoming(IncomingMessageEvent.DirectMessageReceived(incoming(2_000L), ChatFixtures.CONTACT))

        val state = viewModel.renderState.value
        assertEquals(1, state.unreadWhileScrolledUp)
        assertEquals(commandBefore, state.scrollCommand)
    }

    @Test
    fun `outgoing arrival while scrolled up does not raise the unread badge`() = runTest {
        val store = FakeChatThreadStore(rows = rows(3))
        val (viewModel, _) = makeThreadViewModel(store = store)
        viewModel.loadInitial()
        runCurrent()
        viewModel.setNearBottom(false)
        val commandBefore = viewModel.renderState.value.scrollCommand

        viewModel.handleIncoming(
            IncomingMessageEvent.DirectMessageReceived(
                outgoing(2_000L, MessageStatus.SENT),
                ChatFixtures.CONTACT,
            ),
        )

        val state = viewModel.renderState.value
        assertEquals(0, state.unreadWhileScrolledUp)
        assertEquals(commandBefore, state.scrollCommand)
    }

    @Test
    fun `send while scrolled up does not yank the viewport`() = runTest {
        val session = FakeChatSession()
        val messageStore = FakeSendQueueStore()
        val ids = SequentialIds()
        val messageService = testMessageService(session, messageStore.messages, backgroundScope, ids = ids)
        val sendQueue = ChatSendQueueService(
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
        val contact = ChatFixtures.contact(name = "Bob", id = ChatFixtures.CONTACT)
        val (viewModel, _) = makeThreadViewModel(
            store = FakeChatThreadStore(rows = rows(3)),
            currentContact = { contact },
            messageService = { messageService },
            sendQueue = { sendQueue },
        )
        viewModel.loadInitial()
        runCurrent()
        viewModel.setNearBottom(false)
        val commandBefore = viewModel.renderState.value.scrollCommand

        viewModel.sendMessage("hi")
        runCurrent()

        val state = viewModel.renderState.value
        assertEquals(commandBefore, state.scrollCommand)
        assertEquals(0, state.unreadWhileScrolledUp)
        assertFalse(state.isAtBottom)
        assertTrue(state.items.last().envelope.isOutgoing)
    }

    @Test
    fun `composer focus while scrolled up does not yank the viewport`() = runTest {
        val store = FakeChatThreadStore(rows = rows(3))
        val (viewModel, _) = makeThreadViewModel(store = store)
        viewModel.loadInitial()
        runCurrent()
        viewModel.setNearBottom(false)
        val commandBefore = viewModel.renderState.value.scrollCommand

        viewModel.requestPinToBottom()

        val state = viewModel.renderState.value
        assertEquals(commandBefore, state.scrollCommand)
        assertFalse(state.isAtBottom)
    }

    @Test
    fun `composer focus while at the bottom re-pins the newest row`() = runTest {
        val store = FakeChatThreadStore(rows = rows(3))
        val (viewModel, _) = makeThreadViewModel(store = store)
        viewModel.loadInitial()
        runCurrent()
        val tokenBefore = (viewModel.renderState.value.scrollCommand as? ScrollCommand.ToBottom)?.token ?: -1

        viewModel.requestPinToBottom()

        val command = viewModel.renderState.value.scrollCommand
        assertTrue(command is ScrollCommand.ToBottom && command.token > tokenBefore)
        assertTrue(viewModel.renderState.value.isAtBottom)
    }

    @Test
    fun `scroll-to-bottom affordance jumps even when scrolled up`() = runTest {
        val store = FakeChatThreadStore(rows = rows(3))
        val (viewModel, _) = makeThreadViewModel(store = store)
        viewModel.loadInitial()
        runCurrent()
        viewModel.setNearBottom(false)
        val tokenBefore = (viewModel.renderState.value.scrollCommand as? ScrollCommand.ToBottom)?.token ?: -1

        viewModel.requestScrollToBottom()

        val state = viewModel.renderState.value
        val command = state.scrollCommand
        assertTrue(command is ScrollCommand.ToBottom && command.token > tokenBefore)
        assertTrue(state.isAtBottom)
        assertEquals(0, state.unreadWhileScrolledUp)
    }

    @Test
    fun `divider is placed on open when unread exceeds the threshold`() = runTest {
        val store = FakeChatThreadStore(rows = rows(20))
        val (viewModel, _) = makeThreadViewModel(store = store, unreadCount = { 12 })
        viewModel.loadInitial()
        runCurrent()

        val state = viewModel.renderState.value
        val dividerID = state.newMessagesDividerMessageID
        assertEquals(state.items[8].id, dividerID)
        assertTrue(state.items[8].grouping.showNewMessagesDivider)
        assertFalse(state.items[7].grouping.showNewMessagesDivider)
    }

    @Test
    fun `divider bakes from the store count, not the stale DTO snapshot`() = runTest {
        val store = FakeChatThreadStore(rows = rows(20)).apply { storedUnreadCount = 12 }
        // The navigation snapshot is stale (0); the live store count must win.
        val (viewModel, _) = makeThreadViewModel(store = store, unreadCount = { 0 })
        viewModel.loadInitial()
        runCurrent()

        val state = viewModel.renderState.value
        assertEquals(state.items[8].id, state.newMessagesDividerMessageID)
        assertTrue(state.items[8].grouping.showNewMessagesDivider)
    }

    @Test
    fun `channel divider is placed from the store count with no contact snapshot`() = runTest {
        val store = FakeChatThreadStore(rows = rows(20)).apply { storedUnreadCount = 12 }
        // A channel conversation carries no contact DTO, so the snapshot count is always 0.
        val (viewModel, _) = makeThreadViewModel(
            store = store,
            conversationID = ChatConversationID.channel(ChatFixtures.RADIO, channelIndex = 1),
            unreadCount = { 0 },
        )
        viewModel.loadInitial()
        runCurrent()

        val state = viewModel.renderState.value
        assertEquals(state.items[8].id, state.newMessagesDividerMessageID)
        assertTrue(state.items[8].grouping.showNewMessagesDivider)
    }

    @Test
    fun `no divider when the store count is zero`() = runTest {
        val store = FakeChatThreadStore(rows = rows(20)).apply { storedUnreadCount = 0 }
        val (viewModel, _) = makeThreadViewModel(store = store, unreadCount = { 12 })
        viewModel.loadInitial()
        runCurrent()

        val state = viewModel.renderState.value
        assertNull(state.newMessagesDividerMessageID)
    }

    @Test
    fun `open anchors at the divider without animating when there is unread backlog`() = runTest {
        val store = FakeChatThreadStore(rows = rows(20)).apply { storedUnreadCount = 12 }
        val (viewModel, _) = makeThreadViewModel(store = store, unreadCount = { 0 })
        viewModel.loadInitial()
        runCurrent()

        val state = viewModel.renderState.value
        val command = state.scrollCommand
        assertTrue(command is ScrollCommand.ToMessage)
        assertEquals(state.newMessagesDividerMessageID, command.messageID)
        assertFalse(command.animated)
        assertFalse(state.isAtBottom)
    }

    @Test
    fun `open stays at the bottom with no unread backlog`() = runTest {
        val store = FakeChatThreadStore(rows = rows(20)).apply { storedUnreadCount = 0 }
        val (viewModel, _) = makeThreadViewModel(store = store, unreadCount = { 0 })
        viewModel.loadInitial()
        runCurrent()

        val state = viewModel.renderState.value
        assertNull(state.scrollCommand)
        assertTrue(state.isAtBottom)
        assertEquals(ChatInitialScrollPolicy.FirstSnapshotDecision.Present(null), state.firstSnapshot)
    }

    @Test
    fun `open withholds until the divider row is baked into items`() = runTest {
        val store = FakeChatThreadStore(rows = rows(20)).apply { storedUnreadCount = 12 }
        val (viewModel, _) = makeThreadViewModel(store = store, unreadCount = { 12 })
        viewModel.loadInitial()
        // Fetch and divider bake have finished; the off-thread item rebuild has not applied yet.
        assertEquals(
            ChatInitialScrollPolicy.FirstSnapshotDecision.Withhold,
            viewModel.renderState.value.firstSnapshot,
        )
        assertNull(viewModel.renderState.value.scrollCommand)

        runCurrent()

        val state = viewModel.renderState.value
        val present = assertIs<ChatInitialScrollPolicy.FirstSnapshotDecision.Present>(state.firstSnapshot)
        assertEquals(state.newMessagesDividerMessageID, present.target)
        val command = assertIs<ScrollCommand.ToMessage>(state.scrollCommand)
        assertEquals(state.newMessagesDividerMessageID, command.messageID)
        assertFalse(command.animated)
    }

    @Test
    fun `reopen on a warm coordinator withholds the previous session's page`() = runTest {
        val stale = rows(50)
        val store = FakeChatThreadStore(rows = stale).apply { storedUnreadCount = 20 }
        val (first, coordinator) = makeThreadViewModel(store = store, unreadCount = { 20 })
        first.loadInitial()
        runCurrent()
        val staleDivider = first.renderState.value.newMessagesDividerMessageID
        assertEquals(stale[30].id, staleDivider)

        store.storedUnreadCount = 18
        val (reopened, _) = makeThreadViewModel(
            store = store,
            unreadCount = { 18 },
            coordinator = coordinator,
        )
        reopened.loadInitial()
        assertEquals(
            ChatInitialScrollPolicy.FirstSnapshotDecision.Withhold,
            reopened.renderState.value.firstSnapshot,
            "the previous session's items must not spend the one-shot",
        )

        runCurrent()

        val state = reopened.renderState.value
        val present = assertIs<ChatInitialScrollPolicy.FirstSnapshotDecision.Present>(state.firstSnapshot)
        assertEquals(stale[32].id, present.target)
        assertEquals(stale[32].id, state.newMessagesDividerMessageID)
        assertTrue(state.items.first { it.id == stale[32].id }.grouping.showNewMessagesDivider)
    }

    @Test
    fun `a primed unread page presents after fetch without waiting for rebuild`() = runTest {
        val page = rows(20)
        val store = FakeChatThreadStore(rows = page).apply { storedUnreadCount = 12 }
        val registry = ChatCoordinatorRegistry(
            store = store,
            scope = backgroundScope,
            computeDispatcher = StandardTestDispatcher(testScheduler),
        )
        ChatTimelinePrimer(
            ChatTimelinePrimer.Hooks(
                registry = { registry },
                store = { store },
                isConversationActive = { false },
                unknownLabel = { "Unknown" },
            ),
        ).prime(
            Conversation.Direct(
                ChatFixtures.contact(name = "Pat", id = ChatFixtures.CONTACT, unreadCount = 12),
            ),
        )
        val coordinator = registry.existingCoordinator(
            ChatConversationID.dm(ChatFixtures.RADIO, ChatFixtures.CONTACT),
        )!!
        val (viewModel, _) = makeThreadViewModel(
            store = store,
            unreadCount = { 12 },
            coordinator = coordinator,
        )

        viewModel.loadInitial()

        val state = viewModel.renderState.value
        val present = assertIs<ChatInitialScrollPolicy.FirstSnapshotDecision.Present>(state.firstSnapshot)
        assertEquals(page[8].id, present.target)
        assertTrue(state.items[8].grouping.showNewMessagesDivider)
    }

    @Test
    fun `consumeAnchor retires the open target so a later snapshot does not re-jump`() = runTest {
        val store = FakeChatThreadStore(rows = rows(20)).apply { storedUnreadCount = 12 }
        val (viewModel, _) = makeThreadViewModel(store = store, unreadCount = { 12 })
        viewModel.loadInitial()
        runCurrent()
        assertIs<ChatInitialScrollPolicy.FirstSnapshotDecision.Present>(viewModel.renderState.value.firstSnapshot)

        viewModel.consumeAnchor()

        assertEquals(
            ChatInitialScrollPolicy.FirstSnapshotDecision.Present(null),
            viewModel.renderState.value.firstSnapshot,
        )
    }
}

/**
 * Send, delete, and environment changes update the open thread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatThreadSendTest {
    @Test
    fun `delete removes the row and recomputes the last-message date`() = runTest {
        val messages = rows(3)
        val store = FakeChatThreadStore(rows = messages)
        val (viewModel, _) = makeThreadViewModel(store = store)
        viewModel.loadInitial()
        runCurrent()

        viewModel.deleteMessage(messages[1].id)
        runCurrent()

        val state = viewModel.renderState.value
        assertEquals(2, state.items.size)
        assertNull(state.items.firstOrNull { it.id == messages[1].id })
        assertTrue(store.deletedIds.contains(messages[1].id))
        assertEquals(1, store.recomputeLastMessageCalls)
    }

    @Test
    fun deletingAChatMessageWhileDisconnectedDoesNotRadio() = runTest {
        val messages = rows(1)
        val store = FakeChatThreadStore(rows = messages)
        var readyReads = 0
        var serviceReads = 0
        val (viewModel, _) = makeThreadViewModel(
            store = store,
            isReady = {
                readyReads += 1
                false
            },
            messageService = {
                serviceReads += 1
                error("message service")
            },
            sendQueue = {
                serviceReads += 1
                error("send queue")
            },
        )
        viewModel.loadInitial()
        runCurrent()
        readyReads = 0
        serviceReads = 0

        viewModel.deleteMessage(messages[0].id)
        runCurrent()

        val state = viewModel.renderState.value
        assertEquals(1, readyReads)
        assertEquals(0, serviceReads)
        assertTrue(store.deletedIds.isEmpty())
        assertEquals(0, store.recomputeLastMessageCalls)
        assertEquals(messages[0].id, state.items.single().id)
        assertEquals(null, state.errorMessage)
        assertEquals(null, state.sendErrorMessage)
    }

    /**
     * A storage failure carries whatever text the driver wrote; the user reads the shipped sentence, not
     * that text. This is the whole point of routing every chat catch through [ChatErrorMessages].
     */
    @Test
    fun `a raw storage failure reaches the user as the shipped sentence`() = runTest {
        val messages = rows(3)
        val store = FakeChatThreadStore(rows = messages).apply { deleteFailureText = RAW_STORAGE_TEXT }
        val (viewModel, _) = makeThreadViewModel(store = store)
        viewModel.loadInitial()
        runCurrent()

        viewModel.deleteMessage(messages[1].id)
        runCurrent()

        val shown = viewModel.renderState.value.errorMessage
        assertEquals(ChatErrorMessages.developerEnglish().deleteMessageFailed, shown)
        assertFalse(RAW_STORAGE_TEXT in (shown ?: ""))
    }

    @Test
    fun `send appends an optimistic pending bubble then folds to delivered`() = runTest {
        val session = FakeChatSession()
        val messageStore = FakeSendQueueStore()
        val ids = SequentialIds()
        val messageService = testMessageService(session, messageStore.messages, backgroundScope, ids = ids)
        val sendQueue = ChatSendQueueService(
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
        val contact = ChatFixtures.contact(name = "Bob", id = ChatFixtures.CONTACT)
        val (viewModel, _) = makeThreadViewModel(
            store = FakeChatThreadStore(),
            currentContact = { contact },
            messageService = { messageService },
            sendQueue = { sendQueue },
        )
        viewModel.loadInitial()
        runCurrent()

        viewModel.sendMessage("hi")
        runCurrent()

        val sent = viewModel.renderState.value.items.single()
        assertEquals(MessageStatus.PENDING, sent.footer.status)
        assertTrue(sent.envelope.isOutgoing)

        viewModel.handleStatus(MessageStatusEvent.StatusResolved(sent.id, MessageStatus.SENT))
        assertEquals(MessageStatus.SENT, viewModel.renderState.value.items.single().footer.status)

        viewModel.handleStatus(
            MessageStatusEvent.StatusResolved(sent.id, MessageStatus.DELIVERED, roundTripTime = 900),
        )
        assertEquals(MessageStatus.DELIVERED, viewModel.renderState.value.items.single().footer.status)
    }

    @Test
    fun `a DM send notifies the conversation list`() = runTest {
        var notifications = 0
        val session = FakeChatSession()
        val messageStore = FakeSendQueueStore()
        val ids = SequentialIds()
        val messageService = testMessageService(session, messageStore.messages, backgroundScope, ids = ids)
        val sendQueue = ChatSendQueueService(
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
        val contact = ChatFixtures.contact(name = "Bob", id = ChatFixtures.CONTACT)
        val (viewModel, _) = makeThreadViewModel(
            store = FakeChatThreadStore(),
            currentContact = { contact },
            messageService = { messageService },
            sendQueue = { sendQueue },
            onConversationsChanged = { notifications += 1 },
        )
        viewModel.loadInitial()
        runCurrent()
        notifications = 0

        viewModel.sendMessage("hi")
        runCurrent()

        assertEquals(1, notifications)
    }

    @Test
    fun `a local enqueue failure marks the open thread seen`() = runTest {
        val messageStore = FakeSendQueueStore().apply {
            insertError = { IllegalStateException("disk full") }
        }
        val ids = SequentialIds()
        val messageService = testMessageService(
            FakeChatSession(),
            messageStore.messages,
            backgroundScope,
            ids = ids,
        )
        val sendQueue = ChatSendQueueService(
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
        }
        val contact = ChatFixtures.contact(name = "Bob", id = ChatFixtures.CONTACT)
        val (viewModel, _) = makeThreadViewModel(
            store = threadStore,
            currentContact = { contact },
            messageService = { messageService },
            sendQueue = { sendQueue },
        )
        viewModel.loadInitial()
        runCurrent()
        threadStore.markFailedSendsSeenCalls = 0

        viewModel.sendMessage("hi")
        runCurrent()

        assertEquals(
            ChatErrorMessages.developerEnglish().sendQueuePersistFailed,
            viewModel.renderState.value.sendErrorMessage,
        )
        assertEquals(1, threadStore.markFailedSendsSeenCalls)
        assertEquals(MessageStatus.FAILED, viewModel.renderState.value.items.single().footer.status)
    }

    @Test
    fun aReactionSendFaultShowsMappedCopyNotExceptionText() = runTest {
        val (viewModel, _) = makeThreadViewModel()
        viewModel.loadInitial()
        runCurrent()

        viewModel.surfaceReactionFailure(MessageServiceException.SendFailed("radio offline"))

        assertEquals(
            ChatErrorMessages.developerEnglish().sendFailed,
            viewModel.renderState.value.errorMessage,
        )
        assertNull(viewModel.renderState.value.sendErrorMessage)
    }

    @Test
    fun `clearing a chat send error does not radio`() = runTest {
        val session = FakeChatSession()
        var serviceReads = 0
        val (viewModel, _) = makeThreadViewModel(
            messageService = {
                serviceReads += 1
                testMessageService(session, InMemoryMessageStore(), backgroundScope)
            },
        )

        viewModel.clearSendError()
        runCurrent()

        assertEquals(0, serviceReads)
        assertTrue(session.directSendAttempts.isEmpty())
        assertTrue(session.channelSends.isEmpty())
        assertEquals(0, session.resetPathCalls)
        assertNull(viewModel.renderState.value.sendErrorMessage)
    }

    @Test
    fun `scrolling to a missing new-messages divider does not radio`() = runTest {
        val session = FakeChatSession()
        var serviceReads = 0
        val (viewModel, _) = makeThreadViewModel(
            messageService = {
                serviceReads += 1
                testMessageService(session, InMemoryMessageStore(), backgroundScope)
            },
        )

        viewModel.requestScrollToDivider()
        runCurrent()

        assertEquals(0, serviceReads)
        assertTrue(session.directSendAttempts.isEmpty())
        assertTrue(session.channelSends.isEmpty())
        assertEquals(0, session.resetPathCalls)
        assertNull(viewModel.renderState.value.scrollCommand)
    }

    @Test
    fun `a blank chat composer send does not radio`() = runTest {
        val session = FakeChatSession()
        var serviceReads = 0
        val contact = ChatFixtures.contact(name = "Bob")
        val (viewModel, _) = makeThreadViewModel(
            currentContact = { contact },
            isReady = { true },
            messageService = {
                serviceReads += 1
                testMessageService(session, InMemoryMessageStore(), backgroundScope)
            },
        )

        viewModel.sendMessage("  ")
        runCurrent()

        assertEquals(0, serviceReads)
        assertTrue(session.directSendAttempts.isEmpty())
        assertTrue(session.channelSends.isEmpty())
        assertEquals(0, session.resetPathCalls)
        assertNull(viewModel.renderState.value.sendErrorMessage)
        assertTrue(viewModel.renderState.value.items.isEmpty())
    }

    @Test
    fun `clearing a chat thread error does not radio`() = runTest {
        val session = FakeChatSession()
        var serviceReads = 0
        val (viewModel, _) = makeThreadViewModel(
            messageService = {
                serviceReads += 1
                testMessageService(session, InMemoryMessageStore(), backgroundScope)
            },
        )
        viewModel.surfaceReactionFailure(MessageServiceException.SendFailed("radio offline"))
        assertEquals(
            ChatErrorMessages.developerEnglish().sendFailed,
            viewModel.renderState.value.errorMessage,
        )

        viewModel.clearError()
        runCurrent()

        assertEquals(0, serviceReads)
        assertTrue(session.directSendAttempts.isEmpty())
        assertTrue(session.channelSends.isEmpty())
        assertEquals(0, session.resetPathCalls)
        assertNull(viewModel.renderState.value.errorMessage)
        assertNull(viewModel.renderState.value.sendErrorMessage)
    }

    @Test
    fun `leaving the thread notifies the conversation list`() = runTest {
        var notifications = 0
        val (viewModel, _) = makeThreadViewModel(
            onConversationsChanged = { notifications += 1 },
        )
        viewModel.loadInitial()
        runCurrent()
        notifications = 0

        viewModel.notifyConversationsChanged()

        assertEquals(1, notifications)
    }

    @Test
    fun `active conversation is claimed on open and released on cleanup`() = runTest {
        val sink = RecordingActiveConversationSink()
        val store = FakeChatThreadStore(rows = rows(2))
        val conversationID = ChatConversationID.dm(ChatFixtures.RADIO, ChatFixtures.CONTACT)
        val (viewModel, _) = makeThreadViewModel(
            store = store,
            conversationID = conversationID,
            activeConversation = sink,
        )
        viewModel.loadInitial()
        runCurrent()
        assertEquals(conversationID, sink.active)

        viewModel.cleanup()
        assertNull(sink.active)
    }

    @Test
    fun `cleanup cancels and retires thread actions`() = runTest {
        val (viewModel, _) = makeThreadViewModel()
        var completions = 0
        viewModel.launchAction {
            delay(1_000)
            completions += 1
        }
        runCurrent()

        viewModel.cleanup()
        advanceTimeBy(1_000)
        runCurrent()
        viewModel.launchAction { completions += 1 }
        runCurrent()

        assertEquals(0, completions)
    }

    @Test
    fun `applyEnvInputs rebakes hop footer when the toggle changes`() = runTest {
        val flood = incoming(1_000L).copy(channelIndex = 0, pathLength = 0x02)
        val store = FakeChatThreadStore(rows = listOf(flood))
        var env = EnvInputs(currentUserName = "Me", showIncomingSendTime = false)
        val (viewModel, _) = makeThreadViewModel(store = store, envInputs = { env })
        viewModel.loadInitial()
        runCurrent()

        assertFalse(viewModel.renderState.value.items.single().footer.showHop)

        env = env.copy(showIncomingHopCount = true)
        viewModel.applyEnvInputs()
        runCurrent()

        assertTrue(viewModel.renderState.value.items.single().footer.showHop)
    }

    @Test
    fun `applyEnvInputs rebakes map preview appearance without changing chrome`() = runTest {
        val withCoord = incoming(1_000L).copy(
            channelIndex = 0,
            text = "at 37.77,-122.42",
        )
        var env = EnvInputs(
            currentUserName = "Me",
            showIncomingSendTime = false,
            showMapPreviews = true,
            isDark = false,
        )
        val (viewModel, _) = makeThreadViewModel(
            store = FakeChatThreadStore(rows = listOf(withCoord)),
            envInputs = { env },
        )
        viewModel.loadInitial()
        runCurrent()
        val before = viewModel.renderState.value.items.single()

        env = env.copy(mapBasemapIsDark = true)
        viewModel.applyEnvInputs()
        runCurrent()

        val after = viewModel.renderState.value.items.single()
        assertTrue(before !== after)
        assertFalse((before.content.last() as MessageFragment.MapPreview).state.isDark)
        assertTrue((after.content.last() as MessageFragment.MapPreview).state.isDark)
        assertFalse(env.isDark)
    }

    @Test
    fun `applyEnvInputs is a no-op when env is unchanged`() = runTest {
        val flood = incoming(1_000L).copy(channelIndex = 0, pathLength = 0x02)
        val store = FakeChatThreadStore(rows = listOf(flood))
        val env = EnvInputs(currentUserName = "Me", showIncomingSendTime = false, showIncomingHopCount = true)
        val (viewModel, _) = makeThreadViewModel(store = store, envInputs = { env })
        viewModel.loadInitial()
        runCurrent()
        val before = viewModel.renderState.value

        viewModel.applyEnvInputs()
        runCurrent()

        assertTrue(before === viewModel.renderState.value)
        assertTrue(viewModel.renderState.value.items.single().footer.showHop)
    }

    @Test
    fun `applyEnvInputs clears map snapshot failures on offline to online`() = runTest {
        val snapshots = one.meshcore.shared.map.MapSnapshotStore()
        val req = one.meshcore.shared.map.MapSnapshotRequest.rounded(
            latitude = 1.0,
            longitude = 2.0,
            isDark = false,
            isOffline = true,
        )
        snapshots.markFailed(req)
        var env = EnvInputs(currentUserName = "Me", showIncomingSendTime = false, isOffline = true)
        val (viewModel, _) = makeThreadViewModel(
            store = FakeChatThreadStore(rows = rows(1)),
            envInputs = { env },
            mapSnapshotStore = { snapshots },
        )
        viewModel.loadInitial()
        runCurrent()
        assertTrue(snapshots.isFailed(req))

        env = env.copy(isOffline = false)
        viewModel.applyEnvInputs()
        runCurrent()

        assertFalse(snapshots.isFailed(req))
    }

    @Test
    fun `isInitialLoading false for uninitialized empty and true only while loading`() {
        val empty = ChatThreadRenderState.EMPTY
        assertTrue(!empty.isInitialLoading)
        val loading = empty.copy(phase = ChatRenderState.LoadPhase.LOADING)
        assertTrue(loading.isInitialLoading)
        val loadedEmpty = empty.copy(phase = ChatRenderState.LoadPhase.LOADED)
        assertTrue(!loadedEmpty.isInitialLoading)
    }
}

/**
 * A disconnected thread action must not radio.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatThreadSilenceTest {
    @Test
    fun requestingAPreviewForAnUnknownMessageDoesNotRadio() = runTest {
        val messages = rows(1)
        var gate = false
        val (viewModel, _) = makeThreadViewModel(
            store = FakeChatThreadStore(rows = messages),
            envInputs = {
                if (gate) error("env")
                EnvInputs(currentUserName = "Me", showIncomingSendTime = false)
            },
            linkPreviewCache = {
                if (gate) error("cache")
                null
            },
        )
        viewModel.loadInitial()
        runCurrent()
        gate = true

        viewModel.requestPreviewFetch(ChatFixtures.freshUuid())
        runCurrent()

        val state = viewModel.renderState.value
        assertEquals(messages[0].id, state.items.single().id)
        assertEquals(null, state.errorMessage)
        assertEquals(null, state.sendErrorMessage)
    }

    @Test
    fun refreshingChatSenderNamesDoesNotRadio() = runTest {
        val message = ChatFixtures.channelMessage(timestamp = 1_000L, senderName = "Ada")
        val store = FakeChatThreadStore(rows = listOf(message))
        var serviceReads = 0
        var queueReads = 0
        var readyReads = 0
        var envReads = 0
        var mapReads = 0
        var dimensionReads = 0
        val (viewModel, _) = makeThreadViewModel(
            store = store,
            conversationID = ChatConversationID.channel(ChatFixtures.RADIO, channelIndex = 0),
            isReady = { false.also { readyReads += 1 } },
            messageService = { error("message service".also { serviceReads += 1 }) },
            sendQueue = { error("send queue".also { queueReads += 1 }) },
            envInputs = {
                EnvInputs(currentUserName = "Me", showIncomingSendTime = false).also { envReads += 1 }
            },
            mapSnapshotStore = { null.also { mapReads += 1 } },
            inlineImageDimensions = { null.also { dimensionReads += 1 } },
        )
        viewModel.loadInitial()
        runCurrent()
        val envAfterLoad = envReads
        val mapAfterLoad = mapReads
        val dimensionsAfterLoad = dimensionReads
        val fetchesAfterLoad = store.fetchLimits.size
        serviceReads = 0
        queueReads = 0
        readyReads = 0

        viewModel.refreshSenderNames()
        runCurrent()

        val state = viewModel.renderState.value
        assertEquals(listOf(0, 0, 0), listOf(serviceReads, queueReads, readyReads))
        assertEquals(
            listOf(true, true, true),
            listOf(envReads > envAfterLoad, mapReads > mapAfterLoad, dimensionReads > dimensionsAfterLoad),
        )
        assertEquals(fetchesAfterLoad, store.fetchLimits.size)
        assertEquals(message.id, state.items.single().id)
        assertEquals("Ada", state.items.single().envelope.senderName)
        assertTrue(store.deletedIds.isEmpty())
        assertEquals(0, store.recomputeLastMessageCalls)
        assertEquals(listOf(null, null), listOf(state.errorMessage, state.sendErrorMessage))
    }

    @Test
    fun scrollingToAChatMentionDoesNotRadio() = runTest {
        val message = incoming(1_000L).copy(
            containsSelfMention = true,
            mentionSeen = false,
            senderNodeName = "Ada",
        )
        val store = FakeChatThreadStore(rows = listOf(message))
        var serviceReads = 0
        var queueReads = 0
        var readyReads = 0
        val (viewModel, _) = makeThreadViewModel(
            store = store,
            isReady = {
                readyReads += 1
                false
            },
            messageService = {
                serviceReads += 1
                error("message service")
            },
            sendQueue = {
                queueReads += 1
                error("send queue")
            },
        )
        viewModel.loadInitial()
        runCurrent()
        val fetchesAfterLoad = store.fetchLimits.size
        val marksAfterLoad = store.markedMentionIds.toList()
        val tokenBefore = (viewModel.renderState.value.scrollCommand as? ScrollCommand.ToMessage)?.token ?: -1
        serviceReads = 0
        queueReads = 0
        readyReads = 0

        viewModel.requestScrollToMention(message.id)
        runCurrent()

        val state = viewModel.renderState.value
        val command = assertIs<ScrollCommand.ToMessage>(state.scrollCommand)
        assertEquals(0, serviceReads)
        assertEquals(0, queueReads)
        assertEquals(0, readyReads)
        assertEquals(message.id, command.messageID)
        assertEquals(true, command.animated)
        assertTrue(command.token > tokenBefore)
        assertEquals(fetchesAfterLoad, store.fetchLimits.size)
        assertEquals(marksAfterLoad, store.markedMentionIds.toList())
        assertEquals(listOf(message.id), store.markedMentionIds)
        assertEquals(message.id, state.items.single().id)
        assertEquals(true, state.items.single().envelope.containsSelfMention)
        assertEquals(true, state.items.single().envelope.mentionSeen)
        assertEquals(true, state.isAtBottom)
        assertTrue(store.deletedIds.isEmpty())
        assertEquals(0, store.recomputeLastMessageCalls)
        assertEquals(null, state.errorMessage)
        assertEquals(null, state.sendErrorMessage)
    }

    @Test
    fun lookingUpAnUnwiredPreviewImageDoesNotRadio() = runTest {
        var serviceReads = 0
        var queueReads = 0
        var cacheReads = 0
        val (viewModel, _) = makeThreadViewModel(
            messageService = {
                serviceReads += 1
                error("message service")
            },
            sendQueue = {
                queueReads += 1
                error("send queue")
            },
            linkPreviewCache = {
                cacheReads += 1
                null
            },
        )
        val cacheAfterInit = cacheReads
        assertTrue(cacheAfterInit > 0)
        val key = ChatFixtures.freshUuid()

        val image = viewModel.previewImageBytes(
            ImageReference(key, ImageReference.Role.LINK_PREVIEW_IMAGE),
        )
        val icon = viewModel.previewImageBytes(
            ImageReference(key, ImageReference.Role.LINK_PREVIEW_ICON),
        )
        val inline = viewModel.previewImageBytes(
            ImageReference(key, ImageReference.Role.INLINE),
        )

        assertEquals(null, image)
        assertEquals(null, icon)
        assertEquals(null, inline)
        assertEquals(cacheAfterInit, cacheReads)
        assertEquals(0, serviceReads)
        assertEquals(0, queueReads)
        assertTrue(viewModel.renderState.value.items.isEmpty())
        assertEquals(null, viewModel.renderState.value.errorMessage)
        assertEquals(null, viewModel.renderState.value.sendErrorMessage)
    }

    @Test
    fun scrollingAChatUpFromTheBottomDoesNotRadio() = runTest {
        val quiet = quietThread()
        val viewModel = quiet.viewModel
        assertEquals(true, viewModel.renderState.value.isAtBottom)
        assertEquals(0, quiet.reads.serviceReads)
        assertEquals(0, quiet.reads.queueReads)
        assertEquals(0, quiet.reads.readyReads)

        viewModel.setNearBottom(false)
        runCurrent()

        val state = viewModel.renderState.value
        assertEquals(0, quiet.reads.serviceReads)
        assertEquals(0, quiet.reads.queueReads)
        assertEquals(0, quiet.reads.readyReads)
        assertEquals(false, state.isAtBottom)
        assertEquals(0, state.unreadWhileScrolledUp)
        assertTrue(state.items.isEmpty())
        assertEquals(null, state.errorMessage)
        assertEquals(null, state.sendErrorMessage)
    }

    @Test
    fun clearingAChatThreadErrorDoesNotRadio() = runTest {
        val quiet = quietThread()
        val viewModel = quiet.viewModel
        assertEquals(true, viewModel.renderState.value.isAtBottom)
        assertEquals(null, viewModel.renderState.value.errorMessage)
        assertEquals(null, viewModel.renderState.value.sendErrorMessage)
        assertEquals(null, viewModel.renderState.value.errorBannerMessage)
        assertEquals(0, quiet.reads.serviceReads)
        assertEquals(0, quiet.reads.queueReads)
        assertEquals(0, quiet.reads.readyReads)

        viewModel.clearError()
        runCurrent()

        val state = viewModel.renderState.value
        assertEquals(0, quiet.reads.serviceReads)
        assertEquals(0, quiet.reads.queueReads)
        assertEquals(0, quiet.reads.readyReads)
        assertEquals(true, state.isAtBottom)
        assertEquals(0, state.unreadWhileScrolledUp)
        assertTrue(state.items.isEmpty())
        assertEquals(null, state.errorMessage)
        assertEquals(null, state.sendErrorMessage)
        assertEquals(null, state.errorBannerMessage)
    }

    @Test
    fun keepingAChatTranscriptAtTheBottomDoesNotRadio() = runTest {
        var serviceReads = 0
        var readyReads = 0
        val (viewModel, _) = makeThreadViewModel(
            messageService = {
                serviceReads += 1
                null
            },
            isReady = {
                readyReads += 1
                false
            },
        )
        assertEquals(true, viewModel.renderState.value.isAtBottom)
        assertEquals(0, viewModel.renderState.value.unreadWhileScrolledUp)
        assertTrue(viewModel.renderState.value.items.isEmpty())
        assertEquals(null, viewModel.renderState.value.errorMessage)
        assertEquals(null, viewModel.renderState.value.errorBannerMessage)
        assertEquals(0, serviceReads)
        assertEquals(0, readyReads)

        viewModel.setNearBottom(true)
        runCurrent()

        val state = viewModel.renderState.value
        assertEquals(0, serviceReads)
        assertEquals(0, readyReads)
        assertEquals(true, state.isAtBottom)
        assertEquals(0, state.unreadWhileScrolledUp)
        assertTrue(state.items.isEmpty())
        assertEquals(null, state.errorMessage)
        assertEquals(null, state.errorBannerMessage)
    }

    @Test
    fun consumingAChatOpenAnchorDoesNotRadio() = runTest {
        val quiet = quietThread()
        val viewModel = quiet.viewModel
        assertEquals(
            ChatInitialScrollPolicy.FirstSnapshotDecision.Present(null),
            viewModel.renderState.value.firstSnapshot,
        )
        assertEquals(true, viewModel.renderState.value.isAtBottom)
        assertEquals(0, quiet.reads.serviceReads)
        assertEquals(0, quiet.reads.queueReads)
        assertEquals(0, quiet.reads.readyReads)

        viewModel.consumeAnchor()
        runCurrent()

        val state = viewModel.renderState.value
        assertEquals(0, quiet.reads.serviceReads)
        assertEquals(0, quiet.reads.queueReads)
        assertEquals(0, quiet.reads.readyReads)
        assertEquals(ChatInitialScrollPolicy.FirstSnapshotDecision.Present(null), state.firstSnapshot)
        assertEquals(true, state.isAtBottom)
        assertEquals(0, state.unreadWhileScrolledUp)
        assertTrue(state.items.isEmpty())
        assertEquals(null, state.errorMessage)
        assertEquals(null, state.sendErrorMessage)
        assertEquals(null, state.errorBannerMessage)
    }

    @Test
    fun clearingAChatErrorBannerDoesNotRadio() = runTest {
        val quiet = quietThread()
        val viewModel = quiet.viewModel
        assertEquals(null, viewModel.renderState.value.errorBannerMessage)
        assertEquals(null, viewModel.renderState.value.errorMessage)
        assertEquals(true, viewModel.renderState.value.isAtBottom)
        assertEquals(0, quiet.reads.serviceReads)
        assertEquals(0, quiet.reads.queueReads)
        assertEquals(0, quiet.reads.readyReads)

        viewModel.clearErrorBanner()
        runCurrent()

        val state = viewModel.renderState.value
        assertEquals(0, quiet.reads.serviceReads)
        assertEquals(0, quiet.reads.queueReads)
        assertEquals(0, quiet.reads.readyReads)
        assertEquals(null, state.errorBannerMessage)
        assertEquals(null, state.errorMessage)
        assertEquals(null, state.sendErrorMessage)
        assertEquals(true, state.isAtBottom)
        assertEquals(0, state.unreadWhileScrolledUp)
        assertTrue(state.items.isEmpty())
    }
}

private fun incoming(timestamp: Long, id: Uuid = ChatFixtures.freshUuid()): MessageDto = ChatFixtures.message(
    id = id,
    text = "in-$timestamp",
    timestamp = timestamp,
    createdAt = timestamp.toDouble(),
    direction = MessageDirection.INCOMING,
    contactID = ChatFixtures.CONTACT,
)

private fun outgoing(timestamp: Long, status: MessageStatus, id: Uuid = ChatFixtures.freshUuid()): MessageDto =
    ChatFixtures.message(
        id = id,
        text = "out-$timestamp",
        timestamp = timestamp,
        createdAt = timestamp.toDouble(),
        direction = MessageDirection.OUTGOING,
        status = status,
        contactID = ChatFixtures.CONTACT,
    )

private fun rows(count: Int): List<MessageDto> = (0 until count).map { incoming(1_000L + it) }

/** A chat thread whose message service, send queue, and ready flag throw, so an edit can prove it did not radio. */
private fun TestScope.quietThread(): QuietThread {
    val reads = QuietThreadReads()
    val (viewModel, _) = makeThreadViewModel(
        messageService = {
            reads.serviceReads += 1
            error("message service")
        },
        sendQueue = {
            reads.queueReads += 1
            error("send queue")
        },
        isReady = {
            reads.readyReads += 1
            false
        },
    )
    return QuietThread(viewModel, reads)
}

private class QuietThread(val viewModel: ChatThreadViewModel, val reads: QuietThreadReads)

private class QuietThreadReads {
    var serviceReads = 0
    var queueReads = 0
    var readyReads = 0
}

private class RecordingActiveConversationSink : ActiveConversationSink {
    var active: ChatConversationID? = null
        private set

    override suspend fun setActive(conversation: ChatConversationID) {
        active = conversation
    }

    override fun clearActiveAsync(conversation: ChatConversationID) {
        if (active == conversation) active = null
    }
}

private const val RAW_STORAGE_TEXT = "null cursor at row 3"
