package one.meshcore.shared.navigation

import one.meshcore.shared.backup.RemoteNodeSessionDto

/**
 * A chat identity with transient row presentation. Only the selected tab is persisted; titles and the
 * room snapshot stay in memory. Details resolve their live graph from providers rather than routes.
 */
sealed interface ChatRoute {
    /** The stable identity the split-view detail keys its rebuild on. */
    val conversationId: String

    /**
     * Human title from the conversation list (or other entry surface) for the first content frame while
     * the store resolves. Null when the opener has no title (deep link / notification).
     */
    val displayTitle: String?

    /** A one-to-one conversation, keyed by the persisted contact ID. */
    data class Direct(val contactId: String, override val displayTitle: String? = null) : ChatRoute {
        override val conversationId: String get() = "direct:$contactId"
    }

    /** A channel conversation, keyed by the persisted channel ID. */
    data class Channel(val channelId: String, override val displayTitle: String? = null) : ChatRoute {
        override val conversationId: String get() = "channel:$channelId"
    }

    /** A room conversation, keyed by the remote-node session's stable id. */
    data class Room(
        val sessionId: String,
        override val displayTitle: String? = null,
        /** Transient row payload for the mounted room; never persisted with navigation preferences. */
        val sessionSnapshot: RemoteNodeSessionDto? = null,
    ) : ChatRoute {
        val isConnected: Boolean get() = sessionSnapshot?.isConnected ?: true
        override val conversationId: String get() = "room:$sessionId"
    }
}

/** Animation identity excludes payload changes while the content still receives the fresh route. */
internal fun ChatRoute.navigationIdentity(): ChatRoute = when (this) {
    is ChatRoute.Direct -> copy(displayTitle = null)
    is ChatRoute.Channel -> copy(displayTitle = null)
    is ChatRoute.Room -> copy(displayTitle = null, sessionSnapshot = null)
}
