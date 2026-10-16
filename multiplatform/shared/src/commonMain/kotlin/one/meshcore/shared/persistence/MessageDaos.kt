package one.meshcore.shared.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import one.meshcore.shared.chat.MessageStatusGuards
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.Uuid

/** Paged thread reads plus the latest, unread, and orphan lookups a conversation list uses. */
@Dao
interface MessageThreadDao {
    @Query("SELECT * FROM message ORDER BY sortDate")
    suspend fun listAll(): List<MessageEntity>

    @Query("SELECT * FROM message WHERE radioID = :radioID ORDER BY sortDate")
    suspend fun listByRadio(radioID: Uuid): List<MessageEntity>

    @Query("SELECT * FROM message WHERE radioID = :radioID AND channelIndex = :channelIndex ORDER BY sortDate")
    suspend fun listByChannel(radioID: Uuid, channelIndex: Int): List<MessageEntity>

    /**
     * One page of a DM thread, newest first. A reconnect-drained block shares a single `sortDate`, so the
     * send time and the row's creation instant break the tie the way the Swift fetch descriptor does; `id`
     * closes the last gap so a LIMIT/OFFSET window can never re-order between two pages and skip a row.
     */
    @Query(
        "SELECT * FROM message WHERE radioID = :radioID AND contactID = :contactID AND channelIndex IS NULL " +
            "ORDER BY sortDate DESC, timestamp DESC, createdAt DESC, id DESC LIMIT :limit OFFSET :offset",
    )
    suspend fun listDirectNewestFirst(radioID: Uuid, contactID: Uuid, limit: Int, offset: Int): List<MessageEntity>

    @Query(
        "SELECT COUNT(*) FROM message WHERE radioID = :radioID AND contactID = :contactID " +
            "AND channelIndex IS NULL AND sortDate >= :anchor",
    )
    suspend fun countDirectAtOrNewer(radioID: Uuid, contactID: Uuid, anchor: Double): Int

    @Query(
        "SELECT COUNT(*) FROM message WHERE radioID = :radioID AND channelIndex = :channelIndex " +
            "AND sortDate >= :anchor",
    )
    suspend fun countChannelAtOrNewer(radioID: Uuid, channelIndex: Int, anchor: Double): Int

    /** One page of a channel thread, newest first, under the same total order as [listDirectNewestFirst]. */
    @Query(
        "SELECT * FROM message WHERE radioID = :radioID AND channelIndex = :channelIndex " +
            "ORDER BY sortDate DESC, timestamp DESC, createdAt DESC, id DESC LIMIT :limit OFFSET :offset",
    )
    suspend fun listChannelNewestFirst(radioID: Uuid, channelIndex: Int, limit: Int, offset: Int): List<MessageEntity>

    @Query(
        "SELECT * FROM message WHERE radioID = :radioID AND contactID = :contactID AND channelIndex IS NULL " +
            "ORDER BY sortDate DESC LIMIT 1",
    )
    suspend fun latestDirect(radioID: Uuid, contactID: Uuid): MessageEntity?

    /** Newest unread incoming DM for a contact. iOS `newestUnreadIncomingMessage`. */
    @Query(
        "SELECT * FROM message WHERE contactID = :contactID AND channelIndex IS NULL " +
            "AND directionRawValue = :incoming AND isRead = 0 ORDER BY sortDate DESC LIMIT 1",
    )
    suspend fun newestUnreadIncomingDirect(contactID: Uuid, incoming: Int): MessageEntity?

    /**
     * Incoming DMs stored before their sender's contact existed. Prefix match stays in memory
     * (iOS `#Predicate` cannot express `Data.prefix` either).
     */
    @Query(
        "SELECT * FROM message WHERE radioID = :radioID AND contactID IS NULL AND channelIndex IS NULL " +
            "AND directionRawValue = :incoming",
    )
    suspend fun listOrphanIncomingDirect(radioID: Uuid, incoming: Int): List<MessageEntity>
}

