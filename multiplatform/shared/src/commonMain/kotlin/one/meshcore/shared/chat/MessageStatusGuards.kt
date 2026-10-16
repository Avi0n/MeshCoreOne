package one.meshcore.shared.chat

import one.meshcore.shared.model.MessageStatus

/**
 * Pure predicates for the central message-status transition contract. Each guard answers "may this write
 * proceed?" given the row's current status; the storage layer ([MessageStore]) and in-place coordinator
 * updates delegate here so the rules live in one
 * tested place. Mirrors the guards inlined across the Swift `PersistenceStore` message methods and
 * `ChatCoordinator.applyStatusUpdate`.
 */
object MessageStatusGuards {
    /**
     * `updateMessageStatusUnlessDelivered`: a write proceeds unless delivery already won. `.delivered`
     * is monotonic, so a late `.sent`/`.failed` over a delivered row is dropped.
     */
    fun allowsUnlessDelivered(current: MessageStatus): Boolean = current != MessageStatus.DELIVERED

    /**
     * `clearRetryingToSent`: rolls a retry-loop status back to `.sent` only from a non-terminal row.
     * Terminal-safe: no-ops on `.delivered` (never downgrade) and `.failed` (never resurrect a
     * checker-failed row).
     */
    fun allowsClearRetryingToSent(current: MessageStatus, onlyRetrying: Boolean = false): Boolean =
        if (onlyRetrying) current == MessageStatus.RETRYING else allowsRetryStatus(current)

    /**
     * `updateMessageRetryStatus`: skips a terminal row so a stale retry iteration cannot clobber a
     * winning ACK nor resurrect a checker-failed row.
     */
    fun allowsRetryStatus(current: MessageStatus): Boolean =
        current != MessageStatus.DELIVERED && current != MessageStatus.FAILED

    /**
     * `updateMessageAck`: both `.delivered` and `.failed` are terminal. A write is refused when the row
     * is terminal and the incoming status differs, so a late `.sent` cannot downgrade a delivered row
     * and a late ACK cannot flip a just-failed row to `.delivered`. The only late transition allowed is
     * the legitimate `.sent` -> `.delivered` upgrade (current `.sent`, not terminal).
     */
    fun allowsAckWrite(current: MessageStatus, incoming: MessageStatus): Boolean {
        val terminal = current == MessageStatus.DELIVERED || current == MessageStatus.FAILED
        return !(terminal && incoming != current)
    }

    /**
     * In-place `applyStatusUpdate` for event-stream status resolutions. Blocks a `.delivered`
     * downgrade to any other status unless [userInitiated] (a user resend flips delivered back to
     * pending while retransmitting), and blocks `.failed` -> `.pending` unless [userInitiated] (an
     * event-stream resolution never carries `.pending`, so the default suppresses a stale-event
     * flicker after the queue marked the row terminal).
     */
    fun allowsInPlaceUpdate(current: MessageStatus, incoming: MessageStatus, userInitiated: Boolean): Boolean {
        if (userInitiated) return true
        if (current == MessageStatus.DELIVERED && incoming != MessageStatus.DELIVERED) return false
        if (current == MessageStatus.FAILED && incoming == MessageStatus.PENDING) return false
        return true
    }

    /**
     * A new `.failed` write (not an already-failed idempotent write) reopens the list badge.
     * Matches iOS `updateMessageStatus` / `updateMessageStatusUnlessDelivered`.
     */
    fun clearsFailureSeen(current: MessageStatus, incoming: MessageStatus): Boolean =
        incoming == MessageStatus.FAILED && current != MessageStatus.FAILED

    /** Next `failureSeen` after a status write. */
    fun failureSeenAfterWrite(current: MessageStatus, incoming: MessageStatus, previouslySeen: Boolean): Boolean =
        if (clearsFailureSeen(current, incoming)) false else previouslySeen
}
