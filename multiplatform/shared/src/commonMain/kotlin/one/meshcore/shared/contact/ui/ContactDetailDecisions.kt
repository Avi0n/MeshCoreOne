package one.meshcore.shared.contact.ui

import one.meshcore.shared.contact.ContactServiceError
import one.meshcore.shared.contact.VContactIdentity
import one.meshcore.shared.pathedit.HopResolution
import one.meshcore.shared.pathedit.PathEditState
import one.meshcore.shared.pathedit.PathManagementViewModel
import one.meshcore.shared.pathedit.cancelDiscovery

/**
 * False for the radio's own loopback V-contact, which must keep its slot or the firmware CLI turns off.
 * A missing self key still allows delete.
 */
internal fun allowDeleteContact(selfPublicKey: ByteArray?, contactPublicKey: ByteArray): Boolean =
    selfPublicKey?.let { !VContactIdentity.isVContact(contactPublicKey, it) } ?: true

/** A missing advert stays its own message; every other failure uses the mapped service text. */
internal fun shareAdvertFailureMessage(
    error: Throwable,
    shareUnavailable: String,
    contactErrorText: (Throwable) -> String?,
    errorOccurred: String,
): String = if (error is ContactServiceError.ShareContactUnavailable) {
    shareUnavailable
} else {
    contactErrorText(error) ?: errorOccurred
}

/** The one place hop hashes become names, through the same single-match resolver the path editor uses. */
internal fun resolvedHopName(state: PathEditState?, hash: ByteArray): String? =
    state?.let { HopResolution.resolveName(hash, it.allContacts, it.discoveredNodes) }

/** Binds a loaded contact into an optional callback without a branch at the call site. */
internal fun <T> callbackWith(value: T, callback: ((T) -> Unit)?): (() -> Unit)? = callback?.let { { it(value) } }

/** A blocked contact unblocks immediately; an open contact confirms first. */
internal fun blockedContactToggle(isBlocked: Boolean, unblock: () -> Unit, confirm: () -> Unit) {
    if (isBlocked) unblock() else confirm()
}

/** Setting a path is busy even when the plain send-and-reload flag is clear. */
internal fun pathCommandBusy(pathBusy: Boolean, isSettingPath: Boolean): Boolean = pathBusy || isSettingPath

/** Cancel is available only while a path view model owns the in-flight discovery. */
internal fun cancelDiscoveryAction(pathViewModel: PathManagementViewModel?): (() -> Unit)? =
    pathViewModel?.let { viewModel -> { viewModel.cancelDiscovery() } }
