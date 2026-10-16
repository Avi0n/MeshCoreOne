package one.meshcore.shared.contact

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.model.toUppercaseHex

/**
 * The persistence seam [ContactService] depends on: the per-radio contact reads/writes and the
 * message-cascade the block/delete paths need. [one.meshcore.shared.persistence.RoomContactStore] provides
 * the production adapter. Mirrors the subset of the Swift `PersistenceStoreProtocol` the service calls.
 *
 * Every write keys a row by `(radioID, publicKey)` and freezes those two axes after creation (see
 * [ContactUpsert]); `radioID` always arrives from the caller's live `device.radioID`, never minted here.
 * Message cascade, unread clears, and heard stamps live on [ContactBookkeeping].
 */
interface ContactStore :
    ContactBookkeeping,
    ContactFieldUpdates {
    /** All contacts for a radio. */
    suspend fun fetchContacts(radioID: Uuid): List<ContactDto>

    /** A radio's blocked contacts, sorted by name (the Blocked Contacts screen query). */
    suspend fun fetchBlockedContacts(radioID: Uuid): List<ContactDto>

    /** Contacts with a non-null last-message date, newest first (the conversations list). */
    suspend fun fetchConversations(radioID: Uuid): List<ContactDto>

    /** The contact with local id [id], or null. */
    suspend fun fetchContact(id: Uuid): ContactDto?

    /** The `(radioID, publicKey)` contact, or null. */
    suspend fun fetchContact(radioID: Uuid, publicKey: ByteArray): ContactDto?

    /**
     * Upserts one contact from a device [frame], matched by `(radioID, publicKey)`. Returns the row's
     * local id. Insert seeds a fresh row; update preserves the local favorite bit ([ContactUpsert]).
     */
    suspend fun saveContact(radioID: Uuid, frame: ContactFrame): Uuid

    /**
     * Upserts contacts from [frames] in a single transaction, matched by `(radioID, publicKey)`. Returns
     * the number of frames persisted. The batched form of [saveContact], the dominant cost of a full sync.
     */
    suspend fun batchSaveContacts(radioID: Uuid, frames: List<ContactFrame>): Int

    /** Upserts a full [dto] by local id, keeping `radioID`/`publicKey` frozen ([ContactUpsert.apply]). */
    suspend fun saveContact(dto: ContactDto)

    /** Deletes the contact row with local id [id]; a no-op if absent. */
    suspend fun deleteContact(id: Uuid)

    /**
     * Deletes matching contacts and their scoped messages. Missing keys are omitted from the
     * returned ids. [skippingPublicKeys] is read per key so a concurrent 0x80 revive can retract
     * a key after the caller snapshotted the set. Mirrors iOS `ContactPersisting.deleteContacts`.
     */
    suspend fun deleteContacts(
        radioID: Uuid,
        publicKeys: Collection<ByteArray>,
        skippingPublicKeys: suspend () -> Set<String> = { emptySet() },
    ): List<Uuid> {
        if (publicKeys.isEmpty()) return emptyList()
        val deleted = mutableListOf<Uuid>()
        for (key in publicKeys) {
            val hex = key.toUppercaseHex()
            if (hex !in skippingPublicKeys()) {
                val contact = fetchContact(radioID, key)
                if (contact != null && hex !in skippingPublicKeys()) {
                    deleteContact(contact.id)
                    deleted += contact.id
                }
            }
        }
        return deleted
    }
}
