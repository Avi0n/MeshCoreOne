// PortedFrom: MC1Tests/Views/Chats/ChatTimelineTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Tests/Views/Chats/ChatViewModelAppendRaceTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Tests/Views/Chats/ChatViewModelDraftRestoreTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Tests/Views/Chats/ChatViewModelEventGuardTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Tests/Views/Chats/ChatViewModelFailedSendTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.chats.timeline

import com.meshcoreone.android.core.model.ChatConversationID
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import com.meshcoreone.android.feature.chats.list.support.OriginalCase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Test

class ChatViewModelTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun tearDown() = scope.cancel()

    @Test
    @OriginalCase("ChatViewModelTests::Unread divider lands on the first unread row of the recent block()")
    @OriginalCase("ChatTimelineTests::a reopened warm timeline withholds until this session's divider resolves, then anchors on it()")
    fun `open keeps chronological data and anchors the oldest unread message`() = runBlocking {
        val contact = contact(unreadCount = 2)
        val old = message(contact, 1)
        val unread = message(contact, 2)
        val newest = message(contact, 3)
        val data = FakeData(listOf(newest, old, unread), unread.id)
        val viewModel = viewModel(contact, data = data)

        viewModel.open()

        assertEquals(listOf(old.id, unread.id, newest.id), viewModel.state.value.messages.map { it.id })
        assertEquals(InitialTimelineAnchor.Message(unread.id), viewModel.state.value.initialAnchor)
        assertTrue(viewModel.state.value.rows.any { it == TimelineRow.UnreadDivider(unread.id) })
        assertEquals(1, data.clearUnreadCalls)
        assertEquals(1, data.markFailedSeenCalls)
    }

    @Test
    @OriginalCase("ChatViewModelTests::Divider absent when there are no unread messages()")
    @OriginalCase("ChatTimelineTests::a staged open with no unread presents at the bottom immediately()")
    fun `zero unread opens at latest and never asks for an unread anchor`() = runBlocking {
        val contact = contact()
        val data = FakeData(listOf(message(contact, 1)))
        val viewModel = viewModel(contact, data = data)

        viewModel.open()

        assertEquals(InitialTimelineAnchor.Latest, viewModel.state.value.initialAnchor)
        assertEquals(0, data.unreadAnchorCalls)
    }

    @Test
    @OriginalCase("ChatTimelineTests::open of exactly one page reports no further history()")
    fun `short initial page closes paging`() = runBlocking {
        val contact = contact()
        val viewModel = viewModel(contact, data = FakeData(List(3) { message(contact, it) }))

        viewModel.open()

        assertFalse(viewModel.state.value.hasMoreMessages)
        assertEquals(3, viewModel.state.value.totalFetchedCount)
    }

    @Test
    @OriginalCase("ChatTimelineTests::loadOlder prepends the older page in order and ends history on a short page()")
    @OriginalCase("ChatViewModelDisplayItemsPaginationTests::Message lookup by ID works after pagination()")
    fun `older page prepends in order and preserves scroll anchor`() = runBlocking {
        val contact = contact()
        val latest = List(ChatViewModel.PAGE_SIZE) { message(contact, it + 10) }
        val older = List(10) { message(contact, it) }
        val data = FakeData(latest + older)
        val viewModel = viewModel(contact, data = data)
        viewModel.open()
        val visible = latest[20]
        viewModel.updateScrollPosition(TimelineScrollAnchor(visible.id, 17), isAtLatest = false)

        viewModel.loadOlder()

        assertEquals((0 until 60).toList(), viewModel.state.value.messages.map { it.timestamp.toInt() })
        assertEquals(TimelineScrollAnchor(visible.id, 17), viewModel.state.value.scrollAnchor)
        assertFalse(viewModel.state.value.hasMoreMessages)
        assertEquals(listOf(0, ChatViewModel.PAGE_SIZE), data.offsets)
    }

    @Test
    @OriginalCase("ChatTimelineTests::loadOlder drops rows an in-flight admission already landed()")
    @OriginalCase("ChatViewModelAppendRaceTests::appendMessageIfNew skips a message already present in messagesByID()")
    fun `paging drops a row already admitted while the database page was pending`() = runBlocking {
        val contact = contact()
        val first = List(ChatViewModel.PAGE_SIZE) { message(contact, it + 10) }
        val raced = message(contact, 2)
        val data = FakeData(first + List(10) { if (it == 2) raced else message(contact, it) })
        val events = MutableSharedFlow<TimelineEvent>(extraBufferCapacity = 4)
        val viewModel = viewModel(contact, data = data, events = events)
        viewModel.start()
        viewModel.open()
        events.emit(TimelineEvent.MessageReceived(viewModel.conversation.id, raced))
        yield()

        viewModel.loadOlder()

        assertEquals(1, viewModel.state.value.messages.count { it.id == raced.id })
    }

    @Test
    fun `queued older request retires spinner when refresh closes paging`() = runBlocking {
        val contact = contact()
        val data = FakeData(List(ChatViewModel.PAGE_SIZE + 1) { message(contact, it) })
        val viewModel = viewModel(contact, data = data)
        viewModel.open()
        assertTrue(viewModel.state.value.hasMoreMessages)
        val anchor = TimelineScrollAnchor(viewModel.state.value.messages.first().id, 17)
        viewModel.updateScrollPosition(anchor, isAtLatest = false)
        val refreshEntered = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        data.beforeFetch = { call, _ ->
            if (call == 2) {
                refreshEntered.complete(Unit)
                releaseRefresh.await()
            }
        }

        val refresh = scope.launch { viewModel.refresh() }
        refreshEntered.await()
        val older = scope.launch { viewModel.loadOlder() }
        assertTrue(viewModel.state.value.isLoadingOlder)
        assertFalse(older.isCompleted)
        releaseRefresh.complete(Unit)
        refresh.join()
        older.join()

        assertFalse(viewModel.state.value.isLoadingOlder)
        assertFalse(viewModel.state.value.hasMoreMessages)
        assertEquals(data.messages.map { it.id }, viewModel.state.value.messages.map { it.id })
        assertEquals(data.messages.size, viewModel.state.value.totalFetchedCount)
        assertEquals(anchor, viewModel.state.value.scrollAnchor)
        assertNull(viewModel.state.value.passiveError)
        assertEquals(listOf(0, 0), data.offsets)
        assertEquals(2, data.fetchCalls)
        assertEquals(1, data.clearUnreadCalls)
        assertEquals(1, data.markFailedSeenCalls)
    }

    @Test
    @OriginalCase("ChatViewModelEventGuardTests::messageStatusResolved with no coordinator is a no-op()")
    fun `events for another stable conversation are ignored`() = runBlocking {
        val contact = contact()
        val events = MutableSharedFlow<TimelineEvent>(extraBufferCapacity = 4)
        val viewModel = viewModel(contact, events = events)
        viewModel.start()
        yield()

        events.emit(TimelineEvent.MessageReceived(ChatConversationID.dm(contact.radioId, UUID.randomUUID()), message(contact, 1)))
        yield()

        assertTrue(viewModel.state.value.messages.isEmpty())
    }

    @Test
    @OriginalCase("ChatTimelineTests::admit dedupes a message already in the loaded window()")
    fun `start is idempotent and duplicate events admit once`() = runBlocking {
        val contact = contact()
        val events = MutableSharedFlow<TimelineEvent>(extraBufferCapacity = 4)
        val viewModel = viewModel(contact, events = events)
        viewModel.start()
        viewModel.start()
        yield()
        val message = message(contact, 1)

        events.emit(TimelineEvent.MessageReceived(viewModel.conversation.id, message))
        events.emit(TimelineEvent.MessageReceived(viewModel.conversation.id, message))
        yield()

        assertEquals(listOf(message.id), viewModel.state.value.messages.map { it.id })
    }

    @Test
    @OriginalCase("ChatTiledViewScrollRequestTests::incoming append while scrolled up raises unread and stays put()")
    fun `incoming arrival while scrolled away increments badge without moving anchor`() = runBlocking {
        val contact = contact()
        val events = MutableSharedFlow<TimelineEvent>(extraBufferCapacity = 4)
        val viewModel = viewModel(contact, events = events)
        viewModel.start()
        viewModel.updateScrollPosition(TimelineScrollAnchor(UUID.randomUUID(), 9), isAtLatest = false)
        yield()

        events.emit(TimelineEvent.MessageReceived(viewModel.conversation.id, message(contact, 1)))
        yield()

        assertEquals(1, viewModel.state.value.newMessageCount)
        assertEquals(9, viewModel.state.value.scrollAnchor?.offset)
    }

    @Test
    @OriginalCase("ChatTiledViewScrollRequestTests::scroll to bottom then send still pins the new last row()")
    fun `jump to latest clears badge and anchor`() {
        val contact = contact()
        val viewModel = viewModel(contact)
        viewModel.updateScrollPosition(TimelineScrollAnchor(UUID.randomUUID(), 5), isAtLatest = false)
        viewModel.jumpToLatest()

        assertTrue(viewModel.state.value.isAtLatest)
        assertEquals(0, viewModel.state.value.newMessageCount)
        assertNull(viewModel.state.value.scrollAnchor)
    }

    @Test
    @OriginalCase("ChatViewModelEventGuardTests::messageStatusResolved applies status in place via the coordinator()")
    fun `status event updates the existing row in place`() = runBlocking {
        val contact = contact()
        val sent = message(contact, 1, direction = MessageDirection.OUTGOING, status = MessageStatus.SENT)
        val events = MutableSharedFlow<TimelineEvent>(extraBufferCapacity = 4)
        val viewModel = viewModel(contact, data = FakeData(listOf(sent)), events = events)
        viewModel.start()
        viewModel.open()

        events.emit(TimelineEvent.StatusChanged(viewModel.conversation.id, sent.id, MessageStatus.DELIVERED))
        yield()

        assertEquals(MessageStatus.DELIVERED, viewModel.state.value.messages.single().status)
        assertEquals(sent.id, viewModel.state.value.messages.single().id)
    }

    @Test
    @OriginalCase("ChatViewModelFailedSendTests::messageFailed and peers always refresh()")
    fun `retry is single flight and later status clears retrying state`() = runBlocking {
        val contact = contact()
        val failed = message(contact, 1, direction = MessageDirection.OUTGOING, status = MessageStatus.FAILED)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val retry = TimelineRetryService { _, _ ->
            calls++
            entered.complete(Unit)
            release.await()
        }
        val events = MutableSharedFlow<TimelineEvent>(extraBufferCapacity = 4)
        val viewModel = viewModel(contact, data = FakeData(listOf(failed)), events = events, retry = retry)
        viewModel.start()
        viewModel.open()

        val first = scope.launch { viewModel.retry(failed.id) }
        entered.await()
        viewModel.retry(failed.id)
        release.complete(Unit)
        first.join()
        events.emit(TimelineEvent.StatusChanged(viewModel.conversation.id, failed.id, MessageStatus.SENT))
        yield()

        assertEquals(1, calls)
        assertEquals(MessageStatus.SENT, viewModel.state.value.messages.single().status)
        assertTrue(viewModel.state.value.retryingMessageIds.isEmpty())
    }

    @Test
    @OriginalCase("ChatViewModelFailedSendTests::recordLocalEnqueueFailure marks the current contact seen()")
    fun `retry failure restores failed state and exposes send error`() = runBlocking {
        val contact = contact()
        val failed = message(contact, 1, direction = MessageDirection.OUTGOING, status = MessageStatus.FAILED)
        val failure = IllegalStateException("offline")
        val viewModel = viewModel(
            contact,
            data = FakeData(listOf(failed)),
            retry = TimelineRetryService { _, _ -> throw failure },
        )
        viewModel.open()

        viewModel.retry(failed.id)

        assertEquals(MessageStatus.FAILED, viewModel.state.value.messages.single().status)
        assertEquals(failure, viewModel.state.value.sendError)
        assertTrue(viewModel.state.value.retryingMessageIds.isEmpty())
    }

    @Test
    fun `post commit signal failure retains pending state and exposes the original error`() = runBlocking {
        val contact = contact()
        val failed = message(contact, 1, direction = MessageDirection.OUTGOING, status = MessageStatus.FAILED)
        val data = FakeData(listOf(failed))
        val failure = IllegalStateException("queue closed after commit")
        val viewModel = viewModel(contact, data = data, retry = TimelineRetryService { _, _ ->
            data.messages[0] = failed.copy(status = MessageStatus.PENDING)
            throw failure
        })
        viewModel.open()
        viewModel.retry(failed.id)
        assertEquals(MessageStatus.PENDING, viewModel.state.value.messages.single().status)
        assertSame(failure, viewModel.state.value.sendError)
        assertTrue(viewModel.state.value.retryingMessageIds.isEmpty())
    }

    @Test
    fun `post commit cancellation retains pending state and propagates the same cancellation`() = runBlocking {
        val contact = contact()
        val failed = message(contact, 1, direction = MessageDirection.OUTGOING, status = MessageStatus.FAILED)
        val data = FakeData(listOf(failed))
        val cancellation = CancellationException("caller cancelled after commit")
        val viewModel = viewModel(contact, data = data, retry = TimelineRetryService { _, _ ->
            data.messages[0] = failed.copy(status = MessageStatus.PENDING)
            throw cancellation
        })
        viewModel.open()
        assertSame(cancellation, assertFailsWith<CancellationException> { viewModel.retry(failed.id) })
        assertEquals(MessageStatus.PENDING, viewModel.state.value.messages.single().status)
        assertNull(viewModel.state.value.sendError)
        assertTrue(viewModel.state.value.retryingMessageIds.isEmpty())
    }

    @Test
    @OriginalCase("ChatViewModelEventGuardTests::late retry failure does not clobber delivered status()")
    fun `late retry failure cannot clobber a delivered acknowledgement`() = runBlocking {
        val contact = contact()
        val failed = message(contact, 1, direction = MessageDirection.OUTGOING, status = MessageStatus.FAILED)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val events = MutableSharedFlow<TimelineEvent>(extraBufferCapacity = 4)
        val retryFailure = IllegalStateException("late transport failure")
        val viewModel = viewModel(
            contact,
            data = FakeData(listOf(failed)),
            events = events,
            retry = TimelineRetryService { _, _ ->
                entered.complete(Unit)
                release.await()
                throw retryFailure
            },
        )
        viewModel.start()
        viewModel.open()

        val retryJob = scope.launch { viewModel.retry(failed.id) }
        entered.await()
        events.emit(TimelineEvent.StatusChanged(viewModel.conversation.id, failed.id, MessageStatus.DELIVERED))
        yield()
        release.complete(Unit)
        retryJob.join()

        assertEquals(MessageStatus.DELIVERED, viewModel.state.value.messages.single().status)
        assertEquals(retryFailure, viewModel.state.value.sendError)
    }

    @Test
    @OriginalCase("ChatViewModelDraftRestoreTests::persisted draft is restored for DM()")
    @OriginalCase("ChatViewModelDraftRestoreTests::empty draft is removed instead of stored()")
    fun `draft survives holder recreation and empty draft removes storage`() {
        val contact = contact()
        val drafts = MemoryDrafts()
        val first = viewModel(contact, drafts = drafts)
        first.updateDraft("do not lose me")

        val recreated = viewModel(contact, drafts = drafts)
        assertEquals("do not lose me", recreated.state.value.draft)

        recreated.clearDraftAfterAcceptedSend()
        assertNull(drafts.get(recreated.conversation.id))
    }

    @Test
    @OriginalCase("ChatTimelineTests::loadOlder retires the spinner and bakes the prepended rows()")
    fun `failed paging keeps loaded messages and reports passive error`() = runBlocking {
        val contact = contact()
        val all = List(ChatViewModel.PAGE_SIZE + 1) { message(contact, it) }
        val first = all.takeLast(ChatViewModel.PAGE_SIZE)
        val data = FakeData(all).apply { failAtOffset = ChatViewModel.PAGE_SIZE }
        val viewModel = viewModel(contact, data = data)
        viewModel.open()

        viewModel.loadOlder()

        assertEquals(first.map { it.id }, viewModel.state.value.messages.map { it.id })
        assertIs<IllegalStateException>(viewModel.state.value.passiveError)
        assertFalse(viewModel.state.value.isLoadingOlder)
    }

    @Test
    @OriginalCase("ChatViewModelChannelPaginationTests::Opening a channel with more unread than one page loads the divider target()")
    @OriginalCase("ChatViewModelChannelPaginationTests::Opening a channel with unread past the initial-page cap loads the newest window only()")
    fun `initial page grows for unread context and caps at two hundred`() {
        assertEquals(50, ChatViewModel.initialPageSize(0))
        assertEquals(62, ChatViewModel.initialPageSize(50))
        assertEquals(200, ChatViewModel.initialPageSize(500))
    }

    @Test
    @OriginalCase("ChatViewModelTests::Divider clamps to the oldest loaded row when unread exceeds the first page()")
    fun `missing unread anchor clamps to oldest loaded row`() = runBlocking {
        val contact = contact(unreadCount = 500)
        val messages = List(ChatViewModel.MAX_INITIAL_PAGE_SIZE) { message(contact, it) }
        val viewModel = viewModel(contact, data = FakeData(messages, UUID.randomUUID()))

        viewModel.open()

        assertEquals(
            InitialTimelineAnchor.Message(messages.first().id),
            viewModel.state.value.initialAnchor,
        )
    }

    @Test
    @OriginalCase("ChatReconnectPopulateTests::refreshWindow keeps the paged-in window()")
    fun `refresh keeps paged history and admits newer rows`() = runBlocking {
        val contact = contact()
        val initial = MutableList(60) { message(contact, it) }
        val data = FakeData(initial)
        val viewModel = viewModel(contact, data = data)
        viewModel.open()
        viewModel.loadOlder()
        val newest = message(contact, 60)
        data.messages += newest

        viewModel.refresh()

        assertEquals((0..60).toList(), viewModel.state.value.messages.map { it.timestamp.toInt() })
    }

    @Test
    @OriginalCase("ChatTimelineClobberRegressionTests::a stale prime view model cannot bake its cold state over the live timeline()")
    fun `superseded open cannot clear unread or replace the newer result`() = runBlocking {
        val contact = contact()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val firstPage = listOf(message(contact, 1))
        val secondPage = listOf(message(contact, 2))
        val data = FakeData().apply {
            scriptedPages.addLast(firstPage)
            scriptedPages.addLast(secondPage)
            beforeFetch = { call, _ ->
                if (call == 1) {
                    firstEntered.complete(Unit)
                    releaseFirst.await()
                }
            }
        }
        val viewModel = viewModel(contact, data = data)
        val first = scope.launch { viewModel.open() }
        firstEntered.await()
        val second = scope.launch { viewModel.open() }
        releaseFirst.complete(Unit)
        first.join()
        second.join()

        assertEquals(listOf(2), viewModel.state.value.messages.map { it.timestamp.toInt() })
        assertEquals(1, data.clearUnreadCalls)
        assertEquals(1, data.markFailedSeenCalls)
    }

    @Test
    @OriginalCase("ChatViewModelEventGuardTests::messageStatusResolved does not downgrade .delivered to .sent()")
    fun `delivered status does not downgrade and round trip time is retained`() = runBlocking {
        val contact = contact()
        val delivered = message(contact, 1, MessageDirection.OUTGOING, MessageStatus.DELIVERED)
            .copy(roundTripTime = 10u)
        val events = MutableSharedFlow<TimelineEvent>(extraBufferCapacity = 4)
        val viewModel = viewModel(contact, FakeData(listOf(delivered)), events)
        viewModel.start()
        viewModel.open()

        events.emit(TimelineEvent.StatusChanged(viewModel.conversation.id, delivered.id, MessageStatus.SENT, 20u))
        yield()

        assertEquals(MessageStatus.DELIVERED, viewModel.state.value.messages.single().status)
        assertEquals(10u, viewModel.state.value.messages.single().roundTripTime)
    }

    @Test
    @OriginalCase("ChatViewModelReloadSerializationTests::deleted row does not reappear on stale reload()")
    fun `message changed event does not admit an unloaded row`() = runBlocking {
        val contact = contact()
        val hidden = message(contact, 9)
        val events = MutableSharedFlow<TimelineEvent>(extraBufferCapacity = 4)
        val viewModel = viewModel(contact, FakeData(listOf(hidden)), events)
        viewModel.start()
        yield()

        events.emit(TimelineEvent.MessageChanged(viewModel.conversation.id, hidden.id))
        yield()

        assertTrue(viewModel.state.value.messages.isEmpty())
    }

    private fun viewModel(
        contact: ContactDTO,
        data: FakeData = FakeData(),
        events: MutableSharedFlow<TimelineEvent> = MutableSharedFlow(extraBufferCapacity = 4),
        retry: TimelineRetryService = TimelineRetryService { _, _ -> },
        drafts: MemoryDrafts = MemoryDrafts(),
    ) = ChatViewModel(
        TimelineConversation.Direct(contact),
        Dependencies(data, retry, drafts, events),
        scope,
        ZoneOffset.UTC,
    )

    private class Dependencies(
        override val data: TimelineDataSource,
        override val retryService: TimelineRetryService,
        override val draftStore: TimelineDraftStore,
        override val events: MutableSharedFlow<TimelineEvent>,
    ) : ChatTimelineDependencies

    private class MemoryDrafts : TimelineDraftStore {
        private val values = mutableMapOf<ChatConversationID, String>()
        override fun get(conversationId: ChatConversationID) = values[conversationId]
        override fun set(conversationId: ChatConversationID, text: String?) {
            if (text == null) values.remove(conversationId) else values[conversationId] = text
        }
    }

    private class FakeData(
        all: List<MessageDTO> = emptyList(),
        private val unreadAnchor: UUID? = null,
    ) : TimelineDataSource {
        val messages = all.toMutableList()
        val offsets = mutableListOf<Int>()
        var unreadAnchorCalls = 0
        var clearUnreadCalls = 0
        var markFailedSeenCalls = 0
        var failAtOffset: Int? = null
        var fetchCalls = 0
        var beforeFetch: suspend (call: Int, offset: Int) -> Unit = { _, _ -> }
        val scriptedPages = ArrayDeque<List<MessageDTO>>()

        override suspend fun fetchMessages(conversation: TimelineConversation, limit: Int, offset: Int): List<MessageDTO> {
            fetchCalls++
            beforeFetch(fetchCalls, offset)
            offsets += offset
            if (failAtOffset == offset) throw IllegalStateException("database failed")
            if (scriptedPages.isNotEmpty()) return scriptedPages.removeFirst()
            val chronological = messages.sortedBy { it.sortDate }
            val endExclusive = (chronological.size - offset).coerceAtLeast(0)
            val start = (endExclusive - limit).coerceAtLeast(0)
            return chronological.subList(start, endExclusive)
        }

        override suspend fun fetchMessage(messageId: UUID) = messages.firstOrNull { it.id == messageId }
        override suspend fun unreadAnchorMessageId(conversation: TimelineConversation): UUID? {
            unreadAnchorCalls++
            return unreadAnchor
        }
        override suspend fun clearUnread(conversation: TimelineConversation) {
            clearUnreadCalls++
        }
        override suspend fun markFailedSendsSeen(conversation: TimelineConversation) {
            markFailedSeenCalls++
        }
    }

    private companion object {
        val radioId = RadioId(UUID.fromString("10000000-0000-0000-0000-000000000001"))

        fun contact(unreadCount: Long = 0) = ContactDTO(
            id = UUID.fromString("20000000-0000-0000-0000-000000000001"),
            radioId = radioId,
            publicKey = Bytes(ByteArray(32) { 1 }),
            name = "Alice",
            lastHeardTimestamp = null,
            unreadCount = unreadCount,
        )

        fun message(
            contact: ContactDTO,
            timestamp: Int,
            direction: MessageDirection = MessageDirection.INCOMING,
            status: MessageStatus = MessageStatus.DELIVERED,
        ) = MessageDTO(
            id = UUID.nameUUIDFromBytes("message-$timestamp-$direction".toByteArray()),
            radioId = contact.radioId,
            contactID = contact.id,
            text = "message $timestamp",
            timestamp = timestamp.toUInt(),
            createdAt = Instant.ofEpochSecond(timestamp.toLong()),
            sortDate = Instant.ofEpochSecond(timestamp.toLong()),
            direction = direction,
            status = status,
        )
    }
}
