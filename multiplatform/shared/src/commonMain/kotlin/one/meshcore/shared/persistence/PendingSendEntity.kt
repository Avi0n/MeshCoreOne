package one.meshcore.shared.persistence

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import one.meshcore.shared.chat.PendingSendDto
import one.meshcore.shared.chat.PendingSendKind
import one.meshcore.shared.model.Uuid

/**
 * Persistence mirror of the SwiftData `PendingSend` model: one durable send-queue row that survives
 * process death so the queue resumes draining after a restart. Single-table discriminated design
 * ([kindRawValue] selects DM vs. channel), matching the Swift decision not to split into two tables.
 *
 * Rows are scoped to a radio via [radioID]. [sequence] is a per-radio monotonic counter assigned at
 * enqueue; the ordered-by-sequence fetch replays rows in their original enqueue order across a restart.
 * [attemptCount] is the nullable three-state column (see [PendingSendDto] and the queue's drain logic):
 * null = pre-migration row (purged on connect), 0 = persisted but not yet drained, positive = drained at
 * least once.
 *
 * The two compound indexes target the hot fetch paths: `(radioID, sequence)` powers the ordered hydrate
 * fetch, `(messageID)` powers the delete-by-message run on every success and terminal-fail.
 */
@Entity(
    tableName = "pending_send",
    indices = [
        Index(value = ["radioID", "sequence"]),
        Index(value = ["messageID"]),
    ],
)
data class PendingSendEntity(
    @PrimaryKey val id: Uuid,
    val radioID: Uuid,
    val messageID: Uuid,
    val kindRawValue: Int,
    val isResend: Boolean,
    val messageText: String,
    val messageTimestamp: Long,
    val sequence: Int,
    val enqueuedAt: Double,
    val contactID: Uuid? = null,
    val channelIndex: Int? = null,
    val localNodeName: String? = null,
    val attemptCount: Int? = null,
)

/**
 * The DTO for this row, or null when [kindRawValue] does not resolve to a known [PendingSendKind]. Such
 * a row could only exist after a downgrade from a future build that added a case; the caller skips it
 * rather than routing it to the wrong queue.
 */
fun PendingSendEntity.toDtoOrNull(): PendingSendDto? {
    val kind = PendingSendKind.fromRaw(kindRawValue) ?: return null
    return PendingSendDto(
        id = id,
        radioID = radioID,
        messageID = messageID,
        kind = kind,
        sequence = sequence,
        enqueuedAtEpochSeconds = enqueuedAt,
        contactID = contactID,
        channelIndex = channelIndex,
        isResend = isResend,
        messageText = messageText,
        messageTimestamp = messageTimestamp,
        localNodeName = localNodeName,
        attemptCount = attemptCount,
    )
}

/** The Room row for a DTO. Forwards [PendingSendDto.attemptCount] verbatim so the three-state column is preserved. */
fun PendingSendDto.toEntity(): PendingSendEntity = PendingSendEntity(
    id = id,
    radioID = radioID,
    messageID = messageID,
    kindRawValue = kind.raw,
    isResend = isResend,
    messageText = messageText,
    messageTimestamp = messageTimestamp,
    sequence = sequence,
    enqueuedAt = enqueuedAtEpochSeconds,
    contactID = contactID,
    channelIndex = channelIndex,
    localNodeName = localNodeName,
    attemptCount = attemptCount,
)
