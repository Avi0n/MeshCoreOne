package one.meshcore.shared.contact

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.model.Uuid

/** Serialized field edits for existing contacts, separate from full DTO replacement. */
interface ContactFieldUpdates {
    /**
     * Updates an existing row from one synchronous transform of its current fields. Returns the previous
     * DTO after persistence, or null without insertion. The transform must not suspend or perform effects;
     * identity and pin remain frozen. Full DTO replacement remains on [ContactStore.saveContact].
     */
    suspend fun updateContactFields(id: Uuid, update: (ContactDto) -> ContactDto): ContactDto?
}
