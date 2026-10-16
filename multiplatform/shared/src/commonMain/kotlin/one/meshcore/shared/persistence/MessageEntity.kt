package one.meshcore.shared.persistence

import androidx.room.Entity
import androidx.room.PrimaryKey
import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.RouteType
import one.meshcore.shared.model.TextType
import one.meshcore.shared.model.Uuid

/**
 * Persistence mirror of the SwiftData `Message` model. Direction, status, text type, and route type
 * are stored as their raw columns with typed accessors below; `routeTypeRawValue` keeps the `-1`
 * uncorrelated sentinel.
 */
@Entity(tableName = "message")
data class MessageEntity(
    @PrimaryKey val id: Uuid,
    val radioID: Uuid,
    val text: String,
    val timestamp: Long,
    val createdAt: Double,
    val sortDate: Double,
    val directionRawValue: Int,
    val statusRawValue: Int,
    val textTypeRawValue: Int,
    val pathLength: Int,
    val isRead: Boolean,
    val heardRepeats: Int,
    val sendCount: Int,
    val retryAttempt: Int,
    val maxRetryAttempts: Int,
    val linkPreviewFetched: Boolean,
    val containsSelfMention: Boolean,
    val mentionSeen: Boolean,
    val failureSeen: Boolean = false,
    val timestampCorrected: Boolean,
    val routeTypeRawValue: Int = -1,
    val contactID: Uuid? = null,
    val channelIndex: Int? = null,
    val ackCode: Long? = null,
    val snr: Double? = null,
    val pathNodes: ByteArray? = null,
    val senderKeyPrefix: ByteArray? = null,
    val senderNodeName: String? = null,
    val replyToID: Uuid? = null,
    val roundTripTime: Long? = null,
    val deduplicationKey: String? = null,
    val linkPreviewURL: String? = null,
    val linkPreviewTitle: String? = null,
    val linkPreviewImageData: ByteArray? = null,
    val linkPreviewIconData: ByteArray? = null,
    val senderTimestamp: Long? = null,
    val reactionSummary: String? = null,
    val regionScope: String? = null,
    val regionScopeMatches: List<String> = emptyList(),
)

val MessageEntity.direction: MessageDirection
    get() = MessageDirection.fromRaw(directionRawValue) ?: MessageDirection.OUTGOING

val MessageEntity.status: MessageStatus
    get() = MessageStatus.fromRaw(statusRawValue) ?: MessageStatus.PENDING

val MessageEntity.textType: TextType
    get() = TextType.fromRaw(textTypeRawValue) ?: TextType.PLAIN

/** Route type from RxLog correlation; null for the `-1` uncorrelated sentinel or unknown raws. */
val MessageEntity.routeType: RouteType?
    get() = RouteType.fromRaw(routeTypeRawValue)
