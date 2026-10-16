package one.meshcore.shared.pathedit

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.update
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.setPath

// MARK: Save / reset / direct

/** Encodes and saves the edited path. Returns true on success; sets [PathEditState.errorMessage] on failure. */
suspend fun PathManagementViewModel.saveEditedPath(contact: ContactDto): Boolean {
    val service = writeService() ?: return false
    editState.update { it.copy(errorMessage = null) }
    val target = hashSize()
    val rejection = PathEditLogic.saveRejection(editState.value.editablePath, target, maxHopCount())
    if (rejection != null) {
        editState.update { it.copy(errorMessage = PathEditError.from(rejection)) }
        return false
    }
    editState.update { it.copy(isSettingPath = true) }
    return try {
        runPathWrite(PathEditError::SaveFailed) {
            val encoded = PathEditLogic.encodeEditablePath(editState.value.editablePath, target)
            service.setPath(contact.radioID, contact.publicKey, encoded.path, encoded.length)
        }
    } finally {
        editState.update { it.copy(isSettingPath = false) }
    }
}

/** Resets the contact to flood routing (`0xFF`) via the service. */
suspend fun PathManagementViewModel.resetPath(contact: ContactDto): Boolean {
    val service = writeService() ?: return false
    editState.update { it.copy(isSettingPath = true, errorMessage = null) }
    return try {
        runPathWrite(PathEditError::ResetFailed) {
            service.resetPath(contact.radioID, contact.publicKey)
        }
    } finally {
        editState.update { it.copy(isSettingPath = false) }
    }
}

/**
 * Saves a real 0-hop direct route: writes `outPathLength = 0x00` with an empty path. The one place
 * `0x00` is written — the user has asserted the neighbor is a direct 0-hop neighbor. "Direct" is the
 * out-path mode here, not a value derived from a hop count.
 */
suspend fun PathManagementViewModel.setDirectRouting(contact: ContactDto): Boolean {
    val service = writeService() ?: return false
    editState.update { it.copy(isSettingPath = true, errorMessage = null) }
    return try {
        runPathWrite(PathEditError::SetFailed) {
            service.setPath(
                contact.radioID,
                contact.publicKey,
                ByteArray(0),
                PathManagementViewModel.DIRECT_ROUTING_PATH_LENGTH,
            )
        }
    } finally {
        editState.update { it.copy(isSettingPath = false) }
    }
}

/**
 * Runs one contact path write. An exception is shown as [failed] and the write returns false.
 * Cancellation still stops the caller, and an Error still leaves it.
 */
@Suppress("TooGenericExceptionCaught")
private suspend fun PathManagementViewModel.runPathWrite(
    failed: (String) -> PathEditError,
    block: suspend () -> Unit,
): Boolean {
    try {
        block()
        dependencies.onContactNeedsRefresh()
        return true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        editState.update { it.copy(errorMessage = failed(serviceMessage(error))) }
        return false
    }
}

internal fun PathManagementViewModel.writeService(): ContactService? =
    dependencies.contactService().takeIf { dependencies.isRadioConnected() }

/** The localized lower-layer message to surface; never interpolates the exception itself. */
internal fun PathManagementViewModel.serviceMessage(error: Throwable): String = dependencies.errorText(error)
