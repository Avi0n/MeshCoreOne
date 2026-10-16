package one.meshcore.shared.contact

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.model.Uuid

/** The `(radioID, publicKey)` contact from local storage, or null. */
suspend fun ContactService.getContact(radioID: Uuid, publicKey: ByteArray): ContactDto? =
    store.fetchContact(radioID, publicKey)

/** The contact with local id [id], or null. */
suspend fun ContactService.getContactByID(id: Uuid): ContactDto? = store.fetchContact(id)

/** All contacts for a radio. */
suspend fun ContactService.getContacts(radioID: Uuid): List<ContactDto> = store.fetchContacts(radioID)

/** A radio's blocked contacts, sorted by name (the Blocked Contacts screen query). */
suspend fun ContactService.getBlockedContacts(radioID: Uuid): List<ContactDto> = store.fetchBlockedContacts(radioID)
