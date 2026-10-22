// PortedFrom: MC1/Views/Chats/ViewModel/ChatViewModel+Messages.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/ViewModel/ChatViewModel+Pagination.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/ViewModel/ChatViewModel+MessageActions.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/Timeline/ChatTimeline+Paging.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Tests/Views/Chats/ChatViewModelAppendRaceTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.chats.timeline

import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageStatus
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Screen-lifetime chat state. It never owns or tears down a radio session. Keep one instance per
 * stable conversation route; [start] is idempotent, so recomposition cannot duplicate event work.
 */
class ChatViewModel(
    val conversation: TimelineConversation,
    private val dependencies: ChatTimelineDependencies,
    private val scope: CoroutineScope,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val prefetchTimeoutMillis: Long = 3_000,
) {
    private val mutableState = MutableStateFlow(
        ChatTimelineState(draft = dependencies.draftStore.get(conversation.id).orEmpty()),
    )
    val state: StateFlow<ChatTimelineState> = mutableState

    private val operationMutex = Mutex()
    private val retryAdmissionMutex = Mutex()
    private var eventJob: Job? = null
    private var unreadAnchorId: UUID? = null
    private var openGeneration = 0L

    fun start() {
        if (eventJob != null) return
        eventJob = scope.launch {
            dependencies.events.collect { event ->
                if (event.conversationId == conversation.id) handleEvent(event)
            }
        }
    }

    suspend fun open() {
        val generation = ++openGeneration
        var didLoad = false
        mutableState.update { it.copy(isLoading = true, loadError = null, passiveError = null) }
        try {
            operationMutex.withLock {
                val limit = initialPageSize(conversation.unreadCount)
                val fetched = dependencies.data.fetchMessages(conversation, limit + 1, 0)
                val page = fetched.takeLast(limit)
                val persistedAnchor = if (conversation.unreadCount > 0) {
                    dependencies.data.unreadAnchorMessageId(conversation)
                } else {
                    null
                }
                if (generation != openGeneration) return@withLock
                val ordered = normalize(page)
                val anchor = persistedAnchor
                    ?.takeIf { candidate -> ordered.any { it.id == candidate } }
                    ?: ordered.firstOrNull()?.id?.takeIf { conversation.unreadCount > 0 }
                unreadAnchorId = anchor
                mutableState.update {
                    it.copy(
                        messages = ordered,
                        rows = buildTimelineRows(ordered, anchor, zoneId),
                        isLoading = false,
                        hasLoadedOnce = true,
                        hasMoreMessages = fetched.size > limit,
                        totalFetchedCount = page.size,
                        initialAnchor = anchor?.let(InitialTimelineAnchor::Message) ?: InitialTimelineAnchor.Latest,
                        initialAnchorConsumed = false,
                    )
                }
                didLoad = true
            }
        } catch (cancellation: CancellationException) {
            if (generation == openGeneration) mutableState.update { it.copy(isLoading = false) }
            throw cancellation
        } catch (failure: Exception) {
            if (generation == openGeneration) {
                mutableState.update { it.copy(isLoading = false, hasLoadedOnce = true, loadError = failure) }
            }
            dependencies.diagnostics.report("open timeline", failure)
        }
        if (generation != openGeneration) return
        currentCoroutineContext().ensureActive()
        if (conversation is TimelineConversation.Channel) {
            try {
                dependencies.syncChannelFloodScope(conversation.channel)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                dependencies.diagnostics.report("set channel flood scope", failure)
            }
        }
        if (!didLoad || generation != openGeneration) return
        try {
            dependencies.data.clearUnread(conversation)
            dependencies.data.markFailedSendsSeen(conversation)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            if (generation == openGeneration) {
                mutableState.update { it.copy(isLoading = false, hasLoadedOnce = true, loadError = failure) }
            }
            dependencies.diagnostics.report("open timeline", failure)
        }
    }

    /**
     * Reconnect refreshes keep the loaded window rather than replacing paged-in history with one page.
     * Open-time unread side effects are intentionally not repeated.
     */
    suspend fun refresh() {
        mutableState.update { it.copy(passiveError = null) }
        try {
            operationMutex.withLock {
                val before = mutableState.value
                val limit = maxOf(
                    before.totalFetchedCount + PAGE_SIZE,
                    initialPageSize(conversation.unreadCount),
                )
                val fetched = dependencies.data.fetchMessages(conversation, limit + 1, 0)
                val page = fetched.takeLast(limit)
                val ordered = normalize(page)
                mutableState.update {
                    it.copy(
                        messages = ordered,
                        rows = buildTimelineRows(ordered, unreadAnchorId, zoneId),
                        hasLoadedOnce = true,
                        hasMoreMessages = fetched.size > limit,
                        totalFetchedCount = page.size,
                    )
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            mutableState.update { it.copy(passiveError = failure) }
            dependencies.diagnostics.report("refresh timeline", failure)
        }
    }

    suspend fun loadOlder() {
        if (mutableState.value.isLoadingOlder || !mutableState.value.hasMoreMessages) return
        mutableState.update { it.copy(isLoadingOlder = true, passiveError = null) }
        try {
            operationMutex.withLock {
                val before = mutableState.value
                if (!before.hasMoreMessages) return@withLock
                val fetched = dependencies.data.fetchMessages(conversation, PAGE_SIZE + 1, before.totalFetchedCount)
                val page = fetched.takeLast(PAGE_SIZE)
                val existingIds = before.messages.asSequence().map(MessageDTO::id).toHashSet()
                val older = page.filterNot { it.id in existingIds }
                val merged = normalize(older + before.messages)
                mutableState.update {
                    it.copy(
                        messages = merged,
                        rows = buildTimelineRows(merged, unreadAnchorId, zoneId),
                        isLoadingOlder = false,
                        hasMoreMessages = fetched.size > PAGE_SIZE,
                        totalFetchedCount = before.totalFetchedCount + page.size,
                    )
                }
            }
        } catch (cancellation: CancellationException) {
            mutableState.update { it.copy(isLoadingOlder = false) }
            throw cancellation
        } catch (failure: Exception) {
            mutableState.update { it.copy(isLoadingOlder = false, passiveError = failure) }
            dependencies.diagnostics.report("load older messages", failure)
        }
    }

    fun consumeInitialAnchor() = mutableState.update { it.copy(initialAnchorConsumed = true) }

    fun updateScrollPosition(anchor: TimelineScrollAnchor?, isAtLatest: Boolean) {
        mutableState.update {
            it.copy(
                scrollAnchor = anchor,
                isAtLatest = isAtLatest,
                newMessageCount = if (isAtLatest) 0 else it.newMessageCount,
            )
        }
    }

    fun jumpToLatest() = mutableState.update {
        it.copy(isAtLatest = true, newMessageCount = 0, scrollAnchor = null)
    }

    fun updateDraft(text: String) {
        mutableState.update { it.copy(draft = text) }
        dependencies.draftStore.set(conversation.id, text.ifEmpty { null })
    }

    fun clearDraftAfterAcceptedSend() = updateDraft("")

    suspend fun retry(messageId: UUID) {
        val message = retryAdmissionMutex.withLock {
            val current = mutableState.value
            val candidate = current.messages.firstOrNull { it.id == messageId }
            if (candidate == null || !candidate.isOutgoing || !candidate.hasFailed ||
                messageId in current.retryingMessageIds
            ) {
                return
            }
            mutableState.update {
                it.copy(
                    retryingMessageIds = it.retryingMessageIds + messageId,
                    sendError = null,
                ).withStatus(messageId, MessageStatus.RETRYING, unreadAnchorId, zoneId)
            }
            candidate
        }
        try {
            dependencies.retryService.retry(message, conversation)
        } catch (cancellation: CancellationException) {
            reconcileRetryStatus(messageId)
            throw cancellation
        } catch (failure: Exception) {
            mutableState.update { it.copy(sendError = failure) }
            dependencies.diagnostics.report("retry message", failure)
            reconcileRetryStatus(messageId)
        } finally {
            mutableState.update { it.copy(retryingMessageIds = it.retryingMessageIds - messageId) }
        }
    }

    private suspend fun reconcileRetryStatus(messageId: UUID) {
        if (mutableState.value.messages.firstOrNull { it.id == messageId }?.status != MessageStatus.RETRYING) return
        // A Room write can commit before caller cancellation or queue signalling fails.
        val persisted = try {
            withContext(NonCancellable) { dependencies.data.fetchMessage(messageId) }
        } catch (failure: PersistenceStoreException) {
            mutableState.update { it.copy(passiveError = failure) }
            dependencies.diagnostics.report("reconcile retry status", failure)
            return
        }
        mutableState.update { current ->
            if (current.messages.firstOrNull { it.id == messageId }?.status != MessageStatus.RETRYING) {
                current
            } else if (persisted != null) {
                current.withStatus(messageId, persisted.status, unreadAnchorId, zoneId, persisted.roundTripTime)
            } else {
                val messages = current.messages.filterNot { it.id == messageId }
                current.copy(
                    messages = messages,
                    rows = buildTimelineRows(messages, unreadAnchorId, zoneId),
                    totalFetchedCount = (current.totalFetchedCount - 1).coerceAtLeast(0),
                )
            }
        }
    }

    private suspend fun handleEvent(event: TimelineEvent) {
        operationMutex.withLock {
            when (event) {
                is TimelineEvent.MessageReceived -> {
                    val message = event.message
                    if (mutableState.value.messages.any { it.id == message.id }) return
                    val identity = dependencies.admissionIdentity()
                    if (!dependencies.isCurrentAdmission(identity)) return
                    if (message.direction == MessageDirection.INCOMING) {
                        prefetch(message, bounded = true)
                        currentCoroutineContext().ensureActive()
                        if (!dependencies.isCurrentAdmission(identity)) return
                        admit(message)
                    } else {
                        admit(message)
                        scope.launch { prefetch(message, bounded = false) }
                    }
                }
                is TimelineEvent.StatusChanged -> mutableState.update {
                    it.withStatus(
                        event.messageId,
                        event.status,
                        unreadAnchorId,
                        zoneId,
                        event.roundTripTime,
                    )
                        .copy(retryingMessageIds = if (event.status == MessageStatus.RETRYING) {
                            it.retryingMessageIds + event.messageId
                        } else {
                            it.retryingMessageIds - event.messageId
                        })
                }
                is TimelineEvent.MessageChanged -> {
                    val refreshed = dependencies.data.fetchMessage(event.messageId) ?: return
                    mutableState.update { current ->
                        if (current.messages.none { it.id == refreshed.id }) return@update current
                        val messages = normalize(current.messages.filterNot { it.id == refreshed.id } + refreshed)
                        current.copy(messages = messages, rows = buildTimelineRows(messages, unreadAnchorId, zoneId))
                    }
                }
            }
        }
    }

    private suspend fun prefetch(message: MessageDTO, bounded: Boolean) {
        val content = dependencies.content ?: return
        if (!content.enabled || !content.containsUrls(message.text)) return
        try {
            if (bounded) {
                withTimeoutOrNull(prefetchTimeoutMillis) {
                    content.prefetch(message.text, message.isChannelMessage)
                }
            } else {
                content.prefetch(message.text, message.isChannelMessage)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            dependencies.diagnostics.report("prefetch message content", failure)
        }
    }

    private fun admit(message: MessageDTO) {
        mutableState.update { current ->
            if (current.messages.any { it.id == message.id }) return@update current
            val messages = current.messages + message
            val shouldBadge = !current.isAtLatest && message.direction == MessageDirection.INCOMING
            current.copy(
                messages = messages,
                rows = buildTimelineRows(messages, unreadAnchorId, zoneId),
                newMessageCount = current.newMessageCount + if (shouldBadge) 1 else 0,
            )
        }
    }

    private fun normalize(messages: List<MessageDTO>): List<MessageDTO> =
        messages.distinctBy(MessageDTO::id).sortedWith(
            compareBy<MessageDTO> { it.sortDate }
                .thenBy { it.timestamp }
                .thenBy { it.createdAt }
                .thenBy { it.id },
        )

    private fun ChatTimelineState.withStatus(
        messageId: UUID,
        status: MessageStatus,
        unreadAnchorId: UUID?,
        zoneId: ZoneId,
        roundTripTime: UInt? = null,
    ): ChatTimelineState {
        val index = messages.indexOfFirst { it.id == messageId }
        if (index < 0) return this
        val current = messages[index]
        if (current.status == MessageStatus.DELIVERED && status != MessageStatus.DELIVERED) return this
        if (current.status == MessageStatus.FAILED && status == MessageStatus.PENDING) return this
        val changed = messages.toMutableList().apply {
            this[index] = current.copy(status = status, roundTripTime = roundTripTime ?: current.roundTripTime)
        }
        return copy(messages = changed, rows = buildTimelineRows(changed, unreadAnchorId, zoneId))
    }

    companion object {
        const val PAGE_SIZE = 50
        const val DIVIDER_READ_CONTEXT = 12
        const val MAX_INITIAL_PAGE_SIZE = 200

        fun initialPageSize(unreadCount: Long): Int =
            maxOf(PAGE_SIZE, (unreadCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt() + DIVIDER_READ_CONTEXT))
                .coerceAtMost(MAX_INITIAL_PAGE_SIZE)
    }
}
