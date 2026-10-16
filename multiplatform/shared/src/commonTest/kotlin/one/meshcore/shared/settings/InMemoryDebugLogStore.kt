package one.meshcore.shared.settings

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory [DebugLogStore] behind a [Mutex]: the reference read/write semantics the Room adapter must
 * reproduce, and the test backing. Entries are kept in insertion order; [fetchDebugLogEntries] returns the
 * newest within the window, matching the Swift store's timestamp-descending fetch.
 */
class InMemoryDebugLogStore : DebugLogStore {
    private val mutex = Mutex()
    private val entries = mutableListOf<DebugLogEntryDto>()

    override suspend fun saveDebugLogEntries(entries: List<DebugLogEntryDto>) = mutex.withLock {
        this.entries += entries
    }

    override suspend fun fetchDebugLogEntries(since: Long, limit: Int): List<DebugLogEntryDto> = mutex.withLock {
        entries.asReversed().asSequence().filter { it.timestamp >= since }.take(limit).toList()
    }

    override suspend fun countDebugLogEntries(): Int = mutex.withLock { entries.size }

    override suspend fun pruneDebugLogEntries(olderThanMillis: Long, keepCount: Int) = mutex.withLock {
        entries.removeAll { it.timestamp < olderThanMillis }
        if (entries.size > keepCount) {
            // Ceiling backstop: drop the oldest by timestamp, matching the Room store's ascending delete.
            val drop = entries.size - keepCount
            entries.sortedBy { it.timestamp }.take(drop).forEach { entries.remove(it) }
        }
        Unit
    }

    override suspend fun clearDebugLogEntries() = mutex.withLock { entries.clear() }

    /** Snapshot of every persisted entry, for test assertions. */
    suspend fun snapshot(): List<DebugLogEntryDto> = mutex.withLock { entries.toList() }
}
