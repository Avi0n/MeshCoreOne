package one.meshcore.shared.chat

import kotlinx.coroutines.CancellationException
import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.runReportingException

/**
 * Loads the first page for the conversation: claim the active slot, fetch a window sized to the
 * unread backlog ([ChatCoordinator.initialPageSize]), place the divider on the UNFILTERED page
 * (before reaction filtering), `replaceAll`, rebuild items, then clear unread.
 * Mirrors `loadMessages`/`loadChannelMessages`.
 *
 * Fetch local page-0 before entering LOADING so already-persisted messages never flash a
 * full-screen progress indicator. A closed conversation's warm tail is kept current by
 * [ChatPrewarmRefresher]. A reconnect uses [ChatPopulateMode.REFRESH_WINDOW] so paged-in
 * history stays, matching iOS `performInitialLoad`.
 */
suspend fun ChatThreadViewModel.loadInitial(populateMode: ChatPopulateMode = ChatPopulateMode.REFRESH_WINDOW) {
    loadGeneration += 1u
    val generation = loadGeneration
    completedInitialLoadGeneration = null
    scrollCommand = null
    errorMessage = null
    publish()
    dependencies.activeConversation.setActive(conversationID)
    if (!isCurrentLoad(generation)) return
    val refreshWindow = populateMode == ChatPopulateMode.REFRESH_WINDOW &&
        coordinator.renderState.phase == ChatRenderState.LoadPhase.LOADED &&
        coordinator.messages.isNotEmpty()
    val firstOpen = stageInitialOpen()
    writer?.updateRenderState { it.copy(isLoadingOlder = false) }

    val store = dependencies.store() ?: run {
        if (firstOpen) initialLoadSettled = true
        writer?.markLoaded()
        completedInitialLoadGeneration = generation
        publish()
        return
    }
    var didComplete = false
    try {
        runReportingException<Unit>(
            onFailure = { error ->
                if (!isCurrentLoad(generation)) return@runReportingException
                errorMessage = messages.userFacing(error, messages.loadMessagesFailed)
                // Only show a loading chrome when the fetch failed and we still have nothing to show.
                if (coordinator.renderState.items.isEmpty()) {
                    writer?.beginLoading()
                }
            },
        ) {
            coordinator.performWindowOperation {
                if (isCurrentLoad(generation)) {
                    applyInitialPage(store, firstOpen, refreshWindow, generation)
                }
            }
        }
        didComplete = true
    } finally {
        settleInitialLoad(generation, firstOpen, didComplete)
    }
    if (isCurrentLoad(generation)) {
        completedInitialLoadGeneration = generation
        publish()
    }
}

internal suspend fun ChatThreadViewModel.applyInitialPage(
    store: ChatThreadStore,
    firstOpen: Boolean,
    refreshWindow: Boolean,
    generation: ULong,
) {
    // Read the store's live count (falling back to the navigation snapshot on a miss) before the
    // fetch: it sizes the page and places the divider. The pushed DTO's count goes stale during the
    // transition and is 0 for channels, which would misplace or drop the divider. Read before
    // markConversationRead clears it.
    val unread = store.fetchUnreadCount(conversationID) ?: dependencies.unreadCount()
    if (!isCurrentLoad(generation)) return
    if (firstOpen) openUnreadCount = unread
    val floorLimit = ChatCoordinator.initialPageSize(unread)
    val anchorSortDate = if (refreshWindow) coordinator.messages.minOf { it.sortDate } else null
    val window = store.fetchMessageWindow(conversationID, anchorSortDate, floorLimit)
    if (!isCurrentLoad(generation)) return
    val fetched = window.messages
    val unfilteredCount = fetched.size
    if (firstOpen) {
        dividerMessageID = computeNewMessagesDivider(
            fetched,
            unread,
            isDM,
            isHiddenReaction = { message, _ -> isHiddenOutgoingReaction(message, { row -> isReactionText(row) }) },
        )
    }
    writer?.updateRenderState {
        it.copy(hasMoreMessages = window.hasMore, totalFetchedCount = unfilteredCount)
    }
    writer?.replaceAll(filterOutgoingReactions(fetched))
    rebuildChannelSenderIndex()
    buildItems()
    // The unfiltered page, as on iOS: an outgoing reaction row is hidden from the timeline but is
    // still a message a later reaction can target.
    dependencies.indexPageForReactions(fetched)
    markConversationRead(store)
}

