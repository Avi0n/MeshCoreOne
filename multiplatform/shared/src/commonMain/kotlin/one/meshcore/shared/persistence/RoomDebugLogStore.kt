package one.meshcore.shared.persistence

import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.DebugLogStore

/**
 * Room-backed [DebugLogStore]: append-only save, newest-first fetch within a time window, and
 * age-then-ceiling pruning.
 */
class RoomDebugLogStore(private val dao: DebugLogEntryDao) : DebugLogStore {
    override suspend fun saveDebugLogEntries(entries: List<DebugLogEntryDto>) {
        if (entries.isEmpty()) return
        dao.insertAll(entries.map { it.toEntity() })
    }

    override suspend fun fetchDebugLogEntries(since: Long, limit: Int): List<DebugLogEntryDto> =
        dao.fetchSince(since = since, limit = limit).map { it.toDto() }

    override suspend fun countDebugLogEntries(): Int = dao.count()

    override suspend fun pruneDebugLogEntries(olderThanMillis: Long, keepCount: Int) {
        dao.deleteOlderThan(olderThanMillis)
        val total = dao.count()
        if (total <= keepCount) return
        val toDelete = total - keepCount
        val oldest = dao.fetchOldest(toDelete)
        if (oldest.isNotEmpty()) dao.delete(oldest)
    }

    override suspend fun clearDebugLogEntries() {
        dao.clear()
    }

    companion object {
        fun fromDatabase(database: MeshDatabase): RoomDebugLogStore = RoomDebugLogStore(database.debugLogEntryDao())
    }
}
