package one.meshcore.shared.contact

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.runReportingException

/** Favorite, block, and delete actions for one nodes-list row. */
interface ContactListRowActions {
    /** Toggles a contact's device-synced favorite flag, then reloads. */
    suspend fun toggleFavorite(contact: ContactDto)

    /** Toggles a chat contact's blocked preference, then reloads. */
    suspend fun toggleBlocked(contact: ContactDto)

    /**
     * Removes a contact — a real radio command. The row stays in place under a spinner until the radio
     * acks, then is hidden once; a device that already forgot the contact clears the orphaned local row;
     * a timeout or other failure leaves the row untouched with an error. Ports the iOS delete state machine.
     */
    suspend fun deleteContact(contact: ContactDto)
}

internal class ContactListRowActionRunner(private val model: ContactListModel, private val loader: ContactListLoader) :
    ContactListRowActions {
    override suspend fun toggleFavorite(contact: ContactDto) {
        if (!model.dependencies.isConnected()) return
        val service = model.dependencies.contactService() ?: return
        model.state.update { it.copy(togglingFavoriteId = contact.id) }
        try {
            runReportingException<Unit>(
                onFailure = { error ->
                    // Room save is not a ContactServiceError. iOS `toggleFavorite` sets errorMessage.
                    model.state.update { it.copy(error = ContactListError.ServiceMessage(model.serviceMessage(error))) }
                },
            ) {
                service.setContactFavorite(contact.id, !contact.isFavorite)
                loader.reload(contact.radioID)
            }
        } finally {
            model.state.update { it.copy(togglingFavoriteId = null) }
        }
    }

    override suspend fun toggleBlocked(contact: ContactDto) {
        // iOS `toggleBlocked`: missing live `contactService` is a silent return. The offline
        // service still writes Room and runs blocked-sender channel-message cleanup.
        if (!model.dependencies.isConnected()) return
        val service = model.dependencies.contactService() ?: return
        runReportingException<Unit>(
            onFailure = { error ->
                model.state.update { it.copy(error = ContactListError.ServiceMessage(model.serviceMessage(error))) }
            },
        ) {
            service.updateContactPreferences(contactID = contact.id, isBlocked = !contact.isBlocked)
            loader.reload(contact.radioID)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    override suspend fun deleteContact(contact: ContactDto) {
        // iOS `deleteContact` requires live `contactService`. OfflineDataAccess still returns a
        // ContactService whose session throws; treating that as connected would CMD_REMOVE against
        // air and surface a session error instead of connect-to-delete.
        val service = liveServiceOrConnectToDelete() ?: return
        if (model.state.value.isDeletePending(contact.id)) return
        model.state.update { it.copy(deletingIds = it.deletingIds + contact.id) }

        try {
            try {
                val removed = withTimeoutOrNull(DELETE_TIMEOUT_MS) {
                    service.removeContact(radioID = contact.radioID, publicKey = contact.publicKey)
                    true
                } ?: false
                if (!removed) {
                    model.state.update { it.copy(error = ContactListError.RemoveTimedOut) }
                    return
                }
                hideDeleted(service, contact)
            } catch (_: ContactServiceError.ContactNotFound) {
                removeOrphanedLocalContact(service, contact)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            model.state.update {
                it.copy(error = ContactListError.ServiceMessage(model.serviceMessage(error)))
            }
        } finally {
            model.state.update { it.copy(deletingIds = it.deletingIds - contact.id) }
        }
    }

    private suspend fun removeOrphanedLocalContact(service: ContactService, contact: ContactDto) {
        runReportingException<Unit>(
            onFailure = { error ->
                model.state.update { it.copy(error = ContactListError.ServiceMessage(model.serviceMessage(error))) }
            },
        ) {
            service.removeLocalContact(contactID = contact.id, publicKey = contact.publicKey)
            hideDeleted(service, contact)
        }
    }

    // The mask insert and the array removal land in one state write so the row is never briefly visible
    // as un-deletable before it is removed, matching the iOS single-transaction `hideDeletedContact`.
    private suspend fun hideDeleted(service: ContactService, contact: ContactDto) {
        currentCoroutineContext().ensureActive()
        if (model.isClosed ||
            model.dependencies.contactService() !== service ||
            model.dependencies.radioId() != contact.radioID
        ) {
            return
        }
        model.state.update {
            it.copy(
                pendingRemovalIds = it.pendingRemovalIds + contact.id,
                contacts = it.contacts.filterNot { existing -> existing.id == contact.id },
            )
        }
        model.dependencies.onContactRemoved(contact)
    }

    private fun liveServiceOrConnectToDelete(): ContactService? {
        val service = model.dependencies.contactService()
        if (service == null || !model.dependencies.isConnected()) {
            model.state.update { it.copy(error = ContactListError.ConnectToDelete) }
            return null
        }
        return service
    }

    private companion object {
        // Upper bound for a remove that round-trips to the radio (iOS `RadioCommandTimeout.delete`, 7s).
        const val DELETE_TIMEOUT_MS = 7_000L
    }
}
