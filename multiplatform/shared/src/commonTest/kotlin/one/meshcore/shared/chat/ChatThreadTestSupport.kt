package one.meshcore.shared.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.chat.linkpreview.LinkPreviewCache
import one.meshcore.shared.chat.reactions.ReactionService
import one.meshcore.shared.map.MapSnapshotStore
import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.urlsafety.MalwareDomainFilter

/** A sender resolver that reads the message's own sender name, defaulting outgoing rows to "Me". */
internal val TEST_SENDER_RESOLVER = SenderNameResolver { message ->
    val name = message.senderNodeName ?: if (message.isOutgoing) "Me" else "Them"
    NodeNameResolution(displayName = name, matchKind = NodeNameMatchKind.EXACT)
}

/** Incoming contact rows named row-0 upward, one second apart, so a page test can vary only the count. */
internal fun incomingThreadRows(count: Int): List<MessageDto> = (0 until count).map { index ->
    ChatFixtures.message(
        text = "row-$index",
        timestamp = 1_000L + index,
        createdAt = 1_000.0 + index,
        direction = MessageDirection.INCOMING,
        contactID = ChatFixtures.CONTACT,
    )
}

/**
 * In-memory [ChatThreadStore] for the thread view-model tests. Pages are served from a backing chronological
 * list with honest limit/offset math (newest last), so the pagination-window and anchoring assertions
 * exercise the same offset arithmetic the Room adapter will. Records the mark-read and delete calls the
 * tests assert on.
 */
internal class FakeChatThreadStore(
    /** Backing rows, chronological (oldest first). Pagination slices from the newest end backward by offset. */
    var rows: List<MessageDto> = emptyList(),
) : ChatThreadStore {
    var clearUnreadCalls = 0
        private set

    /** When set, [clearUnread] throws this instead of recording the call. */
    var clearUnreadFailure: Throwable? = null
    var markFailedSendsSeenCalls = 0
    var recomputeLastMessageCalls = 0
        private set
    val deletedIds = mutableListOf<Uuid>()

    /** Raw storage text a delete throws instead of succeeding, standing in for an untyped SQLite failure. */
    var deleteFailureText: String? = null

    /** When set, [deleteMessage] throws this before the raw-text failure. */
    var deleteFailure: Throwable? = null

    /** When set, a page fetch throws this instead of returning rows. */
    var fetchFailure: Throwable? = null
    val failedIds = mutableListOf<Uuid>()
    val retriedPendingSends = mutableListOf<PendingSendDto>()

    /** The row limit of every page fetch, in order; the first entry is the initial page's size. */
    val fetchLimits = mutableListOf<Int>()
    val fetchOffsets = mutableListOf<Int>()
    var beforePageFetch: suspend () -> Unit = {}
    var beforeWindowFetch: suspend () -> Unit = {}

    /** The stored unread count the divider bake reads; null makes the caller fall back to the DTO snapshot. */
    var storedUnreadCount: Int? = null

    override suspend fun fetchUnreadCount(conversation: ChatConversationID): Int? = storedUnreadCount

    /** Extra lookup when [rows] has not been seeded (send-queue tests keep the DTO in another store). */
    var extraFetch: (suspend (Uuid) -> MessageDto?)? = null

    override suspend fun fetchMessage(id: Uuid): MessageDto? =
        rows.firstOrNull { it.id == id } ?: extraFetch?.invoke(id)

    override suspend fun fetchDirectMessages(contactID: Uuid, limit: Int, offset: Int): List<MessageDto> =
        page(limit, offset)

    override suspend fun fetchChannelMessages(
        radioID: Uuid,
        channelIndex: Int,
        limit: Int,
        offset: Int,
    ): List<MessageDto> = page(limit, offset)

    override suspend fun fetchMessageWindow(
        conversation: ChatConversationID,
        anchorSortDate: Double?,
        floorLimit: Int,
    ): MessageWindow {
        beforeWindowFetch()
        fetchFailure?.let { throw it }
        val limit = if (anchorSortDate == null) {
            floorLimit
        } else {
            maxOf(floorLimit, rows.count { it.sortDate >= anchorSortDate })
        }
        fetchLimits += limit
        val hasMore = rows.size > limit
        val end = rows.size
        val start = (end - limit).coerceIn(0, rows.size)
        return MessageWindow(messages = rows.subList(start, end), hasMore = hasMore)
    }

    /**
     * The [limit] rows [offset] back from the newest, returned in chronological order (oldest first) — the
     * shape the coordinator prepends. Offset 0 is the newest page; a later offset walks toward the oldest.
     */
    private suspend fun page(limit: Int, offset: Int): List<MessageDto> {
        fetchOffsets += offset
        beforePageFetch()
        fetchFailure?.let { throw it }
        fetchLimits += limit
        val end = (rows.size - offset).coerceIn(0, rows.size)
        val start = (end - limit).coerceIn(0, rows.size)
        return rows.subList(start, end)
    }

    override suspend fun deleteMessage(id: Uuid) {
        deleteFailure?.let { throw it }
        deleteFailureText?.let { error(it) }
        deletedIds += id
        rows = rows.filterNot { it.id == id }
    }

    override suspend fun recomputeContactLastMessageDate(contactID: Uuid) {
        recomputeLastMessageCalls += 1
    }

    override suspend fun clearUnread(conversation: ChatConversationID) {
        clearUnreadFailure?.let { throw it }
        clearUnreadCalls += 1
    }

    /** When set, [markFailedSendsSeen] throws this instead of recording the call. */
    var markFailedSendsSeenFailure: Throwable? = null

    override suspend fun markFailedSendsSeen(conversation: ChatConversationID) {
        markFailedSendsSeenFailure?.let { throw it }
        markFailedSendsSeenCalls += 1
    }

    val markedMentionIds = mutableListOf<Uuid>()
    var markMentionSeenFailure: Throwable? = null
    var markMessageFailedFailure: Throwable? = null

    override suspend fun markMentionSeen(id: Uuid) {
        markMentionSeenFailure?.let { throw it }
        markedMentionIds += id
        rows = rows.map { if (it.id == id) it.copy(mentionSeen = true) else it }
    }

    override suspend fun markConversationMentionsSeen(conversation: ChatConversationID): List<Uuid> {
        val unseen = rows.filter { it.containsSelfMention && !it.mentionSeen }.map { it.id }
        unseen.forEach { markMentionSeen(it) }
        return unseen
    }

    override suspend fun markMessageFailedUnlessDelivered(id: Uuid): Boolean {
        markMessageFailedFailure?.let { throw it }
        failedIds += id
        return true
    }

    override suspend fun replacePendingSendForRetry(dto: PendingSendDto): Int {
        retriedPendingSends += dto
        return 1
    }
}

