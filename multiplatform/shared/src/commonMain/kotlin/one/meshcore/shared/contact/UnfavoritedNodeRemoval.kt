package one.meshcore.shared.contact

import kotlinx.coroutines.CancellationException
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.runThrowableResult

/**
 * Removes every non-favorite contact for [radioID] via the live [ContactService]
 * (Danger Zone "Remove Non-Favorite Nodes"). Mirrors iOS `ConnectionManager.removeUnfavoritedNodes`,
 * including the V-contact exclusion in `removeContacts`.
 */
suspend fun removeUnfavoritedNodes(radioID: Uuid, contactService: ContactService): StaleNodeRemovalResult {
    val targets = contactService.excludingProtectedVContact(
        radioID,
        contactService.getContacts(radioID).filter { !it.isFavorite },
    )
    if (targets.isEmpty()) return StaleNodeRemovalResult(removed = 0, total = 0)
    return removeUnfavoritedTargets(radioID, contactService, targets)
}

private suspend fun removeUnfavoritedTargets(
    radioID: Uuid,
    contactService: ContactService,
    targets: List<ContactDto>,
): StaleNodeRemovalResult {
    var removed = 0
    for (target in targets) {
        when (deleteUnfavorited(radioID, contactService, target)) {
            UnfavoritedDelete.Removed -> removed++
            UnfavoritedDelete.Skipped -> Unit
            UnfavoritedDelete.Stopped -> return StaleNodeRemovalResult(removed = removed, total = targets.size)
        }
    }
    return StaleNodeRemovalResult(removed = removed, total = targets.size)
}

private suspend fun deleteUnfavorited(
    radioID: Uuid,
    contactService: ContactService,
    target: ContactDto,
): UnfavoritedDelete = try {
    contactService.removeContact(radioID = radioID, publicKey = target.publicKey)
    UnfavoritedDelete.Removed
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: ContactServiceError.ContactNotFound) {
    removeForgottenLocally(contactService, target)
} catch (_: Throwable) {
    UnfavoritedDelete.Stopped
}

/**
 * Radio already forgot this contact. A local failure leaves the row and the pass continues.
 * Cancellation still stops the pass.
 */
private suspend fun removeForgottenLocally(contactService: ContactService, target: ContactDto): UnfavoritedDelete =
    runThrowableResult({ UnfavoritedDelete.Skipped }) {
        contactService.removeLocalContact(contactID = target.id, publicKey = target.publicKey)
        UnfavoritedDelete.Removed
    }

private sealed interface UnfavoritedDelete {
    data object Removed : UnfavoritedDelete
    data object Skipped : UnfavoritedDelete
    data object Stopped : UnfavoritedDelete
}

/** Count of non-favorite contacts on [radioID]. Persist faults propagate (iOS `unfavoritedNodeCount`). */
suspend fun unfavoritedNodeCount(radioID: Uuid, contactService: ContactService): Int =
    contactService.getContacts(radioID).count { !it.isFavorite }
