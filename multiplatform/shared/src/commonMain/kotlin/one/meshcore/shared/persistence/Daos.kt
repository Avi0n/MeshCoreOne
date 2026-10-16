package one.meshcore.shared.persistence

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import one.meshcore.shared.contact.ContactFrame
import one.meshcore.shared.contact.ContactUpsert
import one.meshcore.shared.model.Uuid

// Primitive per-partition reads and writes used by the service layer's merge, relocation, and
// ghost-reconciliation operations.

@Dao
interface DeviceDao {
    @Query("SELECT * FROM device")
    suspend fun listAll(): List<DeviceEntity>

    @Query("SELECT * FROM device WHERE radioID = :radioID")
    suspend fun listByRadio(radioID: Uuid): List<DeviceEntity>

    @Query("SELECT nodeName FROM device WHERE radioID = :radioID LIMIT 1")
    suspend fun nodeNameByRadio(radioID: Uuid): String?

    @Query("SELECT * FROM device WHERE id = :id")
    suspend fun getById(id: Uuid): DeviceEntity?

    @Upsert
    suspend fun upsert(entity: DeviceEntity)

    @Upsert
    suspend fun upsertAll(entities: List<DeviceEntity>)

    @Query("DELETE FROM device WHERE id = :id")
    suspend fun deleteById(id: Uuid)
}

@Dao
@Suppress("TooManyFunctions")
interface ContactDao {
    @Query("SELECT * FROM contact")
    suspend fun listAll(): List<ContactEntity>

    @Query("SELECT * FROM contact WHERE radioID = :radioID")
    suspend fun listByRadio(radioID: Uuid): List<ContactEntity>

    @Query("SELECT * FROM contact WHERE id = :id")
    suspend fun getById(id: Uuid): ContactEntity?

    @Query(
        "SELECT * FROM contact WHERE radioID = :radioID AND lastMessageDate IS NOT NULL " +
            "ORDER BY lastMessageDate DESC",
    )
    suspend fun listConversations(radioID: Uuid): List<ContactEntity>

    @Query("SELECT * FROM contact WHERE radioID = :radioID AND isBlocked = 1 ORDER BY name")
    suspend fun listBlocked(radioID: Uuid): List<ContactEntity>

    // Badge source: only rows a user can actually reach. Repeaters are filtered out of the chats list, so
    // unread on them must not inflate the badge.
    @Query(
        "SELECT * FROM contact WHERE radioID = :radioID AND unreadCount > 0 AND isMuted = 0 " +
            "AND isBlocked = 0 AND typeRawValue != :repeaterTypeRawValue",
    )
    suspend fun listUnreadForBadge(radioID: Uuid, repeaterTypeRawValue: Int): List<ContactEntity>

    @Query("DELETE FROM contact WHERE id = :id")
    suspend fun deleteById(id: Uuid)

    @Upsert
    suspend fun upsert(entity: ContactEntity)

    @Upsert
    suspend fun upsertAll(entities: List<ContactEntity>)

    @Query("UPDATE contact SET isPinned = :isPinned WHERE id = :id")
    suspend fun setPinned(id: Uuid, isPinned: Boolean): Int

    @Transaction
    suspend fun saveContactFrame(radioID: Uuid, frame: ContactFrame, owner: Job, newId: () -> Uuid): Uuid {
        owner.ensureActive()
        val existing = listByRadio(radioID).firstOrNull { it.publicKey.contentEquals(frame.publicKey) }?.toDto()
        owner.ensureActive()
        val row = if (existing != null) {
            ContactUpsert.merge(existing, frame)
        } else {
            ContactUpsert.insert(radioID = radioID, id = newId(), frame = frame)
        }
        upsertPreservingPin(row.toEntity())
        owner.ensureActive()
        return row.id
    }

    @Transaction
    suspend fun updateChatContactFields(
        id: Uuid,
        owner: Job,
        transform: (ContactEntity) -> ContactEntity?,
    ): ContactEntity? {
        owner.ensureActive()
        val current = getById(id)
        owner.ensureActive()
        if (current == null) return null
        val updated = transform(current)
        owner.ensureActive()
        if (updated != null) upsertPreservingPin(updated)
        owner.ensureActive()
        return updated ?: current
    }

