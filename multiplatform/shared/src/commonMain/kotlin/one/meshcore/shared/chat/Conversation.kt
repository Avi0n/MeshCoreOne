package one.meshcore.shared.chat

import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.model.NotificationLevel
import one.meshcore.shared.model.Uuid

/**
 * A conversation-list row: a direct chat, a channel, or a room. Uniform accessors let the list treat
 * all three the same. Mirrors the Swift `Conversation` enum. The list renders
 * and passes them through.
 *
 * `displayName` for a channel is the raw [ChannelDto.name]; the presentation layer owns the localized
 * empty-name fallback (`Channel N`).
 */
sealed interface Conversation {
    val id: Uuid
    val displayName: String

    /** Last-message time as epoch seconds, or null when the conversation has no messages. */
    val lastMessageDate: Double?
    val unreadCount: Int
    val notificationLevel: NotificationLevel
    val isPinned: Boolean

    val isMuted: Boolean
        get() = notificationLevel == NotificationLevel.MUTED

    /**
     * Whether deleting this row must be confirmed first. Leaving a room deletes every room message and
     * removes the backing contact, none of which can be undone, so it asks; direct and channel deletes
     * run straight away. Mirrors the Swift `ChatListActions.handleDeleteConversation` switch.
     */
    val requiresDeleteConfirmation: Boolean
        get() = this is Room

    data class Direct(val contact: ContactDto) : Conversation {
        override val id: Uuid get() = contact.id

        // Contacts fall back to the raw name when no nickname is set.
        override val displayName: String get() = contact.nickname ?: contact.name
        override val lastMessageDate: Double? get() = contact.lastMessageDate
        override val unreadCount: Int get() = contact.unreadCount

        // Contacts carry a boolean mute, mapped to the two-level muted/all.
        override val notificationLevel: NotificationLevel
            get() = if (contact.isMuted) NotificationLevel.MUTED else NotificationLevel.ALL
        override val isPinned: Boolean get() = contact.isPinned
    }

    data class Channel(val channel: ChannelDto) : Conversation {
        override val id: Uuid get() = channel.id
        override val displayName: String get() = channel.name
        override val lastMessageDate: Double? get() = channel.lastMessageDate
        override val unreadCount: Int get() = channel.unreadCount
        override val notificationLevel: NotificationLevel get() = channel.notificationLevel
        override val isPinned: Boolean get() = channel.isPinned
    }

    data class Room(val session: RemoteNodeSessionDto) : Conversation {
        override val id: Uuid get() = session.id
        override val displayName: String get() = session.name
        override val lastMessageDate: Double? get() = session.lastMessageDate
        override val unreadCount: Int get() = session.unreadCount
        override val notificationLevel: NotificationLevel get() = session.notificationLevel
        override val isPinned: Boolean get() = session.isPinned
    }
}
