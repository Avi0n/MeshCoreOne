package one.meshcore.shared.chat

import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.model.Uuid

/**
 * The observable state of the conversation list, held by [ConversationListViewModel] and rendered by the
 * Compose screen. Kotlin port of the iOS `ChatViewModel`'s conversation-list surface. The fetch buffers
 * ([contacts]/[channels]/[rooms]) plus [pendingRemovalIds] recompute into [snapshot] in one synchronous
 * pass; [snapshotGeneration] bumps only when the committed snapshot actually changes (the change-gate).
 */
data class ConversationListState(
    /** Contacts loaded for the current radio, before the repeater/blocked/mask filter. */
    val contacts: List<ContactDto> = emptyList(),
    /** Channels loaded for the current radio, before the empty-name/secret filter. */
    val channels: List<ChannelDto> = emptyList(),
    /** Room sessions loaded for the current radio. */
    val rooms: List<RemoteNodeSessionDto> = emptyList(),
    /** Null means unknown; an empty set authoritatively means no persisted contacts remain. */
    val retainingDirectContactIDs: Set<Uuid>? = null,
    /** The committed pinned/other split the list renders. */
    val snapshot: ConversationSnapshot = ConversationSnapshot.EMPTY,
    /** Bumped on every committed snapshot change; a value-identical recompute leaves it unchanged. */
    val snapshotGeneration: Long = 0,
    /** Cached last-message preview text per conversation id (batch-loaded, reaction rows skipped). */
    val previews: Map<Uuid, String> = emptyMap(),
    val searchText: String = "",
    val filter: ChatFilter = ChatFilter.ALL,
    val isLoading: Boolean = false,
    /** True once a load has completed; gates the empty state so it does not flash before the first load. */
    val hasLoadedOnce: Boolean = false,
    /** Rows with a radio-backed delete in flight (spinner overlay). Distinct from [pendingRemovalIds]. */
    val deletingIds: Set<Uuid> = emptySet(),
    /** Rows hidden after an optimistic delete, held until a reload confirms the row gone (anti-resurrection). */
    val pendingRemovalIds: Set<Uuid> = emptySet(),
    /** A surfaced action failure, shown as a dismissible alert. */
    val errorMessage: String? = null,
    /**
     * A channel radio-delete that failed or timed out. Shown as Channel Deletion Failed with Try Again,
     * matching iOS `showChannelDeleteFailed`. Distinct from [errorMessage] so a room or DM failure is
     * not titled as a channel deletion.
     */
    val channelDeleteFailure: ChannelDeleteFailure? = null,
    /** Conversation ids that currently have an outgoing failed send. */
    val failedSendConversationIDs: Set<Uuid> = emptySet(),
) {
    /** Whether a search is active (search spans all types and ignores the filter). */
    val isSearching: Boolean get() = searchText.isNotEmpty()

    /** True while an optimistic hide or a radio-backed delete is in flight for [id]. */
    fun isDeletePending(id: Uuid): Boolean = id in pendingRemovalIds || id in deletingIds
}

/** A failed radio `clearChannel` the list can retry, mirroring iOS `ChatConversationActions.Failure`. */
data class ChannelDeleteFailure(val channel: ChannelDto, val message: String)
