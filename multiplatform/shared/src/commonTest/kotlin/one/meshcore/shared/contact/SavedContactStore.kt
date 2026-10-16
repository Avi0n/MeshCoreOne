package one.meshcore.shared.contact

import one.meshcore.shared.backup.ContactDto

/** A fresh contact store with [contacts] already saved. An empty call is an empty store. */
internal suspend fun savedContactStore(vararg contacts: ContactDto): InMemoryContactStore {
    val store = InMemoryContactStore()
    contacts.forEach { store.saveContact(it) }
    return store
}
