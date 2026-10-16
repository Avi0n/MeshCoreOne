package one.meshcore.shared.chat

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.model.Uuid

/**
 * Per-`(radio, conversation)` source of truth for the chat timeline. Shared by every UI surface bound to
 * one conversation (resolved through [ChatCoordinatorRegistry]); lives for the radio partition's lifetime.
 * Mirrors the Swift `ChatCoordinator`. Mutations, coalesced reload, and item rebuild live as sibling
 * extensions so this type stays the state holder.
 *
 * Concurrency: this type is single-thread confined, the way the Swift original is `@MainActor`. Every
 * public method and [scope] must run on one confinement dispatcher; only the off-thread item build in
 * [rebuildItems] leaves it (via [computeDispatcher]) and it touches no coordinator state until it hops back.
 * The event fold that feeds these methods (incoming append, status resolution, reload triggers) lives in
 * the thread layer; this class is the sink those events drive.
 */
class ChatCoordinator internal constructor(
    val conversationID: ChatConversationID,
    internal val store: ChatCoordinatorStore,
    internal val scope: CoroutineScope,
    internal val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    /** Canonical loaded-messages list. Readers use [messagesByID] or [renderState]. */
    var messages: List<MessageDto> = emptyList()
        internal set

    /** O(1) id lookup every append / update / dedup guard reads. */
    var messagesByID: Map<Uuid, MessageDto> = emptyMap()
        internal set

    /** Immutable timeline snapshot the UI renders. Rebuilt from [messages]; assigned on confinement only. */
    var renderState: ChatRenderState = ChatRenderState.EMPTY
        internal set

    /**
     * Monotonic counter bumped on every mutation of [messages] or assignment to [renderState]. The off-thread
     * build captures it at start and discards its result if it advanced (the stale-build guard). Unsigned,
     * so it wraps rather than overflows.
     */
    var renderStateID: ULong = 0uL
        internal set

    /**
     * One reaction-scroll wait. Resumed when a render-state write can honor or retire that message.
     * A later register replaces it.
     */
    internal var scrollRenderWaiter: ScrollRenderWaiter? = null

    /** Ids accumulated since the last reload drain; drained atomically by the coalesced load. */
    internal val pendingReloadIDs: MutableSet<Uuid> = mutableSetOf()

    /** Whether a coalesced-reload drain is currently in flight (the coalescing guard consults it). */
    internal var reloadInFlight = false

    /** Gates concurrent loads while a hard reset is mid-flight so a running drain can't stomp fresh state. */
    internal var hardResetInFlight = false

    /** How many coalesced-reload drains were actually launched — the observable coalescing metric (N -> 1). */
    internal var coalescedReloadLaunches = 0

    internal var buildItemsJob: Job? = null
    internal var coalescedReloadJob: Job? = null
    internal var hardResetJob: Job? = null

    /**
     * Per-id render-item rebuild hook the drain invokes after refreshing a DTO, so the bound view model
     * rebuilds the affected [MessageItem] from its own inputs. Null when nothing is bound (headless / tests):
     * the drain then refreshes DTOs without rebuilding items.
     */
    var renderItemRebuilder: ((Uuid) -> Unit)? = null

    /**
     * Fires when `renderState.items` no longer reflects canonical [messages] and the bound view model must
     * reassemble per-message inputs before calling [rebuildItems] again — when a fresher mutation lands
     * mid-build and [setRenderState] rejects the stale result, and after a hard reset's `replaceAll`.
     */
    var renderStateInvalidated: (() -> Unit)? = null

    /**
     * Mint generation of the current [ChatTimelineWriter]. Stale writers compare against this and no-op.
     * Mirrors iOS `ChatCoordinator.writerGeneration`.
     */
    var writerGeneration: ULong = 0uL
        internal set

    private var writerOwner: Any? = null
    private var writerRole: ChatWriterRole = ChatWriterRole.PRIME

    /**
     * Claims write access. [ChatWriterRole.INTERACTIVE] always succeeds and revokes every previously
     * minted writer. [ChatWriterRole.PRIME] succeeds only while no live interactive owner exists.
     */
    fun bindWriter(
        owner: Any,
        role: ChatWriterRole,
        renderItemRebuilder: ((Uuid) -> Unit)? = null,
        renderStateInvalidated: (() -> Unit)? = null,
    ): ChatTimelineWriter? {
        if (role == ChatWriterRole.PRIME && writerRole == ChatWriterRole.INTERACTIVE && writerOwner != null) {
            return null
        }
        writerGeneration += 1uL
        writerOwner = owner
        writerRole = role
        this.renderItemRebuilder = renderItemRebuilder
        this.renderStateInvalidated = renderStateInvalidated
        return ChatTimelineWriter(this, writerGeneration, role)
    }

    /**
     * Vacates the writer slot if [owner] still holds it. A slot that never vacates starves every
     * arrival-time prime. The identity check keeps a stale view's teardown from evicting a successor.
     */
    fun releaseWriter(owner: Any) {
        if (writerOwner !== owner) return
        writerOwner = null
        writerRole = ChatWriterRole.PRIME
        renderItemRebuilder = null
        renderStateInvalidated = null
    }

    private val windowMutex = Mutex()

    /**
     * Runs [operation] after prior window operations finish so populate, loadOlder, and hardReset
     * never interleave. Staleness is gated on mutations inside the operation, not at enqueue.
     * Mirrors iOS `ChatCoordinator.performWindowOperation`.
     */
    suspend fun <T> performWindowOperation(operation: suspend () -> T): T = windowMutex.withLock { operation() }

    /** Cancel every in-flight maintenance job so suspended loops don't outlive the connection. */
    fun cancelInFlight() {
        buildItemsJob?.cancel()
        coalescedReloadJob?.cancel()
        hardResetJob?.cancel()
    }

    companion object {
        /** Pagination page size; the hard-reset refetch and initial-load sizing share it. */
        const val PAGE_SIZE: Int = 50

        /**
         * Read messages loaded above the first unread so the new-messages divider has context to sit
         * beneath rather than pinning to the oldest loaded row.
         */
        const val DIVIDER_READ_CONTEXT: Int = 12

        /**
         * Ceiling on the first-page fetch. Remaining unread pages in via loadOlder; the divider stays
         * on the oldest row of this window and is not recomputed. Mirrors iOS `maxInitialPageSize`.
         */
        const val MAX_INITIAL_PAGE_SIZE: Int = 200

        /**
         * Rows to fetch when a conversation opens. At least [PAGE_SIZE], enough unread plus read
         * context when that fits, otherwise [MAX_INITIAL_PAGE_SIZE].
         */
        fun initialPageSize(unreadCount: Int): Int =
            minOf(maxOf(PAGE_SIZE, unreadCount + DIVIDER_READ_CONTEXT), MAX_INITIAL_PAGE_SIZE)
    }
}
