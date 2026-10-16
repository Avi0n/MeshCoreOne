package one.meshcore.shared.chat

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.model.Uuid

/**
 * A one-shot scroll instruction the view model hands the list. Carried in [ChatThreadRenderState] rather
 * than fired as an event so scrolling is state-driven (spec §9.5): the Compose side keys a `LaunchedEffect`
 * on [token], runs the scroll once, and never re-runs until a newer command bumps the token. This is the
 * `requestScrollToItem` / `LaunchedEffect(scroll-target-id)` pattern the plan mandates — never a
 * fire-and-forget event or a delay.
 */
sealed interface ScrollCommand {
    val token: Int

    /**
     * Land on the newest row (visual bottom under `reverseLayout`). Raised on send, composer pin,
     * and near-bottom arrival.
     */
    data class ToBottom(override val token: Int) : ScrollCommand

    /**
     * Scroll a specific message id into view (divider jump, notification deep-link). [animated] is false for
     * the open-at-divider anchor, which must land on the first frame rather than visibly scroll up from the
     * bottom; the affordance-driven jumps animate.
     */
    data class ToMessage(val messageID: Uuid, override val token: Int, val animated: Boolean = true) : ScrollCommand
}

/**
 * The immutable snapshot the conversation-thread Compose surface renders. Combines the coordinator-owned
 * timeline ([items], [phase], pagination flags) with the view-model-owned per-view state (the positional
 * divider, the scroll gate, the pending scroll command, and error copy). Fully value-typed so Compose skips
 * recomposition when nothing changed. The Kotlin analogue of the observable surface the iOS `ChatViewModel`
 * forwards from its shared `ChatCoordinator` plus its own `@Observable` fields.
 */
data class ChatThreadRenderState(
    val items: List<MessageItem>,
    val phase: ChatRenderState.LoadPhase,
    val hasMoreMessages: Boolean,
    val isLoadingOlder: Boolean,
    val newMessagesDividerMessageID: Uuid?,
    val isAtBottom: Boolean,
    val unreadWhileScrolledUp: Int,
    val scrollCommand: ScrollCommand?,
    val contactRefreshSignal: Int,
    /** Latest completed populate; null while a newer load is pending or cancelled. */
    val completedInitialLoadGeneration: ULong? = null,
    val errorMessage: String?,
    val sendErrorMessage: String?,
    /** Passive pagination/prefetch failure. iOS `errorBannerMessage`. */
    val errorBannerMessage: String? = null,
    /**
     * Whether the timeline is ready to show and, if so, the divider id it opens scrolled to.
     * [ChatInitialScrollPolicy.FirstSnapshotDecision.Withhold] keeps a warm coordinator page off screen
     * until this session's flag-bearing divider row is among the current items.
     */
    val firstSnapshot: ChatInitialScrollPolicy.FirstSnapshotDecision =
        ChatInitialScrollPolicy.FirstSnapshotDecision.Present(null),
    /** Synthetic mention candidates for channel senders with no contact row (empty on a DM thread). */
    val channelSenders: List<ContactDto> = emptyList(),
    /** Latest wire timestamp per channel sender name, ranking the mention list by recency. */
    val channelSenderOrder: Map<String, UInt> = emptyMap(),
) {
    /** True once the first fetch has settled: gates the empty-state placeholder so it never flashes on open. */
    val isLoaded: Boolean get() = phase == ChatRenderState.LoadPhase.LOADED

    /**
     * True only while explicitly in [ChatRenderState.LoadPhase.LOADING] with no items yet.
     * UNINITIALIZED with an empty list is *not* a full-screen spinner: the open path fetches local page-0
     * before flipping to LOADING when possible, so the first paint can be chrome + content (or clear)
     * rather than a centered progress indicator for already-local messages.
     */
    val isInitialLoading: Boolean get() = phase == ChatRenderState.LoadPhase.LOADING && items.isEmpty()

    /**
     * Self-mentions not yet marked seen, oldest first (chronological, matching [items] order). Drives the
     * scroll-to-mention affordance: the Compose side intersects this against the currently visible rows to
     * find which are off-screen, mirroring iOS `unseenMentionIDs`.
     */
    val unseenMentionMessageIDs: List<Uuid>
        get() = items.filter { it.envelope.containsSelfMention && !it.envelope.mentionSeen }.map { it.id }

    companion object {
        val EMPTY = ChatThreadRenderState(
            items = emptyList(),
            phase = ChatRenderState.LoadPhase.UNINITIALIZED,
            hasMoreMessages = true,
            isLoadingOlder = false,
            newMessagesDividerMessageID = null,
            isAtBottom = true,
            unreadWhileScrolledUp = 0,
            scrollCommand = null,
            contactRefreshSignal = 0,
            errorMessage = null,
            sendErrorMessage = null,
            errorBannerMessage = null,
        )
    }
}
