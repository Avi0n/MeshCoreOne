package one.meshcore.shared.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.chat.linkpreview.LinkPreviewCache
import one.meshcore.shared.chat.linkpreview.LinkPreviewController
import one.meshcore.shared.chat.reactions.ReactionService
import one.meshcore.shared.lifecycle.OwnedJobs
import one.meshcore.shared.map.MapSnapshotStore
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.urlsafety.MalwareDomainFilter

/**
 * Marks the open conversation so incoming messages for it neither increment unread nor post a banner
 * (spec §6.6/§13.4), and releases the slot on leave — but only if this conversation still owns it, since a
 * newer open may have claimed it. A no-op default is provided for headless/test use. Mirrors the Swift
 * `NotificationService.setActiveConversation`/`clearActiveConversation`.
 */
interface ActiveConversationSink {
    suspend fun setActive(conversation: ChatConversationID)

    /**
     * Releases the slot [conversation] holds as the thread tears down. Not suspending, and a no-op when a
     * newer conversation already claimed the slot: the thread's coroutine scope is cancelled by the time
     * this runs, so the implementation dispatches on a longer-lived one.
     */
    fun clearActiveAsync(conversation: ChatConversationID)

    companion object {
        val NONE: ActiveConversationSink = object : ActiveConversationSink {
            override suspend fun setActive(conversation: ChatConversationID) = Unit
            override fun clearActiveAsync(conversation: ChatConversationID) = Unit
        }
    }
}

/**
 * Drives one open conversation thread. It owns the flow-subscription fold that routes the ingest and
 * status broadcasters into the shared [ChatCoordinator] (spec §9.2 — the coordinator is the sink, this is
 * the fold), the initial load, the `loadOlderMessages` pagination window with the scroll-anchoring
 * guarantee, the positional new-messages divider, the near-bottom auto-scroll gate, mark-read on open,
 * active-conversation set/clear, and the send optimistic flow (composer -> [MessageService]/
 * [ChatSendQueueService], with the status events folding back in place). The Kotlin port of the iOS
 * `ChatViewModel` conversation-thread surface and its extensions.
 *
 * The coordinator owns the canonical timeline ([ChatCoordinator.messages]/`renderState`), including
 * pagination flags. This view model owns the per-view divider, scroll gate, and error copy, and publishes
 * a single combined [ChatThreadRenderState] the Compose surface renders. Every method that touches the
 * coordinator must run on the coordinator's confinement dispatcher (the [scope] shares it), the way the
 * iOS original is `@MainActor`; the coordinator's own async reload feeds back through the render hooks
 * installed in [bind].
 */
