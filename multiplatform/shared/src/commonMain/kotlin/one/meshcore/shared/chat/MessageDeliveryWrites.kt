package one.meshcore.shared.chat

import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.Uuid

/** Guarded status, ACK, and repeat writes on [MessageStore]. */
interface MessageDeliveryWrites {
    /** Sets status unconditionally (used only where the caller already owns terminal safety). */
    suspend fun updateMessageStatus(id: Uuid, status: MessageStatus)

    /**
     * Sets status unless delivery already won ([MessageStatusGuards.allowsUnlessDelivered]). Returns
     * true only when a row was actually changed, so callers can gate a `.failed` broadcast on the write.
     */
    suspend fun updateMessageStatusUnlessDelivered(id: Uuid, status: MessageStatus): Boolean

    /**
     * Rolls a retry-loop status back to `.sent` when the retry budget is spent but the ACK may still
     * arrive. Set [onlyRetrying] during retirement to preserve an unacknowledged pending row.
     * Terminal-safe ([MessageStatusGuards.allowsClearRetryingToSent]); returns true if moved.
     */
    suspend fun clearRetryingToSent(id: Uuid, onlyRetrying: Boolean = false): Boolean

    /** Sets `.retrying` status plus attempt counters, skipping a terminal row. */
    suspend fun updateMessageRetryStatus(id: Uuid, status: MessageStatus, retryAttempt: Int, maxRetryAttempts: Int)

    /**
     * Writes the ACK code and status. Preserves a terminal row against a differing status
     * ([MessageStatusGuards.allowsAckWrite]); the only late transition allowed is `.sent` -> `.delivered`.
     */
    suspend fun updateMessageAck(id: Uuid, ackCode: Long, status: MessageStatus, roundTripTime: Long? = null)

    /** Replaces the wire timestamp (used when a resend must hash distinctly from the failed packet). */
    suspend fun updateMessageTimestamp(id: Uuid, timestamp: Long)

    /** Increments and returns the row's send count; returns 0 if the row is gone. */
    suspend fun incrementMessageSendCount(id: Uuid): Int

    /** Sets the heard-repeat count (reset to 0 on a channel resend). */
    suspend fun updateMessageHeardRepeats(id: Uuid, heardRepeats: Int)

    /** Deletes any stored mesh-repeat rows for a message (a resend starts its repeat count fresh). */
    suspend fun deleteMessageRepeats(messageID: Uuid)
}
