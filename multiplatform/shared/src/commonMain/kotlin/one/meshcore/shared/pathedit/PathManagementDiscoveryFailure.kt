package one.meshcore.shared.pathedit

import kotlinx.coroutines.flow.update

/**
 * A radio rejection while discovery is open. A failure after timeout or cancel leaves the alert
 * already on screen unchanged, and the stored path is left as it was.
 */
fun PathManagementViewModel.handleDiscoveryWriteFailure(error: Throwable) {
    if (!editState.value.isDiscovering) return
    discoveryJob?.cancel()
    countdownJob?.cancel()
    discoveringContact = null
    editState.update {
        it.copy(
            isDiscovering = false,
            discoverySecondsRemaining = null,
            discoveryResult = PathDiscoveryResult.Failed(serviceMessage(error)),
            showDiscoveryResult = true,
        )
    }
}
