package one.meshcore.shared.contact.ui

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import one.meshcore.shared.avatar.AvatarCropGeometry
import one.meshcore.shared.avatar.cropAvatarImage
import one.meshcore.shared.avatar.decodeAvatarImage
import one.meshcore.shared.avatar.downsampleAvatarForCrop
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.contact.ContactDetailLogic
import one.meshcore.shared.contact.ContactPingResult
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.getContactByID
import one.meshcore.shared.contact.sendPathDiscovery
import one.meshcore.shared.contact.updateContactAvatar
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.pathedit.PathManagementViewModel
import one.meshcore.shared.pathedit.discoverPath
import one.meshcore.shared.pathedit.resetPath

/** How long the share-via-advert success checkmark stays up, matching the 1.5s animation. */
private const val SHARE_SUCCESS_DURATION_MS = 1_500L

internal data class ContactActionReport(
    val servicesUnavailable: String,
    val errorOccurred: String,
    val contactErrorText: (Throwable) -> String?,
    val setError: (String) -> Unit,
)

internal data class PathCommandState(
    val busy: Boolean,
    val setBusy: (Boolean) -> Unit,
    val setStatus: (String?) -> Unit,
    val reload: suspend () -> Unit,
    val perform: suspend (onSucceeded: suspend () -> Unit, action: suspend (ContactService) -> Unit) -> Unit,
)

internal data class ShareAdvertLaunch(
    val contactService: () -> ContactService?,
    val isRadioConnected: Boolean,
    val publicKey: ByteArray,
    val servicesUnavailable: String,
    val shareUnavailable: String,
    val errorOccurred: String,
    val contactErrorText: (Throwable) -> String?,
    val setBusy: (Boolean) -> Unit,
    val setStatus: (String?) -> Unit,
    val setSuccess: (Boolean) -> Unit,
    val setError: (String) -> Unit,
)

internal data class PingLaunch(
    val isPinging: Boolean,
    val contact: ContactDto,
    val setPinging: (Boolean) -> Unit,
    val setResult: (ContactPingResult?) -> Unit,
)

internal data class AvatarCropLaunch(
    val source: ByteArray,
    val geometry: AvatarCropGeometry,
    val contactId: Uuid,
    val invalidImage: String,
    val setPending: (Pair<ByteArray, ImageBitmap>?) -> Unit,
    val setSaving: (Boolean) -> Unit,
    val setError: (String) -> Unit,
    val performAndReload: suspend (suspend (ContactService) -> Unit) -> Unit,
)

internal suspend fun loadContactDetail(
    service: ContactService?,
    current: ContactDto?,
    contactId: String,
): ContactDetailLogic.ContactReload {
    if (service == null) return ContactDetailLogic.ContactReload(contact = null, loadFailed = true)
    return ContactDetailLogic.reloadContact(current) {
        service.getContactByID(Uuid.normalized(contactId))
    }
}

/**
 * Runs a radio-backed action and reports its outcome on the one error alert. A lost service raises
 * the unavailable error instead of running [onSucceeded], so the screen can never claim a change
 * the radio never made.
 */
internal suspend fun performContactDetailAction(
    service: ContactService?,
    isRadioConnected: Boolean,
    report: ContactActionReport,
    onSucceeded: suspend () -> Unit,
    action: suspend (ContactService) -> Unit,
) {
    when (
        val outcome = ContactDetailLogic.runContactAction(
            ContactDetailLogic.writeService(service, isRadioConnected),
            action,
        )
    ) {
        ContactDetailLogic.ActionOutcome.Succeeded -> onSucceeded()
        ContactDetailLogic.ActionOutcome.Unavailable -> report.setError(report.servicesUnavailable)
        is ContactDetailLogic.ActionOutcome.Failed ->
            report.setError(report.contactErrorText(outcome.error) ?: report.errorOccurred)
    }
}