    @Transaction
    suspend fun recomputeContactLastMessageDate(radioID: Uuid, id: Uuid, messages: MessageDao, owner: Job) {
        owner.ensureActive()
        val current = getById(id)
        owner.ensureActive()
        if (current == null) return
        val latest = messages.latestDirect(radioID, id)?.sortDate
        owner.ensureActive()
        upsertPreservingPin(current.copy(lastMessageDate = latest))
        owner.ensureActive()
    }

    @Transaction
    suspend fun upsertPreservingPin(entity: ContactEntity) {
        val current = getById(entity.id)
        upsert(entity.copy(isPinned = current?.isPinned ?: entity.isPinned))
    }
}

@Dao
interface ChannelDao {
    @Query("SELECT * FROM channel")
    suspend fun listAll(): List<ChannelEntity>

    @Query("SELECT * FROM channel WHERE radioID = :radioID ORDER BY `index`")
    suspend fun listByRadio(radioID: Uuid): List<ChannelEntity>

    @Query("SELECT * FROM channel WHERE id = :id")
    suspend fun getById(id: Uuid): ChannelEntity?

    @Query("SELECT * FROM channel WHERE radioID = :radioID AND `index` = :index LIMIT 1")
    suspend fun getByRadioAndIndex(radioID: Uuid, index: Int): ChannelEntity?

    // Badge source. The muted filter runs in Kotlin over the typed accessor, so the unmigrated `-1`
    // sentinel resolves the same way the rest of the app reads it.
    @Query("SELECT * FROM channel WHERE radioID = :radioID AND (unreadCount > 0 OR unreadMentionCount > 0)")
    suspend fun listUnreadForBadge(radioID: Uuid): List<ChannelEntity>

    @Query("DELETE FROM channel WHERE id = :id")
    suspend fun deleteById(id: Uuid)

    @Upsert
    suspend fun upsert(entity: ChannelEntity)

    @Upsert
    suspend fun upsertAll(entities: List<ChannelEntity>)

    @Query("UPDATE channel SET isPinned = :isPinned WHERE id = :id")
    suspend fun setPinned(id: Uuid, isPinned: Boolean): Int

    @Transaction
    suspend fun upsertPreservingPin(entity: ChannelEntity) {
        val current = getById(entity.id)
        upsert(entity.copy(isPinned = current?.isPinned ?: entity.isPinned))
    }
}

@Dao
interface MessageRepeatDao {
    @Query("SELECT * FROM message_repeat")
    suspend fun listAll(): List<MessageRepeatEntity>

    @Query("SELECT * FROM message_repeat WHERE messageID = :messageID ORDER BY receivedAt")
    suspend fun listByMessage(messageID: Uuid): List<MessageRepeatEntity>

    /** Dedup guard for heard-repeat processing keyed by the RX-log entry that produced the echo. */
    @Query("SELECT EXISTS(SELECT 1 FROM message_repeat WHERE rxLogEntryID = :rxLogEntryID)")
    suspend fun existsByRxLogEntryId(rxLogEntryID: Uuid): Boolean

    @Query("DELETE FROM message_repeat WHERE messageID = :messageID")
    suspend fun deleteByMessage(messageID: Uuid)

    @Query("DELETE FROM message_repeat WHERE messageID IN (:ids)")
    suspend fun deleteByMessageIds(ids: List<Uuid>)

    @Upsert
    suspend fun upsert(entity: MessageRepeatEntity)

    @Upsert
    suspend fun upsertAll(entities: List<MessageRepeatEntity>)
}

@Dao
interface ReactionDao {
    @Query("SELECT * FROM reaction")
    suspend fun listAll(): List<ReactionEntity>

    @Query("SELECT * FROM reaction WHERE radioID = :radioID")
    suspend fun listByRadio(radioID: Uuid): List<ReactionEntity>

    @Query("SELECT * FROM reaction WHERE messageID = :messageID")
    suspend fun listByMessage(messageID: Uuid): List<ReactionEntity>

    @Query("DELETE FROM reaction WHERE messageID IN (:ids)")
    suspend fun deleteByMessageIds(ids: List<Uuid>)

    @Query("DELETE FROM reaction WHERE contactID = :contactID")
    suspend fun deleteByContact(contactID: Uuid)

    @Query("DELETE FROM reaction WHERE radioID = :radioID")
    suspend fun deleteByRadio(radioID: Uuid)

    @Upsert
    suspend fun upsert(entity: ReactionEntity)

    @Upsert
    suspend fun upsertAll(entities: List<ReactionEntity>)
}

