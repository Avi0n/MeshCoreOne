package one.meshcore.shared.persistence

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.contact.ContactBookkeeping
import one.meshcore.shared.contact.ContactFrame
import one.meshcore.shared.contact.ContactUpsert
import one.meshcore.shared.model.Uuid

/** DAOs and the shared upsert lock for [RoomContactStore]. */
internal class RoomContactParts(
    val database: MeshDatabase,
    val newId: () -> Uuid,
    val contactDao: ContactDao = database.contactDao(),
    val messageDao: MessageDao = database.messageDao(),
) {
    val pendingSendDao = database.pendingSendDao()
    val reactionDao = database.reactionDao()
    val messageRepeatDao = database.messageRepeatDao()
    val frameMutex = Mutex()
    val fieldUpdates = RoomContactFieldUpdates(this)
    val bookkeeping = RoomContactBookkeeping(this)
}

/** Message cascade, unread clears, heard stamps, and frame upserts for one radio's contacts. */
internal class RoomContactBookkeeping(private val parts: RoomContactParts) : ContactBookkeeping {
    override suspend fun deleteContactIfUnreferenced(id: Uuid) = parts.frameMutex.withLock {
        val owner = currentCoroutineContext().job
        parts.database.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                owner.ensureActive()
                val referenced = parts.messageDao.directMessageIdsForContact(id).isNotEmpty()
                owner.ensureActive()
                if (referenced) return@immediateTransaction
                cascadeContactData(id)
                parts.contactDao.deleteById(id)
                owner.ensureActive()
            }
        }
    }

    override suspend fun deleteMessagesForContact(contactID: Uuid) {
        val owner = currentCoroutineContext().job
        parts.database.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                owner.ensureActive()
                cascadeContactData(contactID)
                owner.ensureActive()
            }
        }
    }

    override suspend fun clearUnreadCount(contactID: Uuid) {
        val owner = currentCoroutineContext().job
        parts.database.useWriterConnection { writer ->
            writer.immediateTransaction {
                owner.ensureActive()
                val current = parts.contactDao.getById(contactID)
                owner.ensureActive()
                if (current == null) return@immediateTransaction
                parts.contactDao.upsertPreservingPin(current.copy(unreadCount = 0))
                owner.ensureActive()
            }
        }
    }

    override suspend fun clearUnreadMentionCount(contactID: Uuid) {
        val owner = currentCoroutineContext().job
        parts.database.useWriterConnection { writer ->
            writer.immediateTransaction {
                owner.ensureActive()
                val current = parts.contactDao.getById(contactID)
                owner.ensureActive()
                if (current == null) return@immediateTransaction
                parts.contactDao.upsertPreservingPin(current.copy(unreadMentionCount = 0))
                owner.ensureActive()
            }
        }
    }

    override suspend fun touchContactHeard(radioID: Uuid, publicKey: ByteArray, atEpochSeconds: Long): Boolean =
        parts.frameMutex.withLock {
            val owner = currentCoroutineContext().job
            parts.database.useWriterConnection { writer ->
                writer.immediateTransaction {
                    owner.ensureActive()
                    val existing = parts.contactDao.listByRadio(radioID)
                        .firstOrNull { it.publicKey.contentEquals(publicKey) }
                    owner.ensureActive()
                    if (existing == null) return@immediateTransaction false
                    val stamp = maxOf(existing.lastHeardTimestamp ?: 0L, atEpochSeconds)
                    if (existing.lastHeardTimestamp != stamp) {
                        parts.contactDao.upsertPreservingPin(existing.copy(lastHeardTimestamp = stamp))
                    }
                    owner.ensureActive()
                    true
                }
            }
        }

    /**
     * Removes everything scoped to a contact — direct messages, their reactions and message repeats, and
     * durable pending sends — keyed by the contact ID value, so orphaned rows are cleaned up even when the
     * Contact row is already gone. Mirrors the Swift `_deleteMessagesForContactWithoutSaving` cascade;
     * callers keep the whole cascade in their Room transaction so every delete commits or rolls back
     * together. [deleteMessagesForContact] leaves the contact row in place.
     */
    suspend fun cascadeContactData(contactID: Uuid) {
        val messageIds = parts.messageDao.directMessageIdsForContact(contactID)
        parts.reactionDao.deleteByContact(contactID)
        if (messageIds.isNotEmpty()) {
            // Pending sends and message repeats key by messageID (iOS keys the pending-send cascade this way);
            // deleteByContact below is the belt-and-braces path for a row whose message is already gone.
            parts.messageRepeatDao.deleteByMessageIds(messageIds)
            parts.pendingSendDao.deleteByMessageIds(messageIds)
        }
        parts.messageDao.deleteDirectByContact(contactID)
        parts.pendingSendDao.deleteByContact(contactID)
    }

    // Callers select the current row while holding their writer transaction and frame mutex.
    // The originating Job remains the cancellation authority across Room's context change.
    suspend fun upsertRow(radioID: Uuid, existing: ContactDto?, frame: ContactFrame, owner: Job): ContactDto {
        owner.ensureActive()
        val row = if (existing != null) {
            ContactUpsert.merge(existing, frame)
        } else {
            ContactUpsert.insert(radioID = radioID, id = parts.newId(), frame = frame)
        }
        parts.contactDao.upsertPreservingPin(row.toEntity())
        owner.ensureActive()
        return row
    }
}