internal fun CoroutineScope.launchShareViaAdvert(busy: Boolean, request: ShareAdvertLaunch) {
    if (busy) return
    request.setBusy(true)
    request.setStatus(null)
    launch {
        val outcome = ContactDetailLogic.shareViaAdvert(
            request.contactService(),
            request.isRadioConnected,
            request.publicKey,
        )
        request.setBusy(false)
        when (outcome) {
            ContactDetailLogic.ActionOutcome.Succeeded -> {
                request.setSuccess(true)
                delay(SHARE_SUCCESS_DURATION_MS)
                request.setSuccess(false)
            }
            ContactDetailLogic.ActionOutcome.Unavailable -> request.setError(request.servicesUnavailable)
            is ContactDetailLogic.ActionOutcome.Failed -> request.setStatus(
                shareAdvertFailureMessage(
                    outcome.error,
                    request.shareUnavailable,
                    request.contactErrorText,
                    request.errorOccurred,
                ),
            )
        }
    }
}

internal fun contactPingAction(
    ping: (suspend (ContactDto) -> ContactPingResult)?,
    scope: CoroutineScope,
    launchState: PingLaunch,
): (() -> Unit)? = ping?.let { callback ->
    { scope.launchContactPing(callback, launchState) }
}

internal fun CoroutineScope.launchContactPing(
    ping: suspend (ContactDto) -> ContactPingResult,
    launchState: PingLaunch,
) {
    if (launchState.isPinging) return
    launchState.setPinging(true)
    launchState.setResult(null)
    launch {
        launchState.setResult(ping(launchState.contact))
        launchState.setPinging(false)
    }
}

internal fun CoroutineScope.launchPathDiscovery(
    pathViewModel: PathManagementViewModel?,
    contact: ContactDto,
    state: PathCommandState,
) {
    // The live flow owns the countdown, cancel, and result. The plain send-and-reload is the
    // fallback when no path view model is wired.
    if (pathViewModel != null) {
        pathViewModel.discoverPath(contact)
    } else if (!state.busy) {
        state.setBusy(true)
        state.setStatus(null)
        launch {
            state.perform({ state.reload() }) { service ->
                service.sendPathDiscovery(contact.publicKey)
            }
            state.setBusy(false)
        }
    }
}

internal fun CoroutineScope.launchPathReset(
    pathViewModel: PathManagementViewModel?,
    contact: ContactDto,
    state: PathCommandState,
) {
    // A wired path view model reports a fault on the path-error alert. The fire-and-reload path
    // is the fallback when that view model is absent.
    if (pathViewModel != null) {
        launch {
            pathViewModel.resetPath(contact)
            state.reload()
        }
    } else if (!state.busy) {
        state.setBusy(true)
        state.setStatus(null)
        launch {
            state.perform({ state.reload() }) { service ->
                service.resetPath(contact.radioID, contact.publicKey)
            }
            state.setBusy(false)
        }
    }
}

internal fun CoroutineScope.launchAvatarPick(
    isSaving: Boolean,
    picked: ByteArray,
    onPending: (Pair<ByteArray, ImageBitmap>) -> Unit,
    onInvalid: () -> Unit,
) {
    if (isSaving) return
    launch {
        val pending = withContext(Dispatchers.Default) {
            val downsampled = downsampleAvatarForCrop(picked) ?: return@withContext null
            val image = decodeAvatarImage(downsampled) ?: return@withContext null
            downsampled to image
        }
        if (pending != null) onPending(pending) else onInvalid()
    }
}

internal fun CoroutineScope.launchAvatarRemove(
    isSaving: Boolean,
    contactId: Uuid,
    setSaving: (Boolean) -> Unit,
    performAndReload: suspend (suspend (ContactService) -> Unit) -> Unit,
) {
    if (isSaving) return
    setSaving(true)
    launch {
        performAndReload { service -> service.updateContactAvatar(contactId, null) }
        setSaving(false)
    }
}

internal fun CoroutineScope.launchAvatarCrop(crop: AvatarCropLaunch) {
    crop.setPending(null)
    crop.setSaving(true)
    launch {
        val cropped = withContext(Dispatchers.Default) {
            cropAvatarImage(crop.source, crop.geometry)
        }
        if (cropped != null) {
            crop.performAndReload { service -> service.updateContactAvatar(crop.contactId, cropped) }
        } else {
            crop.setError(crop.invalidImage)
        }
        crop.setSaving(false)
    }
}
