package one.meshcore.shared.persistence

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.contact.ContactFieldUpdates
import one.meshcore.shared.contact.ContactUpsert
import one.meshcore.shared.model.Uuid

/** Field transforms share one fresh-row writer transaction and preserve caller cancellation. */
internal class RoomContactFieldUpdates(private val parts: RoomContactParts) : ContactFieldUpdates {
    override suspend fun updateContactFields(id: Uuid, update: (ContactDto) -> ContactDto): ContactDto? {
        val owner = currentCoroutineContext().job
        return parts.database.useWriterConnection { writer ->
            writer.immediateTransaction {
                owner.ensureActive()
                val previous = parts.contactDao.getById(id)?.toDto()
                owner.ensureActive()
                if (previous == null) return@immediateTransaction null
                val changed = ContactUpsert.apply(previous, update(previous))
                parts.contactDao.upsertPreservingPin(changed.toEntity())
                owner.ensureActive()
                previous
            }
        }
    }
}
