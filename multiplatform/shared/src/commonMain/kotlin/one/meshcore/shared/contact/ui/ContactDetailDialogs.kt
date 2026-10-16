package one.meshcore.shared.contact.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.flow.consumeAsFlow
import one.meshcore.shared.avatar.AvatarCropGeometry
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.contact.ContactDetailLogic
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.ContactServiceEvent
import one.meshcore.shared.contact.contactType
import one.meshcore.shared.contact.displayName
import one.meshcore.shared.contact.events
import one.meshcore.shared.map.ui.LocalMapColorSchemePreference
import one.meshcore.shared.map.ui.MapLibreHost
import one.meshcore.shared.meshcore.PathInfo
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.pathedit.PathDiscoveryResult
import one.meshcore.shared.pathedit.PathEditError
import one.meshcore.shared.pathedit.PathManagementViewModel
import one.meshcore.shared.pathedit.clearError
import one.meshcore.shared.pathedit.close
import one.meshcore.shared.pathedit.dismissDiscoveryResult
import one.meshcore.shared.pathedit.handleDiscoveryResponse
import one.meshcore.shared.pathedit.handleDiscoveryWriteFailure
import one.meshcore.shared.pathedit.loadContacts
import one.meshcore.shared.pathedit.ui.text
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_action_block
import one.meshcore.shared.resources.contacts_common_cancel
import one.meshcore.shared.resources.contacts_common_delete
import one.meshcore.shared.resources.contacts_common_error
import one.meshcore.shared.resources.contacts_common_ok
import one.meshcore.shared.resources.contacts_detail_alert_block_message
import one.meshcore.shared.resources.contacts_detail_alert_block_title
import one.meshcore.shared.resources.contacts_detail_alert_clear_messages_message
import one.meshcore.shared.resources.contacts_detail_alert_clear_messages_title
import one.meshcore.shared.resources.contacts_detail_alert_delete_message
import one.meshcore.shared.resources.contacts_detail_alert_delete_title
import one.meshcore.shared.resources.contacts_detail_alert_path_discovery
import one.meshcore.shared.resources.contacts_detail_alert_path_error
import one.meshcore.shared.resources.contacts_detail_clear_messages
import one.meshcore.shared.ui.AcknowledgedAlertDialog
import one.meshcore.shared.ui.ConfirmAlertDialog
import one.meshcore.shared.ui.FullScreenOverlay
import one.meshcore.shared.ui.formattedStringResource
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ContactDetailConfirmDialogs(
    loaded: ContactDto?,
    showBlockConfirm: Boolean,
    showDeleteConfirm: Boolean,
    showClearConfirm: Boolean,
    onDismissBlock: () -> Unit,
    onBlock: (ContactDto) -> Unit,
    onDismissDelete: () -> Unit,
    onDelete: (ContactDto) -> Unit,
    onDismissClear: () -> Unit,
    onClear: (ContactDto) -> Unit,
    errorMessage: String?,
    onDismissError: () -> Unit,
) {
    if (loaded != null && showBlockConfirm) {
        ConfirmAlertDialog(
            title = stringResource(Res.string.contacts_detail_alert_block_title),
            message = formattedStringResource(Res.string.contacts_detail_alert_block_message, loaded.displayName),
            confirmLabel = stringResource(Res.string.contacts_action_block),
            dismissLabel = stringResource(Res.string.contacts_common_cancel),
            onConfirm = {
                onDismissBlock()
                onBlock(loaded)
            },
            onDismiss = onDismissBlock,
        )
    }
    if (loaded != null && showDeleteConfirm) {
        val typeLabel = loaded.contactType.kindLabel()
        ConfirmAlertDialog(
            title = formattedStringResource(Res.string.contacts_detail_alert_delete_title, typeLabel),
            message = formattedStringResource(Res.string.contacts_detail_alert_delete_message, loaded.displayName),
            confirmLabel = stringResource(Res.string.contacts_common_delete),
            dismissLabel = stringResource(Res.string.contacts_common_cancel),
            onConfirm = {
                onDismissDelete()
                onDelete(loaded)
            },
            onDismiss = onDismissDelete,
        )
    }
    if (loaded != null && showClearConfirm) {
        ConfirmAlertDialog(
            title = stringResource(Res.string.contacts_detail_alert_clear_messages_title),
            message = formattedStringResource(
                Res.string.contacts_detail_alert_clear_messages_message,
                loaded.displayName,
            ),
            confirmLabel = stringResource(Res.string.contacts_detail_clear_messages),
            dismissLabel = stringResource(Res.string.contacts_common_cancel),
            onConfirm = {
                onDismissClear()
                onClear(loaded)
            },
            onDismiss = onDismissClear,
        )
    }
    errorMessage?.let { message ->
        AcknowledgedAlertDialog(
            title = stringResource(Res.string.contacts_common_error),
            message = message,
            confirmLabel = stringResource(Res.string.contacts_common_ok),
            onDismiss = onDismissError,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ContactDetailSessionDialogs(
    loaded: ContactDto?,
    showFullMap: Boolean,
    isOffline: Boolean,
    onDismissMap: () -> Unit,
    cropRequest: Pair<ByteArray, ImageBitmap>?,
    onCancelCrop: () -> Unit,
    onCrop: (contactId: Uuid, source: ByteArray, geometry: AvatarCropGeometry) -> Unit,
    pathViewModel: PathManagementViewModel?,
    pathError: PathEditError?,
    showDiscoveryResult: Boolean,
    discoveryResult: PathDiscoveryResult?,
) {
    if (loaded != null && showFullMap) {
        ContactFullMapDialog(contact = loaded, isOffline = isOffline, onDismiss = onDismissMap)
    }
    if (loaded != null && cropRequest != null) {
        val contact = loaded
        val request = cropRequest
        AvatarCropDialog(
            image = request.second,
            onCancel = onCancelCrop,
            onComplete = { geometry -> onCrop(contact.id, request.first, geometry) },
        )
    }
    if (pathViewModel != null && pathError != null) {
        AcknowledgedAlertDialog(
            title = stringResource(Res.string.contacts_detail_alert_path_error),
            message = pathError.text(),
            confirmLabel = stringResource(Res.string.contacts_common_ok),
            onDismiss = { pathViewModel.clearError() },
        )
    }
    if (pathViewModel != null && showDiscoveryResult) {
        AlertDialog(
            onDismissRequest = { pathViewModel.dismissDiscoveryResult() },
            title = { Text(stringResource(Res.string.contacts_detail_alert_path_discovery)) },
            text = { if (discoveryResult != null) Text(discoveryResult.text()) },
            confirmButton = {
                TextButton(onClick = { pathViewModel.dismissDiscoveryResult() }) {
                    Text(stringResource(Res.string.contacts_common_ok))
                }
            },
        )
    }
}

@Composable
internal fun ContactDetailPathEffects(
    contactId: String,
    pathViewModel: PathManagementViewModel?,
    discoveryResult: PathDiscoveryResult?,
    radioId: Uuid?,
    contactService: ContactService?,
    reload: suspend () -> Unit,
) {
    if (pathViewModel != null) {
        // The service reports acceptance only after the radio write. A raw path push would show
        // success before a rejection, and the next contact sync would restore the old path.
        ContactPathResponseEffect(
            contactId,
            contactService,
            pathViewModel::handleDiscoveryResponse,
            pathViewModel::handleDiscoveryWriteFailure,
        )
        LaunchedEffect(discoveryResult) {
            if (discoveryResult != null) reload()
        }
        DisposableEffect(pathViewModel) {
            onDispose { pathViewModel.close() }
        }
    }
    // Without this load the resolver has nothing to match and every hop renders as bare hex.
    LaunchedEffect(pathViewModel, radioId) {
        val id = radioId ?: return@LaunchedEffect
        pathViewModel?.loadContacts(id)
    }
}

@Composable
internal fun ContactPathResponseEffect(
    contactId: String,
    contactService: ContactService?,
    onPathAccepted: (PathInfo) -> Unit,
    onWriteFailed: (Throwable) -> Unit,
) {
    val latestOnPathAccepted by rememberUpdatedState(onPathAccepted)
    val latestOnWriteFailed by rememberUpdatedState(onWriteFailed)
    LaunchedEffect(contactId, contactService) {
        contactService?.events()?.consumeAsFlow()?.collect { event ->
            when (event) {
                is ContactServiceEvent.PathDiscoveryAccepted -> latestOnPathAccepted(event.path)
                is ContactServiceEvent.PathDiscoveryWriteFailed -> latestOnWriteFailed(event.error)
                else -> Unit
            }
        }
    }
}

/**
 * The full-screen contact map: interactive, labelled, and showing the user's own position so a
 * responder can read the bearing to the node without leaving the app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContactFullMapDialog(contact: ContactDto, isOffline: Boolean, onDismiss: () -> Unit) {
    val point = remember(contact.id, contact.latitude, contact.longitude) {
        ContactDetailLogic.miniMapPoint(contact)
    } ?: return
    val camera = remember(contact.latitude, contact.longitude) {
        ContactDetailLogic.miniMapCamera(contact.latitude, contact.longitude)
    }
    val mapAppearance = LocalMapColorSchemePreference.current
    FullScreenOverlay(onDismiss = onDismiss, title = contact.displayName) { innerPadding ->
        MapLibreHost(
            points = listOf(point),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            colorSchemePreference = mapAppearance,
            initialCamera = camera,
            isOffline = isOffline,
            showsUserLocation = true,
            showsScale = true,
        )
    }
}
