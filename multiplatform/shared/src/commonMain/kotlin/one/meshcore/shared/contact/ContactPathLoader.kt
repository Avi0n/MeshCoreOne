package one.meshcore.shared.contact

import one.meshcore.shared.meshcore.MeshContact
import one.meshcore.shared.model.Uuid

/** Loads a radio contact after a path update and persists the refreshed path locally. */
internal class ContactPathLoader(private val dependencies: Dependencies) {
    data class Dependencies(
        val session: ContactSession,
        val store: ContactStore,
        val syncCoordinator: ContactSyncCoordinator,
        val isCancelled: suspend (ByteArray) -> Boolean,
        val rollbackInsert: suspend (id: Uuid, isNew: Boolean) -> Unit,
    )

    suspend fun persist(radioID: Uuid, publicKey: ByteArray): Boolean {
        val meshContact = liveContact(publicKey) ?: return false
        val frame = meshContact.toContactFrame()
        val isNew = dependencies.store.fetchContact(radioID, frame.publicKey) == null
        val contactID = dependencies.store.saveContact(radioID = radioID, frame = frame)
        val cancelledAfterSave = dependencies.isCancelled(publicKey)
        if (cancelledAfterSave) {
            dependencies.rollbackInsert(contactID, isNew)
        } else {
            // iOS `contactUpdated` bumps `contactsVersion` so an open route section reloads.
            dependencies.syncCoordinator.notifyContactsChanged()
        }
        return !cancelledAfterSave
    }

    suspend fun liveContact(publicKey: ByteArray): MeshContact? = when {
        dependencies.isCancelled(publicKey) -> null
        else -> runContactSession(mapDeviceCode = null) {
            dependencies.session.getContact(publicKey)
        }?.takeUnless { dependencies.isCancelled(publicKey) }
    }
}
