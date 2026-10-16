package one.meshcore.shared.contact

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.settings.PersistentLogger

/** Ordinary contact-table mutations, separate from advert and overwrite-oldest queue ownership. */
internal class ContactDirectory(private val dependencies: Dependencies) {
    data class Dependencies(
        val session: ContactSession,
        val store: ContactStore,
        val syncCoordinator: ContactSyncCoordinator,
        val cleanupCoordinator: ContactCleanupHandling,
        val identityGuard: ContactIdentityGuard,
        val nodeStorage: ContactNodeStorage,
        val logger: PersistentLogger?,
    )

    suspend fun addOrUpdateContact(radioID: Uuid, frame: ContactFrame, radioIDAtInsert: (() -> Uuid?)? = null) {
        runContactSession(mapDeviceCode = ::tableFullError) {
            dependencies.session.addContact(frame.toMeshContact())
        }
        currentCoroutineContext().ensureActive()
        val partition = if (radioIDAtInsert == null) radioID else radioIDAtInsert() ?: return
        dependencies.store.saveContact(radioID = partition, frame = frame)
        dependencies.syncCoordinator.notifyContactsChanged()
    }

    suspend fun removeContact(radioID: Uuid, publicKey: ByteArray) {
        // CMD_REMOVE would turn off ZephCore's virtual contact rather than deleting a table row.
        if (dependencies.identityGuard.isProtectedVContact(radioID, publicKey)) {
            dependencies.logger?.info(REMOVE_CONTACT_IGNORED_V_CONTACT)
            return
        }

        runContactSession(mapDeviceCode = ::notFoundError) { dependencies.session.removeContact(publicKey) }
        dependencies.store.fetchContact(radioID, publicKey)?.let { contact ->
            dependencies.store.deleteContact(contact.id)
            dependencies.cleanupCoordinator.handleCleanup(contact.id, ContactCleanupReason.DELETED, publicKey)
        }
        dependencies.nodeStorage.publishNodeDeleted()
        dependencies.syncCoordinator.notifyContactsChanged()
    }

    suspend fun removeLocalContact(contactID: Uuid, publicKey: ByteArray) {
        val radioID = dependencies.store.fetchContact(contactID)?.radioID
        if (radioID != null && dependencies.identityGuard.isProtectedVContact(radioID, publicKey)) {
            dependencies.logger?.info(REMOVE_LOCAL_CONTACT_IGNORED_V_CONTACT)
            return
        }

        dependencies.store.deleteContact(contactID)
        dependencies.cleanupCoordinator.handleCleanup(contactID, ContactCleanupReason.DELETED, publicKey)
        dependencies.nodeStorage.publishNodeDeleted()
        dependencies.syncCoordinator.notifyContactsChanged()
    }

    private fun tableFullError(code: Int): ContactServiceError? =
        if (code == ContactProtocolErrorCode.TABLE_FULL) ContactServiceError.ContactTableFull() else null

    private fun notFoundError(code: Int): ContactServiceError? =
        if (code == ContactProtocolErrorCode.NOT_FOUND) ContactServiceError.ContactNotFound() else null
}