@Dao
interface RoomMessageDao {
    @Query("SELECT * FROM room_message ORDER BY timestamp")
    suspend fun listAll(): List<RoomMessageEntity>

    @Query("SELECT * FROM room_message WHERE sessionID = :sessionID ORDER BY timestamp")
    suspend fun listBySession(sessionID: Uuid): List<RoomMessageEntity>

    @Query("DELETE FROM room_message WHERE sessionID = :sessionID")
    suspend fun deleteBySession(sessionID: Uuid)

    @Query(
        "SELECT DISTINCT sessionID FROM room_message WHERE sessionID IN (:sessionIDs) " +
            "AND statusRawValue = :failedStatus AND isFromSelf = 1 AND failureSeen = 0",
    )
    suspend fun failedSendSessionIds(sessionIDs: List<Uuid>, failedStatus: Int): List<Uuid>

    @Query(
        "UPDATE room_message SET failureSeen = 1 WHERE sessionID = :sessionID " +
            "AND statusRawValue = :failedStatus AND isFromSelf = 1 AND failureSeen = 0",
    )
    suspend fun markFailedSendsSeenForSession(sessionID: Uuid, failedStatus: Int)

    @Upsert
    suspend fun upsert(entity: RoomMessageEntity)

    @Upsert
    suspend fun upsertAll(entities: List<RoomMessageEntity>)
}

@Dao
@Suppress("TooManyFunctions")
interface RemoteNodeSessionDao {
    @Query("SELECT * FROM remote_node_session WHERE radioID = :radioID")
    suspend fun listByRadio(radioID: Uuid): List<RemoteNodeSessionEntity>

    @Query("SELECT * FROM remote_node_session WHERE id = :id")
    suspend fun getById(id: Uuid): RemoteNodeSessionEntity?

    // The by-public-key, by-prefix, and connected reads filter this in Kotlin (a byte prefix has no
    // portable SQL form); the session table is small (one row per saved repeater/room).
    @Query("SELECT * FROM remote_node_session")
    suspend fun getAll(): List<RemoteNodeSessionEntity>

    // Badge source: room-server sessions only. Repeater-role admin sessions are filtered out of the chats
    // list and would otherwise be unreachable badge contributors.
    @Query(
        "SELECT * FROM remote_node_session WHERE radioID = :radioID AND unreadCount > 0 " +
            "AND roleRawValue = :roomServerRoleRawValue",
    )
    suspend fun listUnreadForBadge(radioID: Uuid, roomServerRoleRawValue: Int): List<RemoteNodeSessionEntity>

    @Query("DELETE FROM remote_node_session WHERE id = :id")
    suspend fun deleteById(id: Uuid)

    @Upsert
    suspend fun upsert(entity: RemoteNodeSessionEntity)

    @Upsert
    suspend fun upsertAll(entities: List<RemoteNodeSessionEntity>)

    @Query("UPDATE remote_node_session SET isPinned = :isPinned WHERE id = :id")
    suspend fun setPinned(id: Uuid, isPinned: Boolean): Int

    @Query("UPDATE remote_node_session SET isConnected = 0 WHERE id = :id")
    suspend fun updateDisconnected(id: Uuid)

    @Transaction
    suspend fun markDisconnectedIfActive(id: Uuid, owner: Job) {
        owner.ensureActive()
        updateDisconnected(id)
    }

    @Query(
        "UPDATE remote_node_session SET isConnected = :isConnected, " +
            "permissionLevelRawValue = :permissionLevel, " +
            "lastConnectedDate = CASE WHEN :isConnected THEN :connectedDate ELSE lastConnectedDate END " +
            "WHERE id = :id",
    )
    suspend fun updateConnectionFields(id: Uuid, isConnected: Boolean, permissionLevel: Int, connectedDate: Double?)

    @Transaction
    suspend fun updateConnectionIfActive(
        id: Uuid,
        isConnected: Boolean,
        permissionLevel: Int,
        connectedDate: Double?,
        owner: Job,
    ) {
        owner.ensureActive()
        updateConnectionFields(id, isConnected, permissionLevel, connectedDate)
    }

    @Transaction
    suspend fun upsertPreservingPin(entity: RemoteNodeSessionEntity) {
        val current = getById(entity.id)
        upsert(entity.copy(isPinned = current?.isPinned ?: entity.isPinned))
    }
}

