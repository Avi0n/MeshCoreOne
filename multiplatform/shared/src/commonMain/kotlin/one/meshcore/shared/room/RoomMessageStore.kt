package one.meshcore.shared.room

import one.meshcore.shared.backup.RoomMessageDto
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.Uuid

/** Room-message reads and writes on [RoomStore]. */
interface RoomMessageStore {
    /** Inserts or updates a room message by its id. */
    suspend fun saveRoomMessage(dto: RoomMessageDto)

    /** The room message with local id [id], or null. */
    suspend fun fetchRoomMessage(id: Uuid): RoomMessageDto?

    /** Room messages for [sessionID], optionally windowed by [limit]/[offset]. */
    suspend fun fetchRoomMessages(sessionID: Uuid, limit: Int? = null, offset: Int? = null): List<RoomMessageDto>

    /** Updates a message's send outcome: [status] plus the ACK metadata (null when there was no ACK). */
    suspend fun updateRoomMessageStatus(id: Uuid, status: MessageStatus, ackCode: Long?, roundTripTime: Long?)

    /** Updates a message's retry bookkeeping when a failed message is re-sent. */
    suspend fun updateRoomMessageRetryStatus(id: Uuid, status: MessageStatus, retryAttempt: Int, maxRetryAttempts: Int)

    /** Whether [sessionID] already holds a message with [deduplicationKey]. */
    suspend fun isDuplicateRoomMessage(sessionID: Uuid, deduplicationKey: String): Boolean
}
