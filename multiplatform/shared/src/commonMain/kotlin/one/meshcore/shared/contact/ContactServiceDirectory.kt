package one.meshcore.shared.contact

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.model.Uuid

/**
 * Adds or updates a contact on the device and in local storage. [radioID] is the caller's
 * `device.radioID`. A device `TABLE_FULL` becomes [ContactServiceError.ContactTableFull]. Mirrors
 * `addOrUpdateContact(radioID:contact:)`.
 */
suspend fun ContactService.addOrUpdateContact(
    radioID: Uuid,
    frame: ContactFrame,
    radioIDAtInsert: (() -> Uuid?)? = null,
) = contactDirectory.addOrUpdateContact(radioID, frame, radioIDAtInsert)

/**
 * Removes a contact from the device, then deletes its local row (and messages) and runs the deleted
 * cleanup. A device `NOT_FOUND` becomes [ContactServiceError.ContactNotFound]. Mirrors
 * `removeContact(radioID:publicKey:)`.
 */
suspend fun ContactService.removeContact(radioID: Uuid, publicKey: ByteArray) =
    contactDirectory.removeContact(radioID, publicKey)

/**
 * Deletes a contact's local data and runs the deleted cleanup without contacting the device. Used
 * when the radio already forgot the contact but a local row lingers. Mirrors `removeLocalContact`.
 */
suspend fun ContactService.removeLocalContact(contactID: Uuid, publicKey: ByteArray) =
    contactDirectory.removeLocalContact(contactID, publicKey)

/** Whether [publicKey] is the ZephCore V-contact for this radio (remove/prune protected). */
internal suspend fun ContactService.isProtectedVContact(radioID: Uuid, publicKey: ByteArray): Boolean =
    contactIdentityGuard.isProtectedVContact(radioID, publicKey)

/**
 * Drops the ZephCore V-contact from a bulk-remove candidate list. iOS `ConnectionManager.removeContacts`
 * never bulk-removes it: `CMD_REMOVE` turns firmware `v.contact` off.
 */
internal suspend fun ContactService.excludingProtectedVContact(
    radioID: Uuid,
    contacts: List<ContactDto>,
): List<ContactDto> = contactIdentityGuard.excludingProtectedVContact(radioID, contacts)

/**
 * Device push 0x8F (overwrite-oldest): the radio already dropped the contact. If a local row still
 * exists, delete its messages and row and yield [ContactServiceEvent.NodeDeleted]. Returns `false`
 * when no local row is found (same early-return as iOS `handleContactDeletedEvent`).
 */
suspend fun ContactService.handleDeviceContactDeleted(radioID: Uuid, publicKey: ByteArray): Boolean =
    overwriteDeletion.handleDeviceContactDeleted(radioID, publicKey)