/**
 * Builds a [ChatThreadViewModel] confined to the test scheduler, sharing a coordinator built on the same
 * dispatcher. Overrides cover the dependencies each test drives; the rest default to the empty/ready state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LongParameterList")
internal fun TestScope.makeThreadViewModel(
    store: FakeChatThreadStore = FakeChatThreadStore(),
    storeProvider: () -> ChatThreadStore? = { store },
    conversationID: ChatConversationID = ChatConversationID.dm(ChatFixtures.RADIO, ChatFixtures.CONTACT),
    unreadCount: () -> Int = { 0 },
    isReady: () -> Boolean = { true },
    currentContact: () -> ContactDto? = { null },
    messageService: () -> MessageService? = { null },
    sendQueue: () -> ChatSendQueueService? = { null },
    envInputs: () -> EnvInputs = { EnvInputs(currentUserName = "Me", showIncomingSendTime = false) },
    activeConversation: ActiveConversationSink = ActiveConversationSink.NONE,
    linkPreviewCache: () -> LinkPreviewCache? = { null },
    malwareFilter: () -> MalwareDomainFilter? = { null },
    reactionService: () -> ReactionService? = { null },
    pendingScrollRequest: () -> ChatScrollRequest? = { null },
    consumePendingScrollRequest: (Uuid) -> Boolean = { false },
    indexPageForReactions: suspend (List<MessageDto>) -> Unit = {},
    onConversationsChanged: suspend () -> Unit = {},
    mapSnapshotStore: () -> MapSnapshotStore? = { null },
    inlineImageDimensions: () -> InlineImageDimensionsStore? = { null },
    errorMessages: () -> ChatErrorMessages = { ChatErrorMessages.developerEnglish() },
    /** Reuse a coordinator to simulate a registry re-open of a closed chat. */
    coordinator: ChatCoordinator? = null,
    /** The scope standing in for the composable's own; a teardown test cancels it the way Compose does. */
    viewModelScope: CoroutineScope = backgroundScope,
): Pair<ChatThreadViewModel, ChatCoordinator> {
    val coordinator = coordinator ?: ChatCoordinator(
        conversationID = conversationID,
        store = store,
        scope = backgroundScope,
        computeDispatcher = StandardTestDispatcher(testScheduler),
    )
    val viewModel = ChatThreadViewModel(
        conversationID = conversationID,
        coordinator = coordinator,
        scope = viewModelScope,
        dependencies = ChatThreadViewModel.Dependencies(
            errorMessages = errorMessages,
            store = storeProvider,
            sendQueue = sendQueue,
            messageService = messageService,
            currentContact = currentContact,
            unreadCount = unreadCount,
            isReady = isReady,
            envInputs = envInputs,
            resolveSender = TEST_SENDER_RESOLVER,
            activeConversation = activeConversation,
            linkPreviewCache = linkPreviewCache,
            malwareFilter = malwareFilter,
            reactionService = reactionService,
            pendingScrollRequest = pendingScrollRequest,
            consumePendingScrollRequest = consumePendingScrollRequest,
            indexPageForReactions = indexPageForReactions,
            onConversationsChanged = onConversationsChanged,
            mapSnapshotStore = mapSnapshotStore,
            inlineImageDimensions = inlineImageDimensions,
        ),
    )
    return viewModel to coordinator
}
