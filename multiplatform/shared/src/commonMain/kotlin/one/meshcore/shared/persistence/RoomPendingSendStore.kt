package one.meshcore.shared.persistence

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import one.meshcore.shared.chat.PendingSendDto
import one.meshcore.shared.chat.PendingSendStore
import one.meshcore.shared.model.Uuid

/**
 * Room-backed [PendingSendStore]. The Swift `PersistenceStore` assigned sequences and bumped attempt
 * counts inside its serial `@ModelActor` isolation; Room offers no equivalent serial actor across the
 * read-then-write of `max(sequence)`/increment, so [sequenceMutex] serializes those compound operations,
 * reproducing the "concurrent enqueues receive distinct sequences" guarantee.
 */
class RoomPendingSendStore(private val dao: PendingSendDao) : PendingSendStore {
    private val sequenceMutex = Mutex()

    override suspend fun insertPendingSendAssigningSequence(dto: PendingSendDto): Int = sequenceMutex.withLock {
        val assigned = (dao.maxSequence(dto.radioID) ?: 0) + 1
        dao.insert(dto.copy(sequence = assigned).toEntity())
        assigned
    }

    override suspend fun upsertPendingSend(dto: PendingSendDto) = dao.upsert(dto.toEntity())

    override suspend fun fetchPendingSends(radioID: Uuid): List<PendingSendDto> =
        dao.listByRadio(radioID).mapNotNull { it.toDtoOrNull() }

    override suspend fun fetchPendingSendsForMessage(messageID: Uuid): List<PendingSendDto> =
        dao.listByMessage(messageID).mapNotNull { it.toDtoOrNull() }

    override suspend fun deletePendingSend(id: Uuid) = dao.deleteById(id)

    override suspend fun deletePendingSendsForMessage(messageID: Uuid) = dao.deleteByMessage(messageID)

    override suspend fun hasPendingSend(messageID: Uuid): Boolean = dao.exists(messageID)

    override suspend fun incrementPendingSendAttemptCount(messageID: Uuid): Int? = sequenceMutex.withLock {
        val row = dao.firstByMessage(messageID) ?: return@withLock null
        val next = (row.attemptCount ?: 0) + 1
        dao.setAttemptCount(row.id, next)
        next
    }

    override suspend fun purgeLegacyAttemptCountRows(): Int {
        val legacy = dao.listLegacyAttemptCount()
        legacy.forEach { dao.delete(it) }
        return legacy.size
    }

    override suspend fun purgeOrphanPendingSends(): Int {
        val orphans = dao.listOrphans()
        orphans.forEach { dao.delete(it) }
        return orphans.size
    }
}