/** Dedup, path adoption, and failed-send lookups the ingest and badge paths use. */
@Dao
interface MessageLookupDao {
    @Query("SELECT COUNT(*) FROM message WHERE radioID = :radioID AND deduplicationKey = :deduplicationKey")
    suspend fun countByDedup(radioID: Uuid, deduplicationKey: String): Int

    /**
     * Outgoing channel message matched by radio, slot, wire timestamp, and exact text — the
     * [one.meshcore.shared.chat.pathdetail.HeardRepeatsService] correlation key (iOS
     * `findSentChannelMessage`).
     */
    @Query(
        "SELECT * FROM message WHERE radioID = :radioID AND channelIndex = :channelIndex " +
            "AND timestamp = :timestamp AND directionRawValue = :directionRawValue AND text = :text LIMIT 1",
    )
    suspend fun findSentChannelMessage(
        radioID: Uuid,
        channelIndex: Int,
        timestamp: Long,
        directionRawValue: Int,
        text: String,
    ): MessageEntity?

    @Query(
        "SELECT * FROM message WHERE radioID = :radioID AND deduplicationKey = :deduplicationKey " +
            "AND channelIndex IS NOT NULL AND directionRawValue = :incoming LIMIT 1",
    )
    suspend fun findIncomingChannelByDedup(radioID: Uuid, deduplicationKey: String, incoming: Int): MessageEntity?

    @Query("UPDATE message SET pathNodes = :pathNodes, pathLength = :pathLength WHERE id = :id AND pathNodes IS NULL")
    suspend fun adoptIncomingPathIfUnknown(id: Uuid, pathNodes: ByteArray, pathLength: Int): Int

    @Query(
        "SELECT EXISTS(SELECT 1 FROM message WHERE ackCode = :ackCode AND statusRawValue = :status " +
            "AND directionRawValue = :direction AND channelIndex IS NULL)",
    )
    suspend fun existsOutgoingDMWithAck(ackCode: Long, status: Int, direction: Int): Boolean

    @Query("DELETE FROM message WHERE id = :id")
    suspend fun deleteById(id: Uuid)

    @Query(
        "SELECT DISTINCT contactID FROM message WHERE radioID = :radioID " +
            "AND statusRawValue = :failedStatus AND directionRawValue = :outgoing " +
            "AND contactID IS NOT NULL AND failureSeen = 0",
    )
    suspend fun failedSendContactIds(radioID: Uuid, failedStatus: Int, outgoing: Int): List<Uuid>

    @Query(
        "SELECT DISTINCT channelIndex FROM message WHERE radioID = :radioID " +
            "AND statusRawValue = :failedStatus AND directionRawValue = :outgoing " +
            "AND channelIndex IS NOT NULL AND failureSeen = 0",
    )
    suspend fun failedSendChannelIndexes(radioID: Uuid, failedStatus: Int, outgoing: Int): List<Int>

    @Query(
        "UPDATE message SET failureSeen = 1 WHERE contactID = :contactID " +
            "AND statusRawValue = :failedStatus AND directionRawValue = :outgoing AND failureSeen = 0",
    )
    suspend fun markFailedSendsSeenForContact(contactID: Uuid, failedStatus: Int, outgoing: Int)

    @Query(
        "UPDATE message SET failureSeen = 1 WHERE radioID = :radioID AND channelIndex = :channelIndex " +
            "AND statusRawValue = :failedStatus AND directionRawValue = :outgoing AND failureSeen = 0",
    )
    suspend fun markFailedSendsSeenForChannel(radioID: Uuid, channelIndex: Int, failedStatus: Int, outgoing: Int)
}

