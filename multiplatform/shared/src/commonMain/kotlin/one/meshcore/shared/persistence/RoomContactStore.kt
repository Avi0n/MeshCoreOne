package one.meshcore.shared.persistence

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.withLock
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.contact.ContactBookkeeping
import one.meshcore.shared.contact.ContactFieldUpdates
import one.meshcore.shared.contact.ContactFrame
import one.meshcore.shared.contact.ContactStore
import one.meshcore.shared.contact.ContactUpsert
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.model.toLowercaseHex
import one.meshcore.shared.model.toUppercaseHex

/**
 * Room-backed [ContactStore], the production adapter [one.meshcore.shared.contact.ContactService] drives.
 * Reproduces [InMemoryContactStore]'s behavior against the [ContactDao]: frame upserts key by
 * `(radioID, publicKey)` with the id/favorite freeze of [ContactUpsert], and the block/delete cascade
 * clears the contact's direct messages, reactions, message repeats, and durable send-queue rows.
 *
 * [RoomContactParts.frameMutex] serializes the read-modify-write of an upsert (match existing, merge or
 * insert) so concurrent syncs cannot assign two rows the same `(radioID, publicKey)` — the Room analogue
 * of the Swift `@ModelActor` serialization the in-memory reference secures with its own mutex. Unread
 * clears, heard stamps, and the message cascade delegate to [RoomContactBookkeeping].
 */
class RoomContactStore internal constructor(private val parts: RoomContactParts) :
    ContactStore,
    ContactBookkeeping by parts.bookkeeping,
    ContactFieldUpdates by parts.fieldUpdates {
    constructor(database: MeshDatabase, newId: () -> Uuid = Uuid::random) : this(
        RoomContactParts(database = database, newId = newId),
    )

    private val contactDao get() = parts.contactDao
    private val frameMutex get() = parts.frameMutex

    override suspend fun fetchContacts(radioID: Uuid): List<ContactDto> =
        contactDao.listByRadio(radioID).map { it.toDto() }.sortedBy { it.name }

    override suspend fun fetchBlockedContacts(radioID: Uuid): List<ContactDto> =
        contactDao.listBlocked(radioID).map { it.toDto() }

    override suspend fun fetchConversations(radioID: Uuid): List<ContactDto> =
        contactDao.listConversations(radioID).map { it.toDto() }

    override suspend fun fetchContact(id: Uuid): ContactDto? = contactDao.getById(id)?.toDto()

    override suspend fun fetchContact(radioID: Uuid, publicKey: ByteArray): ContactDto? =
        contactDao.listByRadio(radioID).firstOrNull { it.publicKey.contentEquals(publicKey) }?.toDto()

    override suspend fun saveContact(radioID: Uuid, frame: ContactFrame): Uuid = frameMutex.withLock {
        val owner = currentCoroutineContext().job
        contactDao.saveContactFrame(radioID, frame, owner, parts.newId)
    }

    override suspend fun batchSaveContacts(radioID: Uuid, frames: List<ContactFrame>): Int = frameMutex.withLock {
        if (frames.isEmpty()) return@withLock 0
        val owner = currentCoroutineContext().job
        parts.database.useWriterConnection { writer ->
            writer.immediateTransaction {
                owner.ensureActive()
                val existingByKey = contactDao.listByRadio(radioID)
                    .associate { it.publicKey.toLowercaseHex() to it.toDto() }
                    .toMutableMap()
                owner.ensureActive()
                frames.forEach { frame ->
                    val key = frame.publicKey.toLowercaseHex()
                    val row = parts.bookkeeping.upsertRow(radioID, existingByKey[key], frame, owner)
                    existingByKey[key] = row
                }
                owner.ensureActive()
                frames.size
            }
        }
    }

    override suspend fun saveContact(dto: ContactDto) {
        val existing = contactDao.getById(dto.id)?.toDto()
        val row = if (existing != null) ContactUpsert.apply(existing, dto) else dto
        contactDao.upsertPreservingPin(row.toEntity())
    }

    override suspend fun deleteContact(id: Uuid) = frameMutex.withLock {
        val owner = currentCoroutineContext().job
        parts.database.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                owner.ensureActive()
                parts.bookkeeping.cascadeContactData(id)
                contactDao.deleteById(id)
                owner.ensureActive()
            }
        }
    }

    override suspend fun deleteContacts(
        radioID: Uuid,
        publicKeys: Collection<ByteArray>,
        skippingPublicKeys: suspend () -> Set<String>,
    ): List<Uuid> = frameMutex.withLock {
        if (publicKeys.isEmpty()) return@withLock emptyList()
        val owner = currentCoroutineContext().job
        parts.database.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                owner.ensureActive()
                val candidates = mutableListOf<Pair<Uuid, ByteArray>>()
                for (key in publicKeys) {
                    val hex = key.toUppercaseHex()
                    if (hex !in skippingPublicKeys()) {
                        val row = contactDao.listByRadio(radioID).firstOrNull { it.publicKey.contentEquals(key) }
                        owner.ensureActive()
                        if (row != null) candidates += row.id to key
                    }
                }
                val deleted = mutableListOf<Uuid>()
                for ((id, key) in candidates) {
                    val skipped = key.toUppercaseHex() in skippingPublicKeys()
                    owner.ensureActive()
                    if (skipped) continue
                    parts.bookkeeping.cascadeContactData(id)
                    contactDao.deleteById(id)
                    deleted += id
                }
                owner.ensureActive()
                deleted
            }
        }
    }
}
