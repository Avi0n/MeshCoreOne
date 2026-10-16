package one.meshcore.shared.chat

import one.meshcore.shared.model.Uuid

/**
 * The durable send-queue persistence seam. Mirrors the subset of the Swift `PersistenceStore` pending-send
 * surface the queue depends on: ordered hydrate fetch, atomic sequence assignment, delete-by-message,
 * the drain gate, and the three-state [PendingSendDto.attemptCount] bump. A narrow port so
 * [ChatSendQueueService] stays unit-testable against an in-memory fake; the Room-backed adapter is
 * [one.meshcore.shared.persistence.RoomPendingSendStore].
 */
interface PendingSendStore {
    /**
     * Inserts a new row, atomically assigning the next per-radio monotonic sequence (the row's own
     * `sequence` field is ignored). Returns the assigned sequence. This is the production enqueue path;
     * concurrent inserts on one radio must receive distinct sequences.
     */
    suspend fun insertPendingSendAssigningSequence(dto: PendingSendDto): Int

    /** Inserts or replaces a row using the DTO's `sequence` verbatim. For tests that pin sequence values. */
    suspend fun upsertPendingSend(dto: PendingSendDto)

    /** Every row for a radio, ordered by sequence ascending. Rows with an unknown discriminator are skipped. */
    suspend fun fetchPendingSends(radioID: Uuid): List<PendingSendDto>

    /** Every row matching a message id, ordered by sequence ascending; unknown-discriminator rows skipped. */
    suspend fun fetchPendingSendsForMessage(messageID: Uuid): List<PendingSendDto>

    /** Deletes a row by its own id. No-op if absent. */
    suspend fun deletePendingSend(id: Uuid)

    /** Deletes every row matching a message id. No-op if none match. The drain's success/terminal hot path. */
    suspend fun deletePendingSendsForMessage(messageID: Uuid)

    /** The drain gate: whether any row exists for a message id. */
    suspend fun hasPendingSend(messageID: Uuid): Boolean

    /**
     * Increments the attempt count for a message's row and returns the new count, or null if no row
     * matched (terminal — abandon the envelope). Throws on a store fault; the caller parks and retries.
     */
    suspend fun incrementPendingSendAttemptCount(messageID: Uuid): Int?

    /**
     * Deletes every row whose `attemptCount` is null (pre-migration rows with ambiguous drain history).
     * Runs on connect before hydrate. Returns the number deleted.
     */
    suspend fun purgeLegacyAttemptCountRows(): Int

    /**
     * Deletes every row whose `radioID` has no matching Device. Runs on connect before hydrate so a
     * forgotten radio cannot replay sends. Returns the number deleted.
     */
    suspend fun purgeOrphanPendingSends(): Int
}
