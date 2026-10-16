package one.meshcore.shared.chat

import one.meshcore.shared.model.Uuid

/**
 * Unread counters and per-message read flags for one conversation. [ChatThreadStore] adds the thread's
 * delete, mention, and retry writes on top of these.
 */
interface ChatThreadUnreadStore {
    /**
     * The conversation's current stored unread count, used to bake the new-messages divider from live data
     * rather than a navigation-time DTO snapshot (which can go stale during the push transition, misplacing
     * the divider). Returns null on a miss so the caller falls back to the snapshot. Read on open before
     * [clearUnread] runs. Mirrors the iOS `ChatTimelinePopulator.currentUnreadCount`.
     */
    suspend fun fetchUnreadCount(conversation: ChatConversationID): Int? = null

    /**
     * Clears the conversation's unread and unread-mention counters on open. Per-message `isRead` is
     * written only by shade Mark as Read ([markMessageAsRead]), matching iOS.
     */
    suspend fun clearUnread(conversation: ChatConversationID)

    /**
     * Zeros the unread counter only, leaving unread-mention badges. Shade reply and mark-read use this
     * (iOS `clearUnreadCount` / `clearChannelUnreadCount`). Opening the thread still calls [clearUnread].
     */
    suspend fun clearUnreadCount(conversation: ChatConversationID) {}

    /**
     * Sets per-message `isRead`. iOS shade Mark as Read writes this before clearing the conversation
     * unread counter so adopted-DM banners and backup do not treat the row as unread.
     */
    suspend fun markMessageAsRead(id: Uuid) {}
}