@Dao
interface SavedTracePathDao {
    @Query("SELECT * FROM saved_trace_path ORDER BY createdDate DESC")
    suspend fun listAll(): List<SavedTracePathEntity>

    @Query("SELECT * FROM saved_trace_path WHERE radioID = :radioID ORDER BY createdDate DESC")
    suspend fun listByRadio(radioID: Uuid): List<SavedTracePathEntity>

    @Query("SELECT * FROM saved_trace_path WHERE id = :id LIMIT 1")
    suspend fun getById(id: Uuid): SavedTracePathEntity?

    @Query("UPDATE saved_trace_path SET name = :name WHERE id = :id")
    suspend fun updateName(id: Uuid, name: String)

    @Query("DELETE FROM saved_trace_path WHERE id = :id")
    suspend fun deleteById(id: Uuid)

    @Upsert
    suspend fun upsert(entity: SavedTracePathEntity)

    @Upsert
    suspend fun upsertAll(entities: List<SavedTracePathEntity>)
}

@Dao
interface TracePathRunDao {
    @Query("SELECT * FROM trace_path_run WHERE savedPathID = :savedPathID ORDER BY date")
    suspend fun listByPath(savedPathID: Uuid): List<TracePathRunEntity>

    /** Every run id store-wide, used by backup import to dedup runs across all saved paths. */
    @Query("SELECT id FROM trace_path_run")
    suspend fun listAllIds(): List<Uuid>

    @Query("DELETE FROM trace_path_run WHERE savedPathID = :savedPathID")
    suspend fun deleteByPath(savedPathID: Uuid)

    @Upsert
    suspend fun upsert(entity: TracePathRunEntity)

    @Upsert
    suspend fun upsertAll(entities: List<TracePathRunEntity>)
}

@Dao
interface BlockedChannelSenderDao {
    @Query("SELECT * FROM blocked_channel_sender")
    suspend fun listAll(): List<BlockedChannelSenderEntity>

    @Query("SELECT * FROM blocked_channel_sender WHERE radioID = :radioID")
    suspend fun listByRadio(radioID: Uuid): List<BlockedChannelSenderEntity>

    @Query("DELETE FROM blocked_channel_sender WHERE radioID = :radioID AND name = :name")
    suspend fun delete(radioID: Uuid, name: String)

    @Upsert
    suspend fun upsert(entity: BlockedChannelSenderEntity)

    @Upsert
    suspend fun upsertAll(entities: List<BlockedChannelSenderEntity>)
}

@Dao
interface NodeStatusSnapshotDao {
    @Query("SELECT * FROM node_status_snapshot ORDER BY timestamp")
    suspend fun listAll(): List<NodeStatusSnapshotEntity>

    @Query("SELECT * FROM node_status_snapshot WHERE nodePublicKey = :nodePublicKey ORDER BY timestamp")
    suspend fun listByNode(nodePublicKey: ByteArray): List<NodeStatusSnapshotEntity>

    @Query("SELECT * FROM node_status_snapshot WHERE id = :id LIMIT 1")
    suspend fun getById(id: Uuid): NodeStatusSnapshotEntity?

    @Query("DELETE FROM node_status_snapshot WHERE timestamp < :cutoff")
    suspend fun deleteOlderThan(cutoff: Double)

    @Query("DELETE FROM node_status_snapshot WHERE nodePublicKey = :nodePublicKey")
    suspend fun deleteByNodePublicKey(nodePublicKey: ByteArray)

    @Upsert
    suspend fun upsert(entity: NodeStatusSnapshotEntity)

    @Upsert
    suspend fun upsertAll(entities: List<NodeStatusSnapshotEntity>)
}

@Dao
interface DiscoveredNodeDao {
    @Query("SELECT * FROM discovered_node")
    suspend fun listAll(): List<DiscoveredNodeEntity>

    @Query("SELECT * FROM discovered_node WHERE radioID = :radioID")
    suspend fun listByRadio(radioID: Uuid): List<DiscoveredNodeEntity>

    @Query("SELECT COUNT(*) FROM discovered_node WHERE radioID = :radioID")
    suspend fun countByRadio(radioID: Uuid): Int

    @Query("SELECT * FROM discovered_node WHERE radioID = :radioID AND publicKey = :publicKey LIMIT 1")
    suspend fun find(radioID: Uuid, publicKey: ByteArray): DiscoveredNodeEntity?

