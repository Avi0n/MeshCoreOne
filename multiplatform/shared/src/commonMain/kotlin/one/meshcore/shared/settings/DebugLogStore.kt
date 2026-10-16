package one.meshcore.shared.settings

/**
 * The persistence seam [DebugLogBuffer] flushes to and the log export reads from, a narrow port of the Swift
 * `DebugLogPersisting`. The interface keeps buffering independently testable while the Room adapter and its
 * timestamp-indexed `DebugLogEntry` entity provide production storage.
 */
interface DebugLogStore {
    /** Persists a batch of entries; throws on failure so the buffer can apply backpressure. */
    suspend fun saveDebugLogEntries(entries: List<DebugLogEntryDto>)

    /** Entries at or after [since] (epoch millis), newest-relevant first, capped at [limit]. */
    suspend fun fetchDebugLogEntries(since: Long, limit: Int): List<DebugLogEntryDto>

    /** Total persisted entry count. */
    suspend fun countDebugLogEntries(): Int

    /**
     * Prunes entries older than [olderThanMillis] (epoch millis), then enforces a hard row ceiling by
     * deleting the oldest of any that remain past [keepCount]. Mirrors the Swift age-then-ceiling prune.
     */
    suspend fun pruneDebugLogEntries(olderThanMillis: Long, keepCount: Int)

    /** Deletes every persisted entry. */
    suspend fun clearDebugLogEntries()
}

/**
 * Retention policy for persisted debug log entries, shared by the connection-time prune and the diagnostics
 * export so an export can return everything retention keeps. Mirrors the Swift `DebugLogRetention`.
 */
object DebugLogRetention {
    /** Entries older than this window (7 days, in epoch millis) are pruned. */
    const val WINDOW_MS: Long = 7L * 24L * 60L * 60L * 1000L

    /** Hard row ceiling enforced after the time-based prune, a disk backstop for a heavy connected burst. */
    const val MAX_ENTRIES: Int = 50_000

    /** Minimum time between prune passes triggered from DebugLogBuffer, matching iOS `pruneInterval`. */
    const val PRUNE_INTERVAL_MS: Long = 60L * 60L * 1000L
}
