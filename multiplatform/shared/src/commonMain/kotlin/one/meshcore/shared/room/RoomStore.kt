package one.meshcore.shared.room

import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.model.Uuid

/**
 * The persistence seam [RoomServerService] and [RoomAdminService] depend on: room-message reads/writes,
 * the room-session mutations the post/receive paths issue, and the contact lookups author resolution and
 * history sync need. Room messages live on [RoomMessageStore].
 * [one.meshcore.shared.persistence.RoomNodeStore] provides the production adapter. Reproduces the subset
 * of the Swift `PersistenceStoreProtocol` the room services call.
 *
 * Session rows are shared with the remote-node login path (login flips `isConnected` there); an
 * implementation therefore backs both this seam and `RemoteNodeStore` over one session table.
 */
interface RoomStore : RoomMessageStore {
    // MARK: Session reads

    /** The session with local id [id], or null. */
    suspend fun fetchSession(id: Uuid): RemoteNodeSessionDto?

    /** The session whose public key starts with [prefix], or null. */
    suspend fun fetchSessionByPrefix(prefix: ByteArray): RemoteNodeSessionDto?

    /** Every session scoped to [radioID]. */
    suspend fun fetchSessions(radioID: Uuid): List<RemoteNodeSessionDto>

    // MARK: Session mutations

    /**
     * Marks a disconnected session connected because inbound traffic proves it is live. Returns true when a
     * transition happened (was disconnected), false when already connected or absent — the caller emits
     * `connectionRecovered` only on a true transition.
     */
    suspend fun markRoomSessionConnected(id: Uuid): Boolean

    /**
     * Advances a room's activity. Always bumps the local sort date; when [syncTimestamp] is non-null (an
     * inbound message) it also advances the sender-clock sync watermark, never rewinds it. A post passes
     * null so a local send never moves the watermark (avoids clock-skew gaps).
     */
    suspend fun updateRoomActivity(id: Uuid, syncTimestamp: Long? = null)

    /** Increments a room session's unread counter. */
    suspend fun incrementRoomUnreadCount(id: Uuid)

    /** Resets a room session's unread counter to zero. */
    suspend fun resetRoomUnreadCount(id: Uuid)

    /**
     * Marks current self `.failed` rows in [sessionID] as seen so the chats-list badge does not
     * reappear after the user opened the room. Mirrors iOS `markFailedSendsSeen(roomSessionID:)`.
     */
    suspend fun markFailedSendsSeen(sessionID: Uuid) {}

    // MARK: Contacts

    /** The display name of a contact whose public key starts with [keyPrefix], or null if unknown. */
    suspend fun findContactNameByKeyPrefix(keyPrefix: ByteArray): String?

    /**
     * The contact's routing for a history-sync direct-path check: true when flood-routed, false when
     * directly routed, or null when no contact row exists for `(radioID, publicKey)`.
     */
    suspend fun contactFloodRouted(radioID: Uuid, publicKey: ByteArray): Boolean?
}

/**
 * Sender-clock watermark for keep-alive `sync_since`. An inbound stamp only advances; an older or omitted
 * stamp leaves [current] so an out-of-order dump cannot rewind the radio bookmark. Mirrors iOS
 * `PersistenceStore.updateRoomActivity`.
 */
internal fun advancedRoomSyncTimestamp(current: Long, incoming: Long?): Long =
    if (incoming != null && incoming > current) incoming else current