    @Query("DELETE FROM discovered_node WHERE radioID = :radioID")
    suspend fun deleteByRadio(radioID: Uuid)

    @Query("DELETE FROM discovered_node WHERE radioID = :radioID AND publicKey = :publicKey")
    suspend fun delete(radioID: Uuid, publicKey: ByteArray)

    @Query("DELETE FROM discovered_node WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Uuid>)

    @Upsert
    suspend fun upsert(entity: DiscoveredNodeEntity)
}

@Dao
interface RxLogEntryDao {
    @Query("SELECT DISTINCT radioID FROM rx_log_entry")
    suspend fun listRadioIds(): List<Uuid>

    @Query(
        "SELECT * FROM rx_log_entry WHERE radioID = :radioID ORDER BY receivedAtEpochMs DESC LIMIT :limit",
    )
    suspend fun listNewestFirst(radioID: Uuid, limit: Int): List<RxLogEntryEntity>

    @Query("SELECT COUNT(*) FROM rx_log_entry WHERE radioID = :radioID")
    suspend fun countByRadio(radioID: Uuid): Int

    /** The logged packet a channel message arrived on. */
    @Query(
        "SELECT * FROM rx_log_entry WHERE radioID = :radioID AND channelIndex = :channelIndex " +
            "AND senderTimestamp = :senderTimestamp ORDER BY receivedAtEpochMs DESC LIMIT 1",
    )
    suspend fun findChannelPacket(radioID: Uuid, channelIndex: Int, senderTimestamp: Long): RxLogEntryEntity?

    /** The logged packet a direct message arrived on, when the row already carries a sender timestamp. */
    @Query(
        "SELECT * FROM rx_log_entry WHERE radioID = :radioID AND channelIndex IS NULL " +
            "AND senderTimestamp = :senderTimestamp ORDER BY receivedAtEpochMs DESC LIMIT 1",
    )
    suspend fun findDirectPacket(radioID: Uuid, senderTimestamp: Long): RxLogEntryEntity?

    /**
     * Recent direct-message packets, newest first, for the fallback that runs when a row has no sender
     * timestamp yet. The caller narrows these by the sender prefix byte inside the packet payload.
     */
    @Query(
        "SELECT * FROM rx_log_entry WHERE radioID = :radioID AND channelIndex IS NULL " +
            "AND receivedAtEpochMs >= :sinceEpochMs ORDER BY receivedAtEpochMs DESC",
    )
    suspend fun findRecentDirectPackets(radioID: Uuid, sinceEpochMs: Long): List<RxLogEntryEntity>

    /** Oldest ids for one partition, the delete window of the retention prune. */
    @Query("SELECT id FROM rx_log_entry WHERE radioID = :radioID ORDER BY receivedAtEpochMs ASC LIMIT :limit")
    suspend fun oldestIdsByRadio(radioID: Uuid, limit: Int): List<Uuid>

    @Query("DELETE FROM rx_log_entry WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Uuid>)

    @Query("DELETE FROM rx_log_entry WHERE radioID = :radioID")
    suspend fun deleteByRadio(radioID: Uuid)

    @Upsert
    suspend fun upsert(entity: RxLogEntryEntity)
}

@Dao
interface LinkPreviewDao {
    @Query("SELECT * FROM link_preview WHERE url = :url LIMIT 1")
    suspend fun fetch(url: String): LinkPreviewEntity?

    @Upsert
    suspend fun upsert(entity: LinkPreviewEntity)
}

@Dao
interface DebugLogEntryDao {
    @Insert
    suspend fun insertAll(entities: List<DebugLogEntryEntity>)

    @Query(
        """
        SELECT * FROM debug_log_entry
        WHERE timestamp >= :since
        ORDER BY timestamp DESC
        LIMIT :limit
        """,
    )
    suspend fun fetchSince(since: Long, limit: Int): List<DebugLogEntryEntity>

    @Query("SELECT COUNT(*) FROM debug_log_entry")
    suspend fun count(): Int

    @Query(
        """
        SELECT * FROM debug_log_entry
        ORDER BY timestamp ASC
        LIMIT :limit
        """,
    )
    suspend fun fetchOldest(limit: Int): List<DebugLogEntryEntity>

    @Delete
    suspend fun delete(entities: List<DebugLogEntryEntity>)

    @Query("DELETE FROM debug_log_entry WHERE timestamp < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    @Query("DELETE FROM debug_log_entry")
    suspend fun clear()
}
