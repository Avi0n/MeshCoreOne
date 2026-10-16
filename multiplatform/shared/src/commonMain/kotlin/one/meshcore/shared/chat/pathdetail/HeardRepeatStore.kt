package one.meshcore.shared.chat.pathdetail

import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.backup.MessageRepeatDto
import one.meshcore.shared.model.Uuid

/**
 * The persistence seam [HeardRepeatsService] depends on: the dedup existence check keyed by RX-log entry,
 * the exact-match lookup of a sent channel message, the repeat insert, the heard-repeats increment, and the
 * fetch that backs the Repeat Details sheet. Mirrors the Swift `HeardRepeatPersisting` subset of
 * `PersistenceStoreProtocol`.
 */
interface HeardRepeatStore {
    /** Whether a repeat has already been recorded for this RX-log entry (the dedup guard). */
    suspend fun messageRepeatExists(rxLogEntryID: Uuid): Boolean

    /**
     * The sent channel message matching [radioID], [channelIndex], sender [timestamp], and exact [text], or
     * null. The exact-text match disambiguates messages that share a channel and timestamp.
     */
    suspend fun findSentChannelMessage(radioID: Uuid, channelIndex: Int, timestamp: Long, text: String): MessageDto?

    /** The existing incoming channel row for a content-based receive key. */
    suspend fun findIncomingChannelMessage(radioID: Uuid, deduplicationKey: String): MessageDto?

    /** Adopts a path only when the stored incoming message has no canonical path yet. */
    suspend fun adoptIncomingPathIfUnknown(messageID: Uuid, pathNodes: ByteArray, pathLength: Int): Boolean

    /** Inserts (or replaces by id) a repeat row. */
    suspend fun saveMessageRepeat(repeat: MessageRepeatDto)

    /** Increments and returns the message's heard-repeats count; returns 0 when the row is gone. */
    suspend fun incrementMessageHeardRepeats(messageID: Uuid): Int

    /** The stored repeats for [messageID], sorted by receive time ascending. */
    suspend fun fetchMessageRepeats(messageID: Uuid): List<MessageRepeatDto>
}