/** Wire-timestamp correlation, read and mention flags, and repeat counters. */
@Dao
interface MessageReceiptDao {
    /**
     * Incoming channel messages at the packet's wire timestamp. iOS matches `senderTimestamp` or,
     * when that column is null (uncorrected ingest), `timestamp`. The caller decides which rows
     * still need a region so a live ingest is never overwritten.
     */
    @Query(
        "SELECT * FROM message WHERE radioID = :radioID AND channelIndex = :channelIndex " +
            "AND directionRawValue = :incoming " +
            "AND (senderTimestamp = :wireTimestamp OR (senderTimestamp IS NULL AND timestamp = :wireTimestamp))",
    )
    suspend fun incomingChannelMessagesForWireTimestamp(
        radioID: Uuid,
        channelIndex: Int,
        wireTimestamp: Long,
        incoming: Int,
    ): List<MessageEntity>

    /**
     * Incoming DMs at the packet's wire timestamp. The caller narrows by sender prefix byte and
     * skips rows that already have a region.
     */
    @Query(
        "SELECT * FROM message WHERE radioID = :radioID AND channelIndex IS NULL " +
            "AND directionRawValue = :incoming " +
            "AND (senderTimestamp = :wireTimestamp OR (senderTimestamp IS NULL AND timestamp = :wireTimestamp))",
    )
    suspend fun incomingDirectMessagesForWireTimestamp(
        radioID: Uuid,
        wireTimestamp: Long,
        incoming: Int,
    ): List<MessageEntity>

    /**
     * Incoming DMs received in a wall-clock window. Used when the RX-log packet has hop hashes but
     * no decrypted sender timestamp (flood DMs), matching ingest's 30s prefix fallback in reverse.
     */
    @Query(
        "SELECT * FROM message WHERE radioID = :radioID AND channelIndex IS NULL " +
            "AND directionRawValue = :incoming " +
            "AND createdAt >= :sinceCreatedAt AND createdAt <= :untilCreatedAt",
    )
    suspend fun incomingDirectMessagesAroundCreatedAt(
        radioID: Uuid,
        sinceCreatedAt: Double,
        untilCreatedAt: Double,
        incoming: Int,
    ): List<MessageEntity>

    /** Marks a self-mention as seen once its bubble scrolls into view (iOS `dataStore.markMentionSeen`). */
    @Query("UPDATE message SET mentionSeen = 1 WHERE id = :id")
    suspend fun markMentionSeen(id: Uuid)

    /** iOS `PersistenceStore.markMessageAsRead`. Shade Mark as Read is the writer of per-message `isRead`. */
    @Query("UPDATE message SET isRead = 1 WHERE id = :id")
    suspend fun markMessageAsRead(id: Uuid)

    @Query(
        "SELECT id FROM message WHERE contactID = :contactID AND containsSelfMention = 1 " +
            "AND mentionSeen = 0 ORDER BY timestamp ASC",
    )
    suspend fun unseenDirectMentionIds(contactID: Uuid): List<Uuid>

    @Query(
        "SELECT id FROM message WHERE radioID = :radioID AND channelIndex = :channelIndex " +
            "AND containsSelfMention = 1 AND mentionSeen = 0 ORDER BY timestamp ASC",
    )
    suspend fun unseenChannelMentionIds(radioID: Uuid, channelIndex: Int): List<Uuid>

    @Query(
        "UPDATE message SET mentionSeen = 1 WHERE contactID = :contactID " +
            "AND containsSelfMention = 1 AND mentionSeen = 0",
    )
    suspend fun markDirectMentionsSeen(contactID: Uuid)

    @Query(
        "UPDATE message SET mentionSeen = 1 WHERE radioID = :radioID AND channelIndex = :channelIndex " +
            "AND containsSelfMention = 1 AND mentionSeen = 0",
    )
    suspend fun markChannelMentionsSeen(radioID: Uuid, channelIndex: Int)

    @Query("UPDATE message SET heardRepeats = heardRepeats + 1 WHERE id = :id")
    suspend fun incrementHeardRepeats(id: Uuid)
}

