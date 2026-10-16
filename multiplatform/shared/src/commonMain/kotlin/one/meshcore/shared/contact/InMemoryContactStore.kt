package one.meshcore.shared.contact

import one.meshcore.shared.model.Uuid

/**
 * In-memory [ContactStore] holding rows in maps behind [InMemoryContactParts.mutex]. The reference
 * implementation of the frame-upsert and identity-freeze rules ([ContactUpsert]): both the desktop/test
 * backing and the behavior the Room adapter must reproduce. Message rows are modeled only as a per-contact
 * count so the block/clear cascade is observable without pulling in the chat message store.
 *
 * [newId] mints the local row handle on insert; the data-partition `radioID` always arrives from the
 * caller, so no contact path here can mint one.
 */
class InMemoryContactStore internal constructor(private val parts: InMemoryContactParts) :
    ContactStore by parts.store {
    constructor(newId: () -> Uuid = Uuid::random) : this(InMemoryContactParts(newId))

    /** Seeds [count] message rows for a contact so the block/clear cascade is observable. Test-only. */
    suspend fun seedMessages(contactID: Uuid, count: Int) = parts.seeds.seedMessages(contactID, count)

    /** The remaining message-row count for a contact. Test-only. */
    suspend fun remainingMessageCount(contactID: Uuid): Int = parts.seeds.remainingMessageCount(contactID)

    /** The contact ids whose messages were deleted, in call order. Test-only. */
    suspend fun deletedMessagesForContactIDs(): List<Uuid> = parts.seeds.deletedMessagesForContactIDs()
}
