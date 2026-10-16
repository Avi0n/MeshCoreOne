package one.meshcore.shared.contact

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.model.Uuid

/** Shared contact maps and the one mutex behind [InMemoryContactStore]. */
internal class InMemoryContactParts(val newId: () -> Uuid) {
    val mutex = Mutex()
    val contacts = mutableMapOf<Uuid, ContactDto>()
    val messageCounts = mutableMapOf<Uuid, Int>()
    val deletions = mutableListOf<Uuid>()
    val bookkeeping = InMemoryContactBookkeeping(this)
    val fieldUpdates = InMemoryContactFieldUpdates(this)
    val records = InMemoryContactRecords(this)
    val seeds = InMemoryContactSeeds(this)
    val store: ContactStore = records
}

/** Message counts, unread clears, and heard stamps for the in-memory contact rows. */
internal class InMemoryContactBookkeeping(private val parts: InMemoryContactParts) : ContactBookkeeping {
    override suspend fun deleteContactIfUnreferenced(id: Uuid) = parts.mutex.withLock {
        if ((parts.messageCounts[id] ?: 0) > 0) return@withLock
        removeContactLocked(id)
    }

    override suspend fun deleteMessagesForContact(contactID: Uuid) = parts.mutex.withLock {
        cascade(contactID)
    }

    override suspend fun clearUnreadCount(contactID: Uuid) = parts.mutex.withLock {
        parts.contacts[contactID]?.let { parts.contacts[contactID] = it.copy(unreadCount = 0) }
        Unit
    }

    override suspend fun clearUnreadMentionCount(contactID: Uuid) = parts.mutex.withLock {
        parts.contacts[contactID]?.let { parts.contacts[contactID] = it.copy(unreadMentionCount = 0) }
        Unit
    }

    override suspend fun touchContactHeard(radioID: Uuid, publicKey: ByteArray, atEpochSeconds: Long): Boolean =
        parts.mutex.withLock {
            val existing = parts.contacts.values.firstOrNull {
                it.radioID == radioID && it.publicKey.contentEquals(publicKey)
            } ?: return@withLock false
            val stamp = maxOf(existing.lastHeardTimestamp ?: 0L, atEpochSeconds)
            if (existing.lastHeardTimestamp != stamp) {
                parts.contacts[existing.id] = existing.copy(lastHeardTimestamp = stamp)
            }
            true
        }

    /** Drops the contact row after cascading its messages. Caller holds [InMemoryContactParts.mutex]. */
    fun removeContactLocked(id: Uuid) {
        cascade(id)
        parts.contacts.remove(id)
        parts.messageCounts.remove(id)
    }

    private fun cascade(contactID: Uuid) {
        parts.deletions += contactID
        parts.messageCounts[contactID] = 0
    }
}

/** Frame upserts and contact reads. Bookkeeping stays on [InMemoryContactBookkeeping]. */
internal class InMemoryContactRecords(private val parts: InMemoryContactParts) :
    ContactStore,
    ContactBookkeeping by parts.bookkeeping,
    ContactFieldUpdates by parts.fieldUpdates {
    override suspend fun fetchContacts(radioID: Uuid): List<ContactDto> = parts.mutex.withLock {
        parts.contacts.values.filter { it.radioID == radioID }.sortedBy { it.name }
    }

    override suspend fun fetchBlockedContacts(radioID: Uuid): List<ContactDto> = parts.mutex.withLock {
        parts.contacts.values.filter { it.radioID == radioID && it.isBlocked }.sortedBy { it.name }
    }

    override suspend fun fetchConversations(radioID: Uuid): List<ContactDto> = parts.mutex.withLock {
        parts.contacts.values
            .filter { it.radioID == radioID && it.lastMessageDate != null }
            .sortedByDescending { it.lastMessageDate }
    }

    override suspend fun fetchContact(id: Uuid): ContactDto? = parts.mutex.withLock { parts.contacts[id] }

    override suspend fun fetchContact(radioID: Uuid, publicKey: ByteArray): ContactDto? = parts.mutex.withLock {
        parts.contacts.values.firstOrNull { it.radioID == radioID && it.publicKey.contentEquals(publicKey) }
    }

    override suspend fun saveContact(radioID: Uuid, frame: ContactFrame): Uuid = parts.mutex.withLock {
        upsertFrame(radioID, frame).id
    }

    override suspend fun batchSaveContacts(radioID: Uuid, frames: List<ContactFrame>): Int = parts.mutex.withLock {
        if (frames.isEmpty()) return@withLock 0
        frames.forEach { upsertFrame(radioID, it) }
        frames.size
    }

    override suspend fun saveContact(dto: ContactDto) = parts.mutex.withLock {
        val existing = parts.contacts[dto.id]
        parts.contacts[dto.id] = if (existing != null) ContactUpsert.apply(existing, dto) else dto
    }

    override suspend fun deleteContact(id: Uuid) = parts.mutex.withLock {
        // Cascade first, keyed by the id value, so scoped rows are cleared even when the contact is gone —
        // then drop the row. Mirrors the Swift `deleteContact` cascade.
        parts.bookkeeping.removeContactLocked(id)
    }

    /** Insert-or-merge a frame under [InMemoryContactParts.mutex]; assigns a fresh id on insert. */
    private fun upsertFrame(radioID: Uuid, frame: ContactFrame): ContactDto {
        val existing = parts.contacts.values.firstOrNull {
            it.radioID == radioID && it.publicKey.contentEquals(frame.publicKey)
        }
        val row = if (existing != null) {
            ContactUpsert.merge(existing, frame)
        } else {
            ContactUpsert.insert(radioID = radioID, id = parts.newId(), frame = frame)
        }
        parts.contacts[row.id] = row
        return row
    }
}

/** Test seeds for message counts and the cascade log. */
internal class InMemoryContactSeeds(private val parts: InMemoryContactParts) {
    suspend fun seedMessages(contactID: Uuid, count: Int) = parts.mutex.withLock {
        parts.messageCounts[contactID] = count
    }

    suspend fun remainingMessageCount(contactID: Uuid): Int = parts.mutex.withLock {
        parts.messageCounts[contactID] ?: 0
    }

    suspend fun deletedMessagesForContactIDs(): List<Uuid> = parts.mutex.withLock { parts.deletions.toList() }
}