/** Id lists and deletes for clearing a contact, a channel, or a blocked sender. */
@Dao
interface MessageRemovalDao {
    @Query("SELECT heardRepeats FROM message WHERE id = :id")
    suspend fun heardRepeats(id: Uuid): Int?

    @Query("UPDATE message SET sendCount = sendCount + 1 WHERE id = :id")
    suspend fun incrementSendCount(id: Uuid)

    @Query("SELECT sendCount FROM message WHERE id = :id")
    suspend fun sendCount(id: Uuid): Int?

    @Query("SELECT id FROM message WHERE contactID = :contactID AND channelIndex IS NULL")
    suspend fun directMessageIdsForContact(contactID: Uuid): List<Uuid>

    @Query("DELETE FROM message WHERE contactID = :contactID AND channelIndex IS NULL")
    suspend fun deleteDirectByContact(contactID: Uuid)

    /** The ids of one channel slot's messages, so a clear/leave can drop their dependent rows first. */
    @Query("SELECT id FROM message WHERE radioID = :radioID AND channelIndex = :channelIndex")
    suspend fun channelMessageIds(radioID: Uuid, channelIndex: Int): List<Uuid>

    @Query("DELETE FROM message WHERE radioID = :radioID AND channelIndex = :channelIndex")
    suspend fun deleteMessagesForChannel(radioID: Uuid, channelIndex: Int)

    /**
     * Channel-only messages from an exact sender name (null-sender and DM rows excluded). Used by the
     * block-channel-sender cascade.
     */
    @Query(
        "SELECT id FROM message WHERE radioID = :radioID AND channelIndex IS NOT NULL " +
            "AND senderNodeName = :senderName",
    )
    suspend fun channelMessageIdsFromSender(radioID: Uuid, senderName: String): List<Uuid>

    @Query("DELETE FROM message WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Uuid>)
}

@Dao
interface MessageDao :
    MessageThreadDao,
    MessageLookupDao,
    MessageReceiptDao,
    MessageRemovalDao {
    @Query("SELECT * FROM message WHERE id = :id")
    suspend fun getById(id: Uuid): MessageEntity?

    @Upsert
    suspend fun upsert(entity: MessageEntity)

    @Upsert
    suspend fun upsertAll(entities: List<MessageEntity>)

    // The manual-retry replace below spans the queue table, so its three primitives live here: Room's unit
    // of transaction is one DAO method, and the message status flip and the queue row must commit together.

    @Query("DELETE FROM pending_send WHERE messageID = :messageID")
    suspend fun deletePendingSendsForMessage(messageID: Uuid)

    @Query("SELECT MAX(sequence) FROM pending_send WHERE radioID = :radioID")
    suspend fun maxPendingSendSequence(radioID: Uuid): Int?

    @Insert
    suspend fun insertPendingSend(entity: PendingSendEntity)

    /**
     * Manual retry, as one commit: drop the message's parked queue rows, flip it back to `.pending` unless
     * a late ACK already delivered it ([MessageStatusGuards.allowsUnlessDelivered]), and insert [entity]
     * with the next per-radio sequence, which is returned. Process death between the delete and the insert
     * would otherwise leave the message `.pending` with no queue row: it never sends, shows the in-progress
     * glyph, and offers no retry, because retry renders only on `.failed`. The transaction also serializes
     * the sequence read against a concurrent enqueue.
     */
    @Transaction
    suspend fun replacePendingSendForRetry(entity: PendingSendEntity): Int {
        deletePendingSendsForMessage(entity.messageID)
        val message = getById(entity.messageID)
        if (message != null && MessageStatusGuards.allowsUnlessDelivered(message.status)) {
            upsert(message.copy(statusRawValue = MessageStatus.PENDING.raw))
        }
        val sequence = (maxPendingSendSequence(entity.radioID) ?: 0) + 1
        insertPendingSend(entity.copy(sequence = sequence))
        return sequence
    }
}
