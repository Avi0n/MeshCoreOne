package one.meshcore.shared.chat

import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.model.Uuid

/**
 * The read seam [ChatCoordinator] depends on: a per-id fetch for the coalesced reload and the window
 * fetch populate and hard reset use. A narrow port of the Swift `PersistenceStore` reads the coordinator
 * calls; [one.meshcore.shared.persistence.RoomChatStore] provides the production implementation.
 */
interface ChatCoordinatorStore {
    /** The stored message for [id], or null when the row is gone (a null for a known id triggers a hard reset). */
    suspend fun fetchMessage(id: Uuid): MessageDto?

    /** The most recent [limit] direct messages for [contactID], newest-page-first offset math per the caller. */
    suspend fun fetchDirectMessages(contactID: Uuid, limit: Int, offset: Int): List<MessageDto>

    /** The most recent [limit] channel messages for `(radioID, channelIndex)`. */
    suspend fun fetchChannelMessages(radioID: Uuid, channelIndex: Int, limit: Int, offset: Int): List<MessageDto>

    /**
     * Newest-end window for [conversation]: at least [floorLimit] rows, widened to every row with
     * `sortDate` at or newer than [anchorSortDate] (null means the floor alone). Mirrors iOS
     * `fetchMessageWindow`. Default pages [floorLimit] from offset 0 for fakes that do not implement
     * the widen.
     */
    suspend fun fetchMessageWindow(
        conversation: ChatConversationID,
        anchorSortDate: Double?,
        floorLimit: Int,
    ): MessageWindow {
        val messages = when (val key = conversation.conversation) {
            is ChatConversationID.ConversationKey.Dm ->
                fetchDirectMessages(key.contactID, floorLimit, 0)
            is ChatConversationID.ConversationKey.Channel ->
                fetchChannelMessages(conversation.radioID, key.channelIndex, floorLimit, 0)
        }
        return MessageWindow(messages = messages, hasMore = messages.size == floorLimit)
    }
}
