package one.meshcore.shared.chat

import one.meshcore.shared.model.Uuid

/** Counter and date reads the in-memory ingest store exposes for tests. */
interface IngestCounterRead {
    /** The contact's unread count. */
    suspend fun contactUnreadCount(contactID: Uuid): Int

    /** The contact's unread-mention count. */
    suspend fun contactUnreadMentionCount(contactID: Uuid): Int

    /** The channel's unread count. */
    suspend fun channelUnreadCount(channelID: Uuid): Int

    /** The channel's unread-mention count. */
    suspend fun channelUnreadMentionCount(channelID: Uuid): Int

    /** The contact's last-message date, or null. */
    suspend fun contactLastMessageDate(contactID: Uuid): Double?

    /** The contact's last phone-clock hear stamp, or null. Test-only. */
    suspend fun contactHeardAt(contactID: Uuid): Long?

    /** The channel's last-message date, or null. */
    suspend fun channelLastMessageDate(channelID: Uuid): Double?
}
