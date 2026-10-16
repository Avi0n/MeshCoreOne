package one.meshcore.shared.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.chat.reactions.ReactionParser
import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.Uuid
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ChatThreadNotificationScrollTest {
    @Test
    fun olderNotificationTargetIsLoadedBeforeItsScrollCommand() = runTest {
        for (isChannel in listOf(false, true)) {
            for (hiddenMiddlePage in listOf(false, true)) {
                val page = ChatCoordinator.PAGE_SIZE
                val rows = incomingThreadRows(page * 2 + 5).mapIndexed { index, row ->
                    val message = if (isChannel) row.copy(contactID = null, channelIndex = 0) else row
                    if (hiddenMiddlePage && index in 5 until page + 5) {
                        message.copy(
                            direction = MessageDirection.OUTGOING,
                            status = MessageStatus.SENT,
                            text = if (isChannel) {
                                ReactionParser.buildReactionText("👍", "Sender", "target", 1)
                            } else {
                                ReactionParser.buildDmReactionText("👍", "target", 1)
                            },
                        )
                    } else {
                        message
                    }
                }
                val pending = PendingIntent(rows.first().id)
                val store = FakeChatThreadStore(rows)
                val conversation = if (isChannel) {
                    ChatConversationID.channel(ChatFixtures.RADIO, 0)
                } else {
                    ChatConversationID.dm(ChatFixtures.RADIO, ChatFixtures.CONTACT)
                }
                val (viewModel, coordinator) = makeThreadViewModel(
                    store = store,
                    conversationID = conversation,
                    pendingScrollRequest = { pending.request },
                    consumePendingScrollRequest = pending::consume,
                )
                viewModel.loadInitial()
                runCurrent()
                assertNotNull(pending.request)
                assertFalse(viewModel.renderState.value.items.any { it.id == rows.first().id })

                viewModel.deliverPendingScrollRequest()
                assertEquals(
                    rows.first().id,
                    assertIs<ScrollCommand.ToMessage>(viewModel.renderState.value.scrollCommand).messageID,
                )
                assertTrue(viewModel.renderState.value.items.any { it.id == rows.first().id })
                assertEquals(listOf(page, page * 2), store.fetchOffsets)
                assertEquals(rows.size, coordinator.renderState.totalFetchedCount)
                assertNull(pending.request)
                assertFalse(viewModel.renderState.value.isAtBottom)
                viewModel.cleanup()
            }
        }
    }

    @Test
    fun loadedTargetWinsOverTheUnreadDividerWithoutPaging() = runTest {
        val rows = incomingThreadRows(20)
        val pending = PendingIntent(rows[3].id)
        val store = FakeChatThreadStore(rows).apply { storedUnreadCount = 5 }
        val (viewModel, _) = makeThreadViewModel(
            store = store,
            pendingScrollRequest = { pending.request },
            consumePendingScrollRequest = pending::consume,
        )
        viewModel.loadInitial()
        viewModel.deliverPendingScrollRequest()
        assertEquals(rows[15].id, viewModel.renderState.value.newMessagesDividerMessageID)
        assertEquals(rows[3].id, assertIs<ScrollCommand.ToMessage>(viewModel.renderState.value.scrollCommand).messageID)
        assertTrue(store.fetchOffsets.isEmpty())
        assertNull(pending.request)
        viewModel.loadInitial()
        runCurrent()
        viewModel.deliverPendingScrollRequest()
        assertNull(viewModel.renderState.value.scrollCommand)
        assertEquals(1, pending.takes)
    }

    @Test
    fun anotherTapOnTheMountedConversationGetsANewCommand() = runTest {
        val rows = incomingThreadRows(20)
        val pending = PendingIntent(rows[3].id)
        val (viewModel, _) = makeThreadViewModel(
            store = FakeChatThreadStore(rows),
            pendingScrollRequest = { pending.request },
            consumePendingScrollRequest = pending::consume,
        )
        viewModel.loadInitial()
        viewModel.deliverPendingScrollRequest()
        val first = assertIs<ScrollCommand.ToMessage>(viewModel.renderState.value.scrollCommand)
        pending.replace(rows[3].id)
        viewModel.deliverPendingScrollRequest()
        val second = assertIs<ScrollCommand.ToMessage>(viewModel.renderState.value.scrollCommand)
        assertEquals(first.messageID, second.messageID)
        assertTrue(second.token > first.token)
        assertEquals(2, pending.takes)
    }

    @Test
    fun aMissingTargetRetiresOnlyAfterTheFinalEmptyPage() = runTest {
        val page = ChatCoordinator.PAGE_SIZE
        val pending = PendingIntent(Uuid.random())
        val store = FakeChatThreadStore(incomingThreadRows(page * 2))
        val (viewModel, _) = makeThreadViewModel(
            store = store,
            pendingScrollRequest = { pending.request },
            consumePendingScrollRequest = pending::consume,
        )
        viewModel.loadInitial()
        viewModel.deliverPendingScrollRequest()
        assertNull(pending.request)
        assertEquals(listOf(page, page * 2), store.fetchOffsets)
        assertNull(viewModel.renderState.value.scrollCommand)
    }

    @Test
    fun unreadyUnavailableAndFailedLoadsKeepTheRequest() = runTest {
        val pending = PendingIntent(Uuid.random())
        val store = FakeChatThreadStore(incomingThreadRows(ChatCoordinator.PAGE_SIZE + 5))
        var available = true
        val (viewModel, coordinator) = makeThreadViewModel(
            store = store,
            storeProvider = { store.takeIf { available } },
            pendingScrollRequest = { pending.request },
            consumePendingScrollRequest = pending::consume,
        )
        viewModel.deliverPendingScrollRequest()
        assertTrue(store.fetchLimits.isEmpty())
        assertNotNull(pending.request)
        viewModel.loadInitial()
        runCurrent()
        available = false
        viewModel.deliverPendingScrollRequest()
        assertNotNull(pending.request)
        assertTrue(store.fetchOffsets.isEmpty())
        available = true
        store.fetchFailure = IllegalStateException("page unavailable")
        val delivery = launch { viewModel.deliverPendingScrollRequest() }
        runCurrent()
        assertNotNull(coordinator.scrollRenderWaiter)
        assertNotNull(pending.request)
        assertNotNull(viewModel.renderState.value.errorBannerMessage)
        assertNull(viewModel.renderState.value.scrollCommand)
        store.fetchFailure = null
        viewModel.loadOlderMessages()
        advanceUntilIdle()
        delivery.join()
        assertNull(pending.request)
        assertNull(viewModel.renderState.value.scrollCommand)
    }

    @Test
    fun aMessageAdmittedDuringAFailedPageIsScrolledWithoutAnotherFetch() = runTest {
        val rows = incomingThreadRows(ChatCoordinator.PAGE_SIZE + 5)
        val pending = PendingIntent(rows.first().id)
        val store = FakeChatThreadStore(rows)
        val (viewModel, coordinator) = makeThreadViewModel(
            store = store,
            pendingScrollRequest = { pending.request },
            consumePendingScrollRequest = pending::consume,
        )
        viewModel.loadInitial()
        runCurrent()
        store.fetchFailure = IllegalStateException("page unavailable")
        val delivery = launch { viewModel.deliverPendingScrollRequest() }
        runCurrent()
        assertNotNull(coordinator.scrollRenderWaiter)
        assertNull(viewModel.renderState.value.scrollCommand)
        val fetchesAtWait = store.fetchOffsets.size
        store.fetchFailure = null
        coordinator.append(rows.first())
        coordinator.updateRenderState { state ->
            state.copy(itemIndexByID = state.itemIndexByID + (rows.first().id to state.items.size))
        }
        advanceUntilIdle()
        delivery.join()
        assertEquals(fetchesAtWait, store.fetchOffsets.size)
        assertNull(pending.request)
        val command = assertIs<ScrollCommand.ToMessage>(viewModel.renderState.value.scrollCommand)
        assertEquals(rows.first().id, command.messageID)
    }

    @Test
    fun cancelledPagingDoesNotConsumeTheRequest() = runTest {
        val rows = incomingThreadRows(ChatCoordinator.PAGE_SIZE + 5)
        val pending = PendingIntent(rows.first().id)
        val gate = CompletableDeferred<Unit>()
        val store = FakeChatThreadStore(rows).apply { beforePageFetch = { gate.await() } }
        val (viewModel, _) = makeThreadViewModel(
            store = store,
            pendingScrollRequest = { pending.request },
            consumePendingScrollRequest = pending::consume,
        )
        viewModel.loadInitial()
        runCurrent()
        val delivery = launch { viewModel.deliverPendingScrollRequest() }
        runCurrent()
        assertEquals(1, store.fetchOffsets.size)
        delivery.cancel()
        runCurrent()
        assertNotNull(pending.request)
        assertNull(viewModel.renderState.value.scrollCommand)
        assertFalse(viewModel.renderState.value.isLoadingOlder)
    }

    @Test
    fun aReplacementOrRepeatedTapCannotBeConsumedByTheOldDelivery() = runTest {
        for (repeatSameMessage in listOf(false, true)) {
            val rows = incomingThreadRows(ChatCoordinator.PAGE_SIZE + 5)
            val pending = PendingIntent(rows.first().id)
            val gate = CompletableDeferred<Unit>()
            val store = FakeChatThreadStore(rows).apply { beforePageFetch = { gate.await() } }
            val (viewModel, _) = makeThreadViewModel(
                store = store,
                pendingScrollRequest = { pending.request },
                consumePendingScrollRequest = pending::consume,
            )
            viewModel.loadInitial()
            runCurrent()
            val delivery = launch { viewModel.deliverPendingScrollRequest() }
            runCurrent()
            val replacement = pending.replace(if (repeatSameMessage) rows.first().id else rows[1].id)
            gate.complete(Unit)
            delivery.join()
            assertEquals(replacement, pending.request)
            assertEquals(0, pending.takes)
            assertNull(viewModel.renderState.value.scrollCommand)
            viewModel.deliverPendingScrollRequest()
            assertNull(pending.request)
            assertEquals(
                replacement.messageID,
                assertIs<ScrollCommand.ToMessage>(viewModel.renderState.value.scrollCommand).messageID,
            )
            viewModel.cleanup()
        }
    }

    @Test
    fun aReplacedWriterCannotConsumeAfterPaging() = runTest {
        val rows = incomingThreadRows(ChatCoordinator.PAGE_SIZE + 5)
        val pending = PendingIntent(rows.first().id)
        val gate = CompletableDeferred<Unit>()
        val store = FakeChatThreadStore(rows).apply { beforePageFetch = { gate.await() } }
        val (viewModel, coordinator) = makeThreadViewModel(
            store = store,
            pendingScrollRequest = { pending.request },
            consumePendingScrollRequest = pending::consume,
        )
        viewModel.loadInitial()
        runCurrent()
        val delivery = launch { viewModel.deliverPendingScrollRequest() }
        runCurrent()
        coordinator.bindWriter(Any(), ChatWriterRole.INTERACTIVE)
        gate.complete(Unit)
        delivery.join()
        assertNotNull(pending.request)
        assertNull(viewModel.renderState.value.scrollCommand)
    }

    @Test
    fun onlyTheLatestInitialLoadCanPublishReadiness() = runTest {
        val rows = incomingThreadRows(20)
        val pending = PendingIntent(rows[3].id)
        val firstGate = CompletableDeferred<Unit>()
        val latestGate = CompletableDeferred<Unit>()
        var calls = 0
        val sink = object : ActiveConversationSink {
            override suspend fun setActive(conversation: ChatConversationID) {
                calls += 1
                if (calls == 1) firstGate.await() else latestGate.await()
            }
            override fun clearActiveAsync(conversation: ChatConversationID) = Unit
        }
        val (viewModel, _) = makeThreadViewModel(
            store = FakeChatThreadStore(rows),
            activeConversation = sink,
            pendingScrollRequest = { pending.request },
            consumePendingScrollRequest = pending::consume,
        )
        val firstLoad = launch { viewModel.loadInitial() }
        runCurrent()
        val latestLoad = launch { viewModel.loadInitial() }
        runCurrent()
        firstGate.complete(Unit)
        firstLoad.join()
        assertNull(viewModel.renderState.value.completedInitialLoadGeneration)
        viewModel.deliverPendingScrollRequest()
        assertNotNull(pending.request)
        latestGate.complete(Unit)
        latestLoad.join()
        viewModel.deliverPendingScrollRequest()
        assertEquals(2uL, viewModel.renderState.value.completedInitialLoadGeneration)
        assertNull(pending.request)
    }

    @Test
    fun aCancelledInitialFetchRestagesTheDividerOnRetry() = runTest {
        val rows = incomingThreadRows(20)
        val gate = CompletableDeferred<Unit>()
        val pending = PendingIntent(rows[3].id)
        val store = FakeChatThreadStore(rows).apply {
            storedUnreadCount = 5
            beforeWindowFetch = { gate.await() }
        }
        val (viewModel, _) = makeThreadViewModel(
            store = store,
            unreadCount = { 5 },
            pendingScrollRequest = { pending.request },
            consumePendingScrollRequest = pending::consume,
        )
        val load = launch { viewModel.loadInitial() }
        runCurrent()
        load.cancel()
        runCurrent()
        assertNull(viewModel.renderState.value.completedInitialLoadGeneration)
        assertFalse(viewModel.initialLoadSettled)
        assertNotNull(pending.request)
        assertNull(viewModel.renderState.value.scrollCommand)
        store.beforeWindowFetch = {}
        viewModel.loadInitial()
        runCurrent()
        assertEquals(rows[15].id, viewModel.renderState.value.newMessagesDividerMessageID)
        assertIs<ChatInitialScrollPolicy.FirstSnapshotDecision.Present>(viewModel.renderState.value.firstSnapshot)
        viewModel.deliverPendingScrollRequest()
        assertEquals(rows[3].id, assertIs<ScrollCommand.ToMessage>(viewModel.renderState.value.scrollCommand).messageID)
        assertNull(pending.request)
    }

    @Test
    fun aWarmReopenWaitsForTheNewDividerBake() = runTest {
        val rows = incomingThreadRows(20)
        val store = FakeChatThreadStore(rows)
        val bakes = ArrayDeque<Runnable>()
        val bakeDispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                bakes.addLast(block)
            }
        }
        fun finishBakes() {
            while (bakes.isNotEmpty()) {
                while (bakes.isNotEmpty()) bakes.removeFirst().run()
                testScheduler.runCurrent()
            }
        }
        val coordinator = ChatCoordinator(
            ChatConversationID.dm(ChatFixtures.RADIO, ChatFixtures.CONTACT),
            store,
            backgroundScope,
            bakeDispatcher,
        )
        val (first, _) = makeThreadViewModel(store = store, coordinator = coordinator)
        first.loadInitial()
        runCurrent()
        finishBakes()
        runCurrent()
        first.cleanup()
        store.storedUnreadCount = 5
        val pending = PendingIntent(rows[3].id)
        val (reopened, _) = makeThreadViewModel(
            store = store,
            coordinator = coordinator,
            unreadCount = { 5 },
            pendingScrollRequest = { pending.request },
            consumePendingScrollRequest = pending::consume,
        )
        reopened.loadInitial()
        runCurrent()
        assertIs<ChatInitialScrollPolicy.FirstSnapshotDecision.Withhold>(reopened.renderState.value.firstSnapshot)
        val delivery = launch { reopened.deliverPendingScrollRequest() }
        runCurrent()
        assertNotNull(pending.request)
        assertNull(reopened.renderState.value.scrollCommand)
        finishBakes()
        runCurrent()
        delivery.join()
        assertNull(pending.request)
        assertIs<ChatInitialScrollPolicy.FirstSnapshotDecision.Present>(reopened.renderState.value.firstSnapshot)
        assertEquals(rows[3].id, assertIs<ScrollCommand.ToMessage>(reopened.renderState.value.scrollCommand).messageID)
    }

    private class PendingIntent(messageID: Uuid) {
        var request: ChatScrollRequest? = ChatScrollRequest(Uuid.random(), "direct:test", messageID)
        var takes = 0
        fun replace(messageID: Uuid): ChatScrollRequest =
            ChatScrollRequest(Uuid.random(), "direct:test", messageID).also {
                request = it
            }
        fun consume(requestID: Uuid): Boolean {
            if (request?.requestID != requestID) return false
            takes += 1
            request = null
            return true
        }
    }
}