/**
 * Loads the next older page and prepends it without moving the viewport (spec §9.3). Guarded on the
 * spinner and the has-more flag inside the window lane; a short page ends history. Duplicates a live
 * append may have inserted are dropped, the older page is prepended, and the same-sender reorder is
 * re-run across the page boundary. Under `reverseLayout` older history appends at the visual top, so
 * the visible rows stay put; stable per-message keys pin the first visible item as a backstop.
 */
suspend fun ChatThreadViewModel.loadOlderMessages() {
    val store = dependencies.store() ?: return
    try {
        runReportingException<Unit>(
            onFailure = { error ->
                errorBannerMessage = messages.userFacing(error, messages.loadOlderMessagesFailed)
            },
        ) {
            coordinator.performWindowOperation {
                if (coordinator.renderState.isLoadingOlder ||
                    !coordinator.renderState.hasMoreMessages
                ) {
                    return@performWindowOperation
                }
                writer?.updateRenderState { it.copy(isLoadingOlder = true) }
                publish()
                val older = fetchPage(store, offset = coordinator.renderState.totalFetchedCount)
                val unfilteredCount = older.size
                writer?.updateRenderState { current ->
                    current.copy(
                        hasMoreMessages = if (unfilteredCount <
                            ChatCoordinator.PAGE_SIZE
                        ) {
                            false
                        } else {
                            current.hasMoreMessages
                        },
                        totalFetchedCount = current.totalFetchedCount + unfilteredCount,
                    )
                }

                val filtered = filterOutgoingReactions(older).filterNot { coordinator.messagesByID.containsKey(it.id) }
                writer?.prepend(filtered)
                writer?.replaceMessagesPreservingByID(reorderSameSenderClusters(coordinator.messages))
                rebuildChannelSenderIndex()
                buildItems()
                // Every older page is indexed too, so a reaction whose target sits below the first page still
                // finds it once the user scrolls back that far.
                dependencies.indexPageForReactions(older)
            }
        }
    } finally {
        if (coordinator.renderState.isLoadingOlder) {
            writer?.updateRenderState { it.copy(isLoadingOlder = false) }
        }
        publish()
    }
}

internal suspend fun ChatThreadViewModel.fetchPage(
    store: ChatThreadStore,
    offset: Int,
    limit: Int = ChatCoordinator.PAGE_SIZE,
): List<MessageDto> = when (val key = conversationID.conversation) {
    is ChatConversationID.ConversationKey.Dm ->
        store.fetchDirectMessages(key.contactID, limit, offset)

    is ChatConversationID.ConversationKey.Channel ->
        store.fetchChannelMessages(conversationID.radioID, key.channelIndex, limit, offset)
}

internal suspend fun ChatThreadViewModel.markConversationRead(store: ChatThreadStore) {
    try {
        store.clearUnread(conversationID)
        // Opening the thread counts as seeing its mentions, matching iOS
        // `markConversationMentionsSeen`. The list mention badge is already zeroed
        // by [clearUnread]; this persists per-message mentionSeen so the jump
        // affordance and backup flags match.
        store.markConversationMentionsSeen(conversationID).forEach { writer?.applyMentionSeen(it) }
        store.markFailedSendsSeen(conversationID)
        dependencies.onConversationsChanged()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // A bookkeeping failure is not surfaced. The unread counter clears on the next open.
    }
}

/**
 * A fail that lands while this thread is open is already on screen; mark it seen so the list
 * badge does not reappear on pop-back. Mirrors iOS `markFailedSendSeenIfCurrent`.
 */
internal suspend fun ChatThreadViewModel.markFailedSendSeenIfCurrent(messageID: Uuid) {
    val store = dependencies.store() ?: return
    val message = store.fetchMessage(messageID) ?: return
    val belongs = when (val key = conversationID.conversation) {
        is ChatConversationID.ConversationKey.Dm -> message.contactID == key.contactID
        is ChatConversationID.ConversationKey.Channel ->
            message.channelIndex == key.channelIndex && message.radioID == conversationID.radioID
    }
    if (!belongs) return
    try {
        store.markFailedSendsSeen(conversationID)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // A failed seen-mark is not surfaced. The send is already on screen.
    }
}

internal fun ChatThreadViewModel.filterOutgoingReactions(messages: List<MessageDto>): List<MessageDto> =
    messages.filterNot { isHiddenOutgoingReaction(it, { row -> isReactionText(row) }) }

/**
 * Rebuilds the channel mention candidates from the loaded page. A DM thread has no channel senders, so
 * the index stays empty there. Mirrors the Swift `buildChannelSenders` call after a channel page loads.
 */
internal fun ChatThreadViewModel.rebuildChannelSenderIndex() {
    if (isDM) return
    channelSenderIndex = ChannelSenderIndex.from(coordinator.messages, conversationID.radioID)
}
