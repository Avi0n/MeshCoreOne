package one.meshcore.shared.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.channel.ChannelService
import one.meshcore.shared.chat.reactions.ReactionParser
import one.meshcore.shared.model.Uuid

/**
 * Drives the conversation list: it loads contacts, channels, and rooms for the live radio, recomputes the
 * pinned/other [ConversationSnapshot] in one synchronous pass (excluding masked rows), applies the
 * [ChatFilter] + search through the pure [filtered] port, caches last-message previews, and runs the
 * optimistic row actions (notification level, pin, delete) against the injected [ConversationListStore].
 * The Kotlin analogue of the iOS `ChatViewModel` conversation-list surface, plain over provider closures so
 * it is unit-testable on the desktop JVM.
 *
 * There is no reactive conversation stream from the store; the view model reloads after every action and
 * when [reload] is invoked (the host calls it on a conversations-changed signal), mirroring the iOS view
 * model's explicit `requestConversationReload` calls.
 */
class ConversationListViewModel(internal val dependencies: Dependencies) {
    /**
     * The live, per-connection inputs the view model reads at each use. A provider returning `null`
     * mirrors a disconnected state, matching the iOS view model's `@MainActor () -> T?` providers.
     */
    data class Dependencies(
        /** The current shipped error copy, resolved at composition; see [ChatErrorMessages]. */
        val errorMessages: () -> ChatErrorMessages,
        /** The conversation data source for the live radio, or `null` when disconnected. */
        val store: () -> ConversationListStore?,
        /** The live radio's data-partition id, or `null` when disconnected. */
        val radioId: () -> Uuid?,
        /** Whether the radio is fully synced and ready (gates the optimistic writes and radio deletes). */
        val isReady: () -> Boolean = { false },
        /**
         * Whether a message's text is a reaction, so an already-sent outgoing one drops out of the row
         * preview instead of showing its raw wire text. Defaults to the real detector, keyed on the row's
         * own conversation kind; a test overrides it to keep every message in the preview.
         */
        val isReaction: (MessageDto) -> Boolean = { message ->
            ReactionParser.isSentReactionText(message.text, isDM = message.channelIndex == null)
        },
        /**
         * The live channel service, or `null` when disconnected. Deleting a channel must vacate the slot on
         * the radio, so this is the only channel-delete path; a local-only delete would leave the radio
         * decrypting the channel and the next sync would re-create the row.
         */
        val channelService: () -> ChannelService? = { null },
        /**
         * Clears a deleted channel's delivered notifications and refreshes the badge, the iOS
         * `removeDeliveredNotifications(forChannelIndex:radioID:)` plus `updateBadgeCount`. Defaulted to a
         * no-op on a platform with no notification service wired.
         */
        val clearChannelNotifications: suspend (channelIndex: Int, radioID: Uuid) -> Unit = { _, _ -> },
        /**
         * Refreshes the OS unread badge after mute or delete. iOS `updateBadgeCount` after
         * `setNotificationLevel` / `deleteDirectConversation`. Defaulted to a no-op.
         */
        val updateBadge: suspend () -> Unit = {},
    )

    /** Same-package sections write this flow. Callers read [state]. */
    internal val listState = MutableStateFlow(ConversationListState())
    internal val reloadMutex = Mutex()
    internal var failedSendRefreshGeneration = 0
    internal var loadedRadioID: Uuid? = null

    /** The observable list state the Compose screen renders. */
    val state: StateFlow<ConversationListState> = listState.asStateFlow()

    internal val messages: ChatErrorMessages get() = dependencies.errorMessages()

    internal fun ownsReload(store: ConversationListStore, radioID: Uuid): Boolean =
        dependencies.store() === store && dependencies.radioId() == radioID

    internal companion object {
        const val CONTACT_PREVIEW_LIMIT = 10
        const val CHANNEL_PREVIEW_LIMIT = 20

        /**
         * Upper bound for a delete that round-trips to the radio, mirroring the Swift
         * `RadioCommandTimeout.delete`. Too short re-admits a slow-but-successful delete as a spurious error.
         */
        const val RADIO_DELETE_TIMEOUT_MS = 7_000L
    }
}

/** Sorts conversations by last-message date, most recent first; a null date sorts to the end. */
internal fun sortedByLastMessage(items: List<Conversation>): List<Conversation> =
    items.sortedByDescending { it.lastMessageDate ?: Double.NEGATIVE_INFINITY }

/** Whether a channel carries a non-empty secret (any non-zero byte), mirroring the Swift `hasSecret`. */
internal val ChannelDto.hasSecret: Boolean get() = secret.any { it.toInt() != 0 }
