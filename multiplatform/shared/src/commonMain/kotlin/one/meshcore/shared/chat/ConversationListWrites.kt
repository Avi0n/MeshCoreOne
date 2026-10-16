package one.meshcore.shared.chat

import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.model.NotificationLevel
import one.meshcore.shared.model.Uuid

/**
 * Optimistic per-conversation writes on [ConversationListStore]: mute, pin, clear, and leave.
 */
interface ConversationListWrites {
    /** Sets a contact's boolean mute (contacts map the level to muted/all). */
    suspend fun setContactMuted(contactID: Uuid, isMuted: Boolean)

    /** Sets a channel's three-level notification setting. */
    suspend fun setChannelNotificationLevel(channelID: Uuid, level: NotificationLevel)

    /** Sets a room session's notification level (rooms support muted/all). */
    suspend fun setSessionNotificationLevel(sessionID: Uuid, level: NotificationLevel)

    /** Sets a contact's local chat pin without changing its radio favorite. */
    suspend fun setContactPinned(contactID: Uuid, isPinned: Boolean)

    /** Sets a channel's local chat pin. */
    suspend fun setChannelPinned(channelID: Uuid, isPinned: Boolean)

    /** Sets a room session's local chat pin. */
    suspend fun setSessionPinned(sessionID: Uuid, isPinned: Boolean)

    /** Clears a direct conversation: deletes its messages, clears unread, nulls its last-message date. */
    suspend fun clearDirectConversation(contactID: Uuid)

    /** Leaves a room — radio commands (logout, remove contact); the row spins until they ack. */
    suspend fun leaveRoom(session: RemoteNodeSessionDto)
}