class ChatThreadViewModel(
    val conversationID: ChatConversationID,
    internal val coordinator: ChatCoordinator,
    internal val scope: CoroutineScope,
    internal val dependencies: Dependencies,
) {
    /** Current generation-stamped writer; null after [cleanup] vacates the slot. */
    internal var writer: ChatTimelineWriter? = null

    /**
     * The live, per-connection inputs the thread reads at each use. A provider returning `null` mirrors a
     * disconnected state, matching the iOS view model's `@MainActor () -> T?` providers.
     */
    data class Dependencies(
        /** The current shipped error copy, resolved at composition; see [ChatErrorMessages]. */
        val errorMessages: () -> ChatErrorMessages,
        /** The paginated read + delete/mark-read seam for the live radio, or `null` when disconnected. */
        val store: () -> ChatThreadStore?,
        /** The send queue for the live connection, or `null` when disconnected. */
        val sendQueue: () -> ChatSendQueueService?,
        /** The message service (optimistic pending-message creation), or `null` when disconnected. */
        val messageService: () -> MessageService?,
        /** The bound DM contact, or `null` for a channel thread / when disconnected. */
        val currentContact: () -> ContactDto?,
        /** The conversation's unread count at open, used once to place the divider (spec §9.4). */
        val unreadCount: () -> Int,
        /** Whether the radio is fully synced and ready (gates deletes). */
        val isReady: () -> Boolean,
        /** The environment inputs (local user name, incoming-send-time toggle) that gate the item build. */
        val envInputs: () -> EnvInputs,
        /** Resolves a message's sender display name, falling back to the raw sender when unresolved. */
        val resolveSender: SenderNameResolver,
        /** Unique-name incoming avatar table; empty until channel contacts load. */
        val incomingAvatarTable: () -> Map<String, IncomingAvatarIdentity> = { emptyMap() },
        /** Notification-suppression sink for the open conversation. */
        val activeConversation: ActiveConversationSink = ActiveConversationSink.NONE,
        /** Chat map thumbnail resolution cache; null until the shell wires a process-lifetime store. */
        val mapSnapshotStore: () -> MapSnapshotStore? = { null },
        /** Inline-image aspect ratios; null until the shell wires a process-lifetime store. */
        val inlineImageDimensions: () -> InlineImageDimensionsStore? = { null },
        /**
         * Process-lifetime link-preview cache (Room + network). Null keeps previews idle forever —
         * production shell must wire this for LINK-PREVIEW.
         */
        val linkPreviewCache: () -> LinkPreviewCache? = { null },
        /** Malware host blocklist for preview URLs; null means allow-all (no hosts file loaded yet). */
        val malwareFilter: () -> MalwareDomainFilter? = { null },
        /**
         * The connection's reaction service, whose summary updates reload the row a reaction landed on.
         * Null leaves badges to appear only on the next full load.
         */
        val reactionService: () -> ReactionService? = { null },
        /** Route-bound current intent, read again after each suspension. */
        val pendingScrollRequest: () -> ChatScrollRequest?,
        /** Consumes only the captured request identity; false means a newer tap replaced it. */
        val consumePendingScrollRequest: (Uuid) -> Boolean,
        /**
         * Indexes a freshly fetched page for reaction matching, draining any queued reaction whose target
         * the page just brought in. A reaction can arrive before its target message is in memory — after an
         * app restart, or for a message that only appears on an older page — so an unindexed page leaves
         * that reaction pending forever and its badge never appears. Required for the same reason.
         */
        val indexPageForReactions: suspend (List<MessageDto>) -> Unit,
        /**
         * Reloads the conversation list after mark-read or delete so unread badges and last-message
         * previews match the store. Defaults to a no-op for headless tests.
         */
        val onConversationsChanged: suspend () -> Unit = {},
    )

    internal val threadRenderState = MutableStateFlow(ChatThreadRenderState.EMPTY)

    /** The observable combined thread state the Compose surface renders. */
    val renderState: StateFlow<ChatThreadRenderState> = threadRenderState.asStateFlow()

    internal var loadGeneration = 0uL
    internal var completedInitialLoadGeneration: ULong? = null
    internal var buildInFlight = false
    internal var dividerMessageID: Uuid? = null
    internal var isAtBottom = true
    internal var unreadWhileScrolledUp = 0
    internal var scrollToken = 0
    internal var scrollCommand: ScrollCommand? = null

    /** True after this view model's first [loadInitial]; a reconnect of the same instance is not a new open. */
    internal var didStageOpen = false

    /** Unread count this open keys the divider on, captured from the navigation snapshot then the store. */
    internal var openUnreadCount = dependencies.unreadCount()

    /** True once the first populate finished (any outcome), so a load without a divider can present. */
    internal var initialLoadSettled = false

    /** True after the list has positioned on the open anchor; later snapshots present freely. */
    internal var anchorConsumed = false

    /** True after the one-shot open scroll command has been issued (divider or notification). */
    internal var openScrollIssued = false
    internal var contactRefreshSignal = 0
    internal var errorMessage: String? = null
    internal var sendErrorMessage: String? = null
    internal var errorBannerMessage: String? = null
    internal var channelSenderIndex = ChannelSenderIndex.EMPTY
    internal var lastEnvWasOffline: Boolean? = null
    internal var lastAppliedEnv: EnvInputs? = null
    internal val messages: ChatErrorMessages get() = dependencies.errorMessages()

    internal var eventJobs: List<Job> = emptyList()
    internal val ownedJobs = OwnedJobs(scope)

    internal val isDM: Boolean get() = conversationID.conversation is ChatConversationID.ConversationKey.Dm

    internal val linkPreviewController: LinkPreviewController? =
        dependencies.linkPreviewCache()?.let { cache ->
            LinkPreviewController(
                cache = cache,
                malwareFilter = dependencies.malwareFilter() ?: MalwareDomainFilter { null },
                scope = scope,
                isChannelMessage = {
                    conversationID.conversation is ChatConversationID.ConversationKey.Channel
                },
                onRebuild = { id -> rebuildRenderItem(id) },
            )
        }

    /**
     * Image/card content resolution after admission. Null when the process-lifetime cache is unwired.
     */
    internal val inlineImagePrefetcher: InlineImagePrefetcher? =
        dependencies.linkPreviewCache()?.let { cache ->
            InlineImagePrefetcher(
                cache = cache,
                dimensionsStore = dependencies.inlineImageDimensions(),
            )
        }

    internal val admission = ChatThreadAdmission(this)
    internal val sendActions = ChatThreadSend(this, admission)

    init {
        bind()
    }
}
