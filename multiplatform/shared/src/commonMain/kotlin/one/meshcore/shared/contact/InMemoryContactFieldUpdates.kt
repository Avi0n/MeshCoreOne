package one.meshcore.shared.contact

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.model.Uuid

/** Existing-row transforms use the same mutex as all in-memory contact writes. */
internal class InMemoryContactFieldUpdates(private val parts: InMemoryContactParts) : ContactFieldUpdates {
    override suspend fun updateContactFields(id: Uuid, update: (ContactDto) -> ContactDto): ContactDto? =
        parts.mutex.withLock {
            currentCoroutineContext().ensureActive()
            val previous = parts.contacts[id] ?: return@withLock null
            val changed = ContactUpsert.apply(previous, update(previous))
            currentCoroutineContext().ensureActive()
            parts.contacts[id] = changed
            previous
        }
}
