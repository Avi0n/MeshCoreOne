package one.meshcore.shared.chat

import one.meshcore.shared.model.Uuid

/** The conversation the user is actively viewing, so ingest suppresses its unread increment. */
data class ActiveChannel(val radioID: Uuid, val index: Int)

/**
 * Reports the conversation currently on screen so the pipeline skips the unread bump for it (§6.6). The
 * open thread owns this state; ingest only reads it. [None] reports nothing active.
 */
interface ActiveConversation {
    /** The contact whose DM thread is open, or null. */
    suspend fun activeContactID(): Uuid?

    /** The channel whose thread is open, or null. */
    suspend fun activeChannel(): ActiveChannel?

    /** No conversation is on screen; every incoming message increments its unread. */
    object None : ActiveConversation {
        override suspend fun activeContactID(): Uuid? = null
        override suspend fun activeChannel(): ActiveChannel? = null
    }
}

/**
 * A received message may be a reaction rather than a chat message; the
 * `handle*` calls short-circuit ingest (no message row) when so, and the `index*` calls register a saved
 * message so later reactions can target it. The [NoOp] default treats nothing as a reaction and indexes
 * nothing.
 */
interface ReactionHook {
    /** Whether [text] was a DM reaction (handled, so ingest stops); false persists it as a message. */
    suspend fun handleDirectReaction(text: String, contact: ResolvedContact, radioID: Uuid): Boolean

    /** Whether [wire] was a channel reaction (handled, so ingest stops). */
    suspend fun handleChannelReaction(wire: ChannelReactionWire): Boolean

    /** Indexes a saved DM so a pending reaction can later match it. */
    suspend fun indexDirectMessage(messageID: Uuid, contactID: Uuid, text: String, timestamp: Long)

    /** Indexes a saved channel message so a pending reaction can later match it. */
    suspend fun indexChannelMessage(
        messageID: Uuid,
        channelIndex: Int,
        senderNodeName: String,
        text: String,
        timestamp: Long,
    )

    /** No message is treated as a reaction and nothing is indexed. */
    object NoOp : ReactionHook {
        override suspend fun handleDirectReaction(text: String, contact: ResolvedContact, radioID: Uuid) = false
        override suspend fun handleChannelReaction(wire: ChannelReactionWire) = false

        override suspend fun indexDirectMessage(messageID: Uuid, contactID: Uuid, text: String, timestamp: Long) = Unit
        override suspend fun indexChannelMessage(
            messageID: Uuid,
            channelIndex: Int,
            senderNodeName: String,
            text: String,
            timestamp: Long,
        ) = Unit
    }
}

/**
 * Ingest calls this notification and conversation-list seam after a message is saved; the
 * phantom-channel gate ([shouldPostChannelNotification]) decides whether a channel message posts or is
 * recorded unresolved. The [NoOp] default preserves receive and persistence behavior without surfacing
 * anything to the OS.
 */
interface IngestNotifier {
    /** Posts a direct-message notification (the impl applies the mute/authorization/suppression gates). */
    suspend fun postDirectMessage(contact: ResolvedContact, text: String, messageID: Uuid, hasSelfMention: Boolean)

    /** Posts a channel-message notification for a resolved channel. */
    suspend fun postChannelMessage(notice: ChannelMessageNotice)

    /**
     * Posts a room-message notification for a saved signed message (the impl applies the muted-level,
     * authorization, room-preference, and sync-suppression gates). Default is a no-op so pure ingest
     * fakes need not implement it. Mirrors iOS `NotificationService.postRoomMessageNotification`.
     */
    suspend fun postRoomMessage(notice: RoomMessageNotice) = Unit

    /** Records a channel message on a slot with no local channel (phantom channel) as a diagnostic. */
    suspend fun recordUnresolvedChannel(channelIndex: Int, radioID: Uuid, senderTimestamp: Long)

    /** Recomputes the app badge count. */
    suspend fun updateBadge()

    /**
     * Clears delivered OS notifications for a direct conversation. Contact block/delete cleanup calls
     * this (iOS `NotificationService.removeDeliveredNotifications(forContactID:)`). Default is a no-op
     * so pure ingest fakes need not implement tray management.
     */
    suspend fun removeDeliveredForContact(contactID: Uuid) = Unit

    /** Signals the conversation list that a conversation changed so it re-reads. */
    suspend fun notifyConversationsChanged()

    /**
     * Suppress (or resume) OS message notifications for the connect/sync window so backlog and
     * mid-sync live events do not flood the user. Mirrors iOS
     * `NotificationService.isSuppressingNotifications` around full sync. Default is a no-op.
     * Named separately from any `isSuppressingNotifications` property setter to avoid JVM signature clash.
     */
    fun setSyncNotificationSuppression(suppress: Boolean) = Unit

    /** No OS-level surfacing, for callers that do not install a notifier. */
    object NoOp : IngestNotifier {
        override suspend fun postDirectMessage(
            contact: ResolvedContact,
            text: String,
            messageID: Uuid,
            hasSelfMention: Boolean,
        ) = Unit

        override suspend fun postChannelMessage(notice: ChannelMessageNotice) = Unit

        override suspend fun recordUnresolvedChannel(channelIndex: Int, radioID: Uuid, senderTimestamp: Long) = Unit
        override suspend fun updateBadge() = Unit
        override suspend fun notifyConversationsChanged() = Unit
    }
}

/**
 * Whether a received channel message warrants a user notification: only when it resolves to a known
 * local channel. The radio can report a message on a slot the app has no channel for (firmware decrypts
 * zero-key group traffic on an unconfigured slot and attributes it to the first empty slot); such a
 * message has no openable chat, so it is persisted and dedup'd but never surfaced. Mirrors Swift
 * `SyncCoordinator.shouldPostChannelNotification(forResolvedChannel:)`.
 */
fun shouldPostChannelNotification(channel: ResolvedChannel?): Boolean = channel != null
