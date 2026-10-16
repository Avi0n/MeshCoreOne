package one.meshcore.shared.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.channel.ChannelSecret
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.RoomPermissionLevel
import one.meshcore.shared.remotenode.FakeNodeSession
import one.meshcore.shared.remotenode.InMemoryNodePasswordStore
import one.meshcore.shared.remotenode.RemoteNodeError
import one.meshcore.shared.remotenode.RemoteNodeService
import one.meshcore.shared.room.FakeRoomServerSession
import one.meshcore.shared.room.InMemoryRoomStore
import one.meshcore.shared.room.RoomConversationController
import one.meshcore.shared.room.RoomConversationInputs
import one.meshcore.shared.room.RoomServerEdges
import one.meshcore.shared.room.RoomServerService
import one.meshcore.shared.room.joinRoom
import one.meshcore.shared.room.roomContact
import one.meshcore.shared.room.roomSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Channel, room, and public-channel sends through the production composer, [MessageService], and
 * [RoomConversationController]. [FakeChatSession] and [FakeRoomServerSession] record the command
 * arguments and have no BLE, TCP, or bridge. A recorded payload in this file was not transmitted.
 * `.sent` on a channel row means the fake accepted the queue-for-TX call, not that a packet reached the air.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChannelRoomSendFixtureTest {
    @Test
    fun publicComposerSendRecordsSlotZeroAndDoesNotOpenARadio() = runTest {
        val graph = ChannelGraph(backgroundScope)
        graph.store.messages.seedChannel(publicChannel())
        val viewModel = openChannel(graph, ChannelSecret.PUBLIC_CHANNEL_SLOT)

        sendThroughComposer(viewModel, graph, PUBLIC_TEXT)

        val recorded = graph.session.channelSends.single()
        assertEquals(ChannelSecret.PUBLIC_CHANNEL_SLOT, recorded.first)
        assertEquals(PUBLIC_TEXT, recorded.second)
        assertEquals(TEST_NOW_SECONDS.toLong(), recorded.third)
        val saved = graph.store.messages.fetchMessage(viewModel.renderState.value.items.single().id)
        assertEquals(MessageStatus.SENT, saved?.status)
        assertEquals(MessageStatus.SENT, viewModel.renderState.value.items.single().footer.status)
        assertNoRadio(graph.session)
    }

    @Test
    fun hashtagComposerSendKeepsItsIndexAndDoesNotOpenARadio() = runTest {
        val graph = ChannelGraph(backgroundScope)
        graph.store.messages.seedChannel(hashtagChannel())
        val viewModel = openChannel(graph, HASHTAG_SLOT)

        sendThroughComposer(viewModel, graph, HASHTAG_TEXT)

        val recorded = graph.session.channelSends.single()
        assertEquals(HASHTAG_SLOT, recorded.first)
        assertNotEquals(ChannelSecret.PUBLIC_CHANNEL_SLOT, recorded.first)
        assertEquals(HASHTAG_TEXT, recorded.second)
        val saved = graph.store.messages.fetchMessage(viewModel.renderState.value.items.single().id)
        assertEquals(MessageStatus.SENT, saved?.status)
        assertEquals(HASHTAG_SLOT, saved?.channelIndex)
        assertNoRadio(graph.session)
    }

    @Test
    fun hashtagOneShotRecordsTheIndexAndDoesNotOpenARadio() = runTest {
        val graph = ChannelGraph(backgroundScope)
        graph.store.messages.seedChannel(hashtagChannel())

        val (id, timestamp) = graph.messageService.sendChannelMessage(
            HASHTAG_TEXT,
            HASHTAG_SLOT,
            ChatFixtures.RADIO,
        )

        val recorded = graph.session.channelSends.single()
        assertEquals(HASHTAG_SLOT, recorded.first)
        assertNotEquals(ChannelSecret.PUBLIC_CHANNEL_SLOT, recorded.first)
        assertEquals(HASHTAG_TEXT, recorded.second)
        assertEquals(timestamp, recorded.third)
        assertEquals(MessageStatus.SENT, graph.store.messages.fetchMessage(id)?.status)
        assertNoRadio(graph.session)
    }

    @Test
    fun disconnectedComposerDoesNotSend() = runTest {
        val session = FakeChatSession()
        var queueReads = 0
        val (viewModel, _) = makeThreadViewModel(
            conversationID = ChatConversationID.channel(ChatFixtures.RADIO, ChannelSecret.PUBLIC_CHANNEL_SLOT),
            messageService = { null },
            sendQueue = {
                queueReads += 1
                null
            },
        )
        viewModel.loadInitial()
        runCurrent()

        viewModel.sendMessage(PUBLIC_TEXT)
        runCurrent()

        assertEquals(0, queueReads)
        assertTrue(viewModel.renderState.value.items.isEmpty())
        assertNoRadio(session)
    }

    @Test
    fun oversizedChannelTextFailsBeforeTheSession() = runTest {
        val graph = ChannelGraph(backgroundScope)
        graph.store.messages.seedChannel(publicChannel())
        val viewModel = openChannel(graph, ChannelSecret.PUBLIC_CHANNEL_SLOT)
        viewModel.loadInitial()
        runCurrent()
        val over = "a".repeat(ChatConstants.maxChannelMessageLength(null) + 1)

        viewModel.sendMessage(over)
        runCurrent()

        assertEquals(
            ChatErrorMessages.developerEnglish().messageTooLong,
            viewModel.renderState.value.sendErrorMessage,
        )
        assertTrue(viewModel.renderState.value.items.isEmpty())
        assertTrue(graph.session.channelSends.isEmpty())
        assertNoRadio(graph.session)
    }

    @Test
    fun rejectedScopeFailsBeforeTransmission() = runTest {
        val graph = ChannelGraph(backgroundScope)
        graph.store.messages.seedChannel(publicChannel())
        graph.session.beforeScopedSend = { throw ChatDeviceException(REJECTED_FIRMWARE_CODE) }
        val viewModel = openChannel(graph, ChannelSecret.PUBLIC_CHANNEL_SLOT)
        viewModel.loadInitial()
        runCurrent()
        viewModel.start(emptyFlow(), graph.messageService.statusEvents().consumeAsFlow())
        runCurrent()

        viewModel.sendMessage(PUBLIC_TEXT)
        // The failed-status event reloads the thread store. This fixture's thread store is not the
        // message store, so the open-thread row can disappear while the saved row stays FAILED.
        val messageID = viewModel.renderState.value.items.single().id
        runCurrent()
        graph.queue.channelQueue.awaitDrainCompletion()
        runCurrent()

        assertTrue(graph.session.channelSends.isEmpty())
        assertEquals(MessageStatus.FAILED, graph.store.messages.fetchMessage(messageID)?.status)
        assertNoRadio(graph.session)
    }

    @Test
    fun queuedPublicSendReplaysAfterReconnectWithoutOpeningARadio() = runTest {
        val graph = ChannelGraph(
            backgroundScope,
            MessageServiceConfig(poolBackoff = PoolBackoffConfig(attemptCap = 0, baseDelaySeconds = 0.0)),
        )
        graph.store.messages.seedChannel(publicChannel())
        graph.session.directSendError = {
            ChatDeviceException(FirmwareDeviceErrorCode.CHANNEL_MESSAGE_NOT_FOUND)
        }
        val viewModel = openChannel(graph, ChannelSecret.PUBLIC_CHANNEL_SLOT)
        viewModel.loadInitial()
        runCurrent()

        viewModel.sendMessage(PUBLIC_TEXT)
        runCurrent()

        val messageID = viewModel.renderState.value.items.single().id
        assertTrue(graph.session.channelSends.isEmpty())
        assertEquals(MessageStatus.PENDING, graph.store.messages.fetchMessage(messageID)?.status)
        assertEquals(1, graph.store.fetchPendingSends(ChatFixtures.RADIO).size)
        graph.queue.shutdown()
        runCurrent()
        assertTrue(graph.session.channelSends.isEmpty())

        graph.session.directSendError = null
        val reconnected = graph.reconnectedQueue(backgroundScope)
        reconnected.hydrate()
        reconnected.channelQueue.awaitDrainCompletion()
        runCurrent()

        val recorded = graph.session.channelSends.single()
        assertEquals(ChannelSecret.PUBLIC_CHANNEL_SLOT, recorded.first)
        assertEquals(PUBLIC_TEXT, recorded.second)
        assertEquals(TEST_NOW_SECONDS.toLong(), recorded.third)
        assertEquals(MessageStatus.SENT, graph.store.messages.fetchMessage(messageID)?.status)
        assertTrue(graph.store.fetchPendingSends(ChatFixtures.RADIO).isEmpty())
        assertNoRadio(graph.session)
    }

    @Test
    fun roomPostRecordsTheCommandAndDoesNotDiscoverOrSync() = runTest {
        val rooms = RoomGraph(backgroundScope)
        val session = roomSession()
        rooms.store.seedSession(session)
        rooms.roomSession.sendResult = null
        rooms.controller.loadMessages(session)

        rooms.controller.sendMessage(ROOM_TEXT)
        runCurrent()

        val recorded = rooms.roomSession.sendCalls.single()
        assertEquals(ROOM_TEXT, recorded.second)
        assertTrue(recorded.first.contentEquals(session.publicKey))
        val saved = rooms.store.fetchRoomMessage(rooms.controller.messages.single().id)
        assertEquals(MessageStatus.SENT.raw, saved?.statusRawValue)
        assertRoomStayedLocal(rooms.roomSession)
    }

    @Test
    fun roomGuestDoesNotPost() = runTest {
        val rooms = RoomGraph(backgroundScope)
        val session = roomSession(permissionLevel = RoomPermissionLevel.GUEST)
        rooms.store.seedSession(session)
        rooms.controller.loadMessages(session)

        rooms.controller.sendMessage(ROOM_TEXT)
        runCurrent()

        assertTrue(rooms.roomSession.sendCalls.isEmpty())
        assertTrue(rooms.controller.messages.isEmpty())
        assertRoomStayedLocal(rooms.roomSession)
    }

    @Test
    fun roomLoginFailureDoesNotPost() = runTest {
        val rooms = RoomGraph(backgroundScope)
        val contact = roomContact()
        var thrown: Throwable? = null
        val job = launch {
            thrown = runCatching {
                rooms.service.joinRoom(contact.radioID, contact, password = "secret")
            }.exceptionOrNull()
        }
        runCurrent()
        rooms.nodeSession.emitLoginFailed(contact.publicKey.copyOf(PUBLIC_KEY_PREFIX_BYTES))
        runCurrent()
        job.join()

        assertIs<RemoteNodeError.LoginFailed>(thrown)
        assertTrue(rooms.roomSession.sendCalls.isEmpty())
        assertRoomStayedLocal(rooms.roomSession)
        assertFalse(rooms.passwords.hasPassword(contact.publicKey))
    }

    private fun TestScope.openChannel(graph: ChannelGraph, index: Int): ChatThreadViewModel {
        val (viewModel, _) = makeThreadViewModel(
            conversationID = ChatConversationID.channel(ChatFixtures.RADIO, index),
            messageService = { graph.messageService },
            sendQueue = { graph.queue },
        )
        return viewModel
    }

    private suspend fun TestScope.sendThroughComposer(
        viewModel: ChatThreadViewModel,
        graph: ChannelGraph,
        text: String,
    ) {
        viewModel.loadInitial()
        runCurrent()
        viewModel.start(emptyFlow(), graph.messageService.statusEvents().consumeAsFlow())
        runCurrent()
        viewModel.sendMessage(text)
        runCurrent()
        graph.queue.channelQueue.awaitDrainCompletion()
        runCurrent()
    }

    private class ChannelGraph(scope: CoroutineScope, config: MessageServiceConfig = MessageServiceConfig()) {
        val session = FakeChatSession()
        val store = FakeSendQueueStore()
        val messageService = testMessageService(session, store.messages, scope, config = config)
        val queue = newQueue(scope)

        fun reconnectedQueue(scope: CoroutineScope): ChatSendQueueService = newQueue(scope)

        private fun newQueue(scope: CoroutineScope): ChatSendQueueService = ChatSendQueueService(
            radioID = ChatFixtures.RADIO,
            collaborators = ChatSendQueueCollaborators(
                store = store,
                messageService = messageService,
                channelResolver = FakeChannelResolver(),
                scope = scope,
                now = { TEST_NOW_SECONDS },
                newPendingSendId = PendingSendIds()::next,
            ),
        )
    }

    private class RoomGraph(scope: CoroutineScope) {
        val nodeSession = FakeNodeSession()
        val store = InMemoryRoomStore()
        val passwords = InMemoryNodePasswordStore()
        val roomSession = FakeRoomServerSession()
        val service = RoomServerService(
            RemoteNodeService(nodeSession, store, passwords, scope),
            store,
            roomSession,
            passwords,
            scope,
            edges = RoomServerEdges(now = { ROOM_NOW }),
        )
        val controller = RoomConversationController(
            RoomConversationInputs(
                roomServerService = { service },
                scope = scope,
                fetchSession = { id -> store.fetchSession(id) },
                loadFailedMessage = { ROOM_LOAD_FAILED },
                sendFailedMessage = { ROOM_SEND_FAILED },
            ),
        )
    }

    private companion object {
        const val PUBLIC_TEXT: String = "public hello"
        const val HASHTAG_TEXT: String = "hashtag hello"
        const val HASHTAG_SLOT: Int = 4
        const val HASHTAG_NAME: String = "#town"
        const val ROOM_TEXT: String = "room hello"
        const val ROOM_NOW: Long = 1_720_000_000L
        const val ROOM_LOAD_FAILED: String = "load failed"
        const val ROOM_SEND_FAILED: String = "send failed"
        const val REJECTED_FIRMWARE_CODE: Int = 1
        const val PUBLIC_KEY_PREFIX_BYTES: Int = 6

        fun publicChannel() = ChatFixtures.channel(
            name = ChannelSecret.PUBLIC_CHANNEL_NAME,
            index = ChannelSecret.PUBLIC_CHANNEL_SLOT,
        ).copy(secret = ChannelSecret.publicChannelSecret())

        fun hashtagChannel() = ChatFixtures.channel(name = HASHTAG_NAME, index = HASHTAG_SLOT)
            .copy(secret = ChannelSecret.hashtagChannelSecret(HASHTAG_CLEAN_NAME))

        private const val HASHTAG_CLEAN_NAME: String = "town"
    }
}

private fun assertNoRadio(session: FakeChatSession) {
    assertTrue(session.directSendAttempts.isEmpty())
    assertEquals(0, session.resetPathCalls)
}

private fun assertRoomStayedLocal(session: FakeRoomServerSession) {
    assertTrue(session.pathDiscoveryCalls.isEmpty())
    assertTrue(session.historySyncCalls.isEmpty())
}
