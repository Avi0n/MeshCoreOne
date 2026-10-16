package one.meshcore.shared.chat

import one.meshcore.shared.model.Uuid

/**
 * Unread, mention, last-message, and hear stamps the ingest path writes after a message is saved.
 * Split from [IngestStore] so resolution and these counters stay separate.
 */
interface IngestBookkeeping {
    /** Stamps a contact's last-message date so it surfaces in the conversation list. */
    suspend fun updateContactLastMessage(contactID: Uuid, dateEpochSeconds: Double)

    /** Stamps a channel's last-message date. */
    suspend fun updateChannelLastMessage(channelID: Uuid, dateEpochSeconds: Double)

    /** Increments a contact's unread counter. */
    suspend fun incrementContactUnread(contactID: Uuid)

    /** Increments a contact's unread-mention counter. */
    suspend fun incrementContactUnreadMention(contactID: Uuid)

    /** Increments a channel's unread counter. */
    suspend fun incrementChannelUnread(channelID: Uuid)

    /** Increments a channel's unread-mention counter. */
    suspend fun incrementChannelUnreadMention(channelID: Uuid)

    /**
     * Stamps phone-clock recency on the sender of an inbound DM. Called before the reaction
     * short-circuit so a reaction still counts as a hear. Mirrors iOS `touchContactHeard` on ingest.
     */
    suspend fun touchContactHeard(contactID: Uuid, atEpochSeconds: Long)
}
