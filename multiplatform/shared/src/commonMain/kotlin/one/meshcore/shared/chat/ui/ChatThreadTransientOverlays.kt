package one.meshcore.shared.chat.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.chat.SendDMViewModel
import one.meshcore.shared.chat.pathdetail.BlockSenderViewModel
import one.meshcore.shared.chat.pathdetail.loadDiscoveredRepeaters
import one.meshcore.shared.chat.pathdetail.loadRepeaterContacts
import one.meshcore.shared.chat.pathdetail.repeatRowResolution
import one.meshcore.shared.contact.DiscoveredNodeRecord
import one.meshcore.shared.contact.UserLocation
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_alert_unable_to_send_title
import one.meshcore.shared.resources.chats_repeats_unknown_repeater
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun ThreadPathMapOverlay(
    overlays: ThreadOverlayState,
    environment: ChatThreadEnvironment,
    isNetworkOffline: Boolean,
) {
    val locationService = environment.locationService
    val mapDisplayPreferences = environment.mapDisplayPreferences
    val nodeCoordinate = environment.nodeCoordinate
    overlays.pathMapDisplay?.let { display ->
        if (shouldPresentPathMap(actionsSheetOpen = overlays.selected != null, display = display.initialDisplay)) {
            MessagePathSheet(
                presentation = display,
                onDismiss = { overlays.pathMapDisplay = null },
                isOffline = isNetworkOffline,
                locationService = locationService(),
                mapDisplayPreferences = mapDisplayPreferences,
                knownLocation = {
                    locationService()?.currentLocation?.value ?: nodeCoordinate()
                },
            )
        }
    }
}

@Composable
internal fun ThreadSendDmOverlay(
    overlays: ThreadOverlayState,
    environment: ChatThreadEnvironment,
    onOpenContactChat: (String) -> Unit,
    userLocation: () -> UserLocation?,
) {
    val currentEnvironment by rememberUpdatedState(environment)
    val radioId = environment.radioId
    val contactService = environment.contactService
    val currentContactService = contactService()
    overlays.sendDmItem?.let { item ->
        val radio = radioId()
        val senderName = item.envelope.senderName
        if (radio != null && senderName.isNotEmpty()) {
            val sendDmVm = remember(item.id) {
                SendDMViewModel(
                    SendDMViewModel.Dependencies(contactService = { currentEnvironment.contactService() }),
                )
            }
            SendDMSheet(
                viewModel = sendDmVm,
                serviceOwner = currentContactService,
                senderName = senderName,
                radioID = radio,
                unverifiedNickname = item.envelope.senderResolution.unverifiedNickname,
                onSelect = { contact ->
                    overlays.sendDmItem = null
                    onOpenContactChat(contact.id.value)
                },
                onDismiss = { overlays.sendDmItem = null },
                userLocation = userLocation(),
            )
        } else {
            overlays.sendDmItem = null
        }
    }
}

@Composable
internal fun ThreadRepeatDetailsOverlay(
    overlays: ThreadOverlayState,
    environment: ChatThreadEnvironment,
    userLocation: () -> UserLocation?,
) {
    val contactService = environment.contactService
    val radioId = environment.radioId
    val fetchDiscoveredRepeaters = environment.fetchDiscoveredRepeaters
    overlays.repeatItem?.let { item ->
        val currentContactService = contactService()
        val currentRadioId = radioId()
        var repeaterContacts by remember(item.id, currentContactService, currentRadioId) {
            mutableStateOf<List<ContactDto>>(emptyList())
        }
        var discoveredRepeaters by remember(item.id, currentRadioId) {
            mutableStateOf<List<DiscoveredNodeRecord>>(emptyList())
        }
        LaunchedEffect(item.id, currentContactService, currentRadioId) {
            repeaterContacts = loadRepeaterContacts(currentContactService, currentRadioId)
            discoveredRepeaters = loadDiscoveredRepeaters(currentRadioId, fetchDiscoveredRepeaters)
        }
        val user = userLocation()
        val unknownRepeater = stringResource(Res.string.chats_repeats_unknown_repeater)
        RepeatDetailsSheet(
            repeats = overlays.repeatRows,
            onDismiss = { overlays.showRepeat(null) },
            resolveRepeater = { hash ->
                repeatRowResolution(
                    repeaterHash = hash,
                    contacts = repeaterContacts,
                    discovered = discoveredRepeaters,
                    userLocation = user,
                    unknownName = unknownRepeater,
                )
            },
        )
    }
}

@Composable
internal fun ThreadBlockSenderOverlay(
    overlays: ThreadOverlayState,
    environment: ChatThreadEnvironment,
    userLocation: () -> UserLocation?,
    onChannelSenderBlocked: () -> Unit,
) {
    val currentEnvironment by rememberUpdatedState(environment)
    val radioId = environment.radioId
    val contactService = environment.contactService
    val currentContactService = contactService()
    overlays.blockSenderItem?.let { item ->
        val radio = radioId()
        val senderName = item.envelope.senderName
        if (radio != null && senderName.isNotEmpty()) {
            val blockVm = remember(item.id) {
                BlockSenderViewModel(
                    BlockSenderViewModel.Dependencies(
                        contactService = { currentEnvironment.contactService() },
                        blockedChannelSenderService = { currentEnvironment.blockedChannelSenderService() },
                    ),
                )
            }
            BlockSenderSheet(
                senderName = senderName,
                radioID = radio,
                viewModel = blockVm,
                serviceOwner = currentContactService,
                onDismiss = { overlays.blockSenderItem = null },
                userLocation = userLocation(),
                onBlocked = onChannelSenderBlocked,
            )
        } else {
            overlays.blockSenderItem = null
        }
    }
}

@Composable
internal fun ThreadErrorDialogs(
    errorMessage: String?,
    sendErrorMessage: String?,
    onClearError: () -> Unit,
    onClearSendError: () -> Unit,
) {
    errorMessage?.let { ThreadErrorDialog(message = it, onDismiss = onClearError) }
    sendErrorMessage?.let {
        ThreadErrorDialog(
            message = it,
            onDismiss = onClearSendError,
            dialogTitle = stringResource(Res.string.chats_alert_unable_to_send_title),
        )
    }
}
