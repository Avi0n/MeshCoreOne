package one.meshcore.shared.persistence

import androidx.room.Entity
import androidx.room.PrimaryKey
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.Uuid

/**
 * Persistence mirror of the SwiftData `RoomMessage` model, keyed to its session by [sessionID].
 * Delivery status is the raw [statusRawValue] with the typed [RoomMessageEntity.status] accessor.
 */
@Entity(tableName = "room_message")
data class RoomMessageEntity(
    @PrimaryKey val id: Uuid,
    val sessionID: Uuid,
    val authorKeyPrefix: ByteArray,
    val text: String,
    val timestamp: Long,
    val createdAt: Double,
    val isFromSelf: Boolean,
    val deduplicationKey: String,
    val statusRawValue: Int = MessageStatus.DELIVERED.raw,
    val retryAttempt: Int = 0,
    val maxRetryAttempts: Int = 0,
    val failureSeen: Boolean = false,
    val authorName: String? = null,
    val ackCode: Long? = null,
    val roundTripTime: Long? = null,
)

/** Typed delivery status; unknown raws fall back to `DELIVERED`, matching Swift. */
val RoomMessageEntity.status: MessageStatus
    get() = MessageStatus.fromRaw(statusRawValue) ?: MessageStatus.DELIVERED
