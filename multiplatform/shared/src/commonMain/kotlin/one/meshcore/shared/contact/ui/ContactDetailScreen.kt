@file:OptIn(ExperimentalTime::class)

package one.meshcore.shared.contact.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.contact.ContactDetailLogic
import one.meshcore.shared.contact.ContactPingResult
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.clearContactMessages
import one.meshcore.shared.contact.displayName
import one.meshcore.shared.contact.isFloodRouted
import one.meshcore.shared.contact.pingAnnouncement
import one.meshcore.shared.contact.removeContact
import one.meshcore.shared.contact.setContactFavorite
import one.meshcore.shared.contact.updateContactPreferences
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_common_error
import one.meshcore.shared.resources.contacts_common_error_occurred
import one.meshcore.shared.resources.contacts_detail_avatar_invalid_image
import one.meshcore.shared.resources.contacts_detail_error_services_unavailable
import one.meshcore.shared.resources.contacts_detail_info
import one.meshcore.shared.resources.contacts_detail_ping_failure_announcement
import one.meshcore.shared.resources.contacts_detail_ping_success_announcement
import one.meshcore.shared.resources.contacts_detail_share_contact_unavailable
import one.meshcore.shared.ui.AppNestedTopBar
import one.meshcore.shared.ui.PlaceholderPane
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.rememberAccessibilityAnnouncer
import org.jetbrains.compose.resources.stringResource
import kotlin.time.ExperimentalTime

internal val SECTION_HORIZONTAL = 16.dp
internal val SECTION_GAP = 8.dp
internal const val DISABLED_ALPHA = 0.38f

/**
 * Contact detail: avatar header, identity, location mini-map, path controls, role-gated node
 * actions (telemetry / history / join / management), share + advert, favorite/block, ping, and
 * danger-zone delete / clear messages.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ContactDetailScreen(inputs: ContactDetailInputs, modifier: Modifier = Modifier) {
    val contactId = inputs.contactId
    val contactService = inputs.contactService
    val radioId = inputs.radioId
    val onBack = inputs.onBack
    val onSendMessage = inputs.onSendMessage
    val onEditPath = inputs.onEditPath
    val onShare = inputs.onShare
    val onZeroHopPing = inputs.onZeroHopPing
    val onShowTelemetry = inputs.onShowTelemetry
    val onShowSavedHistory = inputs.onShowSavedHistory
    val onJoinRoom = inputs.onJoinRoom
    val onManagement = inputs.onManagement
    val onOpenInMaps = inputs.onOpenInMaps
    val onDeleted = inputs.onDeleted
    val onMessagesCleared = inputs.onMessagesCleared
    val buildPathViewModel = inputs.buildPathViewModel
    val selfPublicKey = inputs.selfPublicKey
    val isRadioReady = inputs.isRadioReady
    val isRadioConnected = inputs.isRadioConnected
    val isOffline = inputs.isOffline
    val showFromDirectChat = inputs.showFromDirectChat
    val listInvalidations = inputs.listInvalidations
    val currentContactService = contactService()
    val currentRadioId = radioId()
    var contact by remember(contactId, currentRadioId) { mutableStateOf<ContactDto?>(null) }
    var loadFailed by remember(contactId, currentRadioId) { mutableStateOf(false) }
    var isPinging by remember(contactId, currentRadioId) { mutableStateOf(false) }
    var pingResult by remember(contactId, currentRadioId) { mutableStateOf<ContactPingResult?>(null) }
    var pathBusy by remember(contactId, currentRadioId) { mutableStateOf(false) }
    var pathStatus by remember(contactId, currentRadioId) { mutableStateOf<String?>(null) }
    var shareAdvertBusy by remember(contactId, currentRadioId) { mutableStateOf(false) }
    // The share-via-advert error text (already localized), or `null`; `shareAdvertSuccess` shows the
    // transient checkmark on a successful broadcast, mirroring the iOS 1.5s success animation.
    var shareAdvertStatus by remember(contactId, currentRadioId) { mutableStateOf<String?>(null) }
    var shareAdvertSuccess by remember(contactId, currentRadioId) { mutableStateOf(false) }
    var showBlockConfirm by remember(contactId, currentRadioId) { mutableStateOf(false) }
    var showDeleteConfirm by remember(contactId, currentRadioId) { mutableStateOf(false) }
    var showClearConfirm by remember(contactId, currentRadioId) { mutableStateOf(false) }
    var isSavingAvatar by remember(contactId, currentRadioId) { mutableStateOf(false) }
    var pendingCrop by remember(contactId, currentRadioId) { mutableStateOf<Pair<ByteArray, ImageBitmap>?>(null) }
    // Tapping the mini map opens the in-app full-screen map, as iOS pushes `ContactFullMapView`. Handing
    // off to a third-party maps app instead would lose the node's position for an offline responder.
    var showFullMap by remember(contactId, currentRadioId) { mutableStateOf(false) }
    // The single modal error surface for the destructive/block actions, mirroring the iOS detail
    // `errorMessage` + `.errorAlert`: a failed radio command surfaces here and never silently pops back.
    var errorMessage by remember(contactId, currentRadioId) { mutableStateOf<String?>(null) }
    val errorOccurred = stringResource(Res.string.contacts_common_error_occurred)
    val servicesUnavailable = stringResource(Res.string.contacts_detail_error_services_unavailable)
    val shareUnavailable = stringResource(Res.string.contacts_detail_share_contact_unavailable)
    val contactErrorText = rememberContactServiceErrorText()
    val avatarInvalidImage = stringResource(Res.string.contacts_detail_avatar_invalid_image)
    val pingSuccessAnnouncement = stringResource(Res.string.contacts_detail_ping_success_announcement)
    val pingFailureAnnouncement = stringResource(Res.string.contacts_detail_ping_failure_announcement)
    val pingAnnouncer = rememberAccessibilityAnnouncer()
    LaunchedEffect(pingResult) {
        val result = pingResult ?: return@LaunchedEffect
        pingAnnouncer.announce(
            pingAnnouncement(
                result = result,
                successTemplate = pingSuccessAnnouncement,
                failureMessage = pingFailureAnnouncement,
            ),
        )
    }
    val scope = rememberCoroutineScope()

    val actionReport = ContactActionReport(
        servicesUnavailable = servicesUnavailable,
        errorOccurred = errorOccurred,
        contactErrorText = contactErrorText,
        setError = { errorMessage = it },
    )

    suspend fun reload(service: ContactService? = contactService()) {
        val next = loadContactDetail(service, contact, contactId)
        contact = next.contact
        loadFailed = next.loadFailed
    }

    suspend fun performAction(onSucceeded: suspend () -> Unit, action: suspend (ContactService) -> Unit) {
        performContactDetailAction(
            service = contactService(),
            isRadioConnected = isRadioConnected,
            report = actionReport,
            onSucceeded = onSucceeded,
            action = action,
        )
    }

    fun runAction(onSucceeded: suspend () -> Unit, action: suspend (ContactService) -> Unit) {
        scope.launch { performAction(onSucceeded, action) }
    }

    LaunchedEffect(contactId, currentRadioId, currentContactService) {
        reload(currentContactService)
    }
    LaunchedEffect(contactId, currentRadioId, currentContactService, listInvalidations) {
        listInvalidations?.collect { reload() }
    }

    // Absent a builder, discovery falls back to the plain send-and-reload.
    val pathScope = rememberCoroutineScope()
    val pathViewModel = remember(contactId, currentRadioId, buildPathViewModel) {
        buildPathViewModel?.invoke(pathScope)
    }
    val pathState = pathViewModel?.state?.collectAsStateWithLifecycle()?.value
    ContactDetailPathEffects(
        contactId = contactId,
        pathViewModel = pathViewModel,
        discoveryResult = pathState?.discoveryResult,
        radioId = contact?.radioID,
        contactService = currentContactService,
        reload = { reload() },
    )

    val loaded = contact
    val pathCommands = PathCommandState(
        busy = pathBusy,
        setBusy = { pathBusy = it },
        setStatus = { pathStatus = it },
        reload = { reload() },
        perform = { onSucceeded, action -> performAction(onSucceeded, action) },
    )
    ContactDetailConfirmDialogs(
        loaded = loaded,
        showBlockConfirm = showBlockConfirm,
        showDeleteConfirm = showDeleteConfirm,
        showClearConfirm = showClearConfirm,
        onDismissBlock = { showBlockConfirm = false },
        onBlock = { contactToBlock ->
            runAction(onSucceeded = { reload() }) { service ->
                service.updateContactPreferences(contactID = contactToBlock.id, isBlocked = true)
            }
        },
        onDismissDelete = { showDeleteConfirm = false },
        onDelete = { contactToDelete ->
            runAction(onSucceeded = { onDeleted() }) { service ->
                service.removeContact(contactToDelete.radioID, contactToDelete.publicKey)
            }
        },
        onDismissClear = { showClearConfirm = false },
        onClear = { contactToClear ->
            runAction(onSucceeded = { onMessagesCleared() }) { service ->
                service.clearContactMessages(contactToClear.id)
            }
        },
        errorMessage = errorMessage,
        onDismissError = { errorMessage = null },
    )
    ContactDetailSessionDialogs(
        loaded = loaded,
        showFullMap = showFullMap,
        isOffline = isOffline,
        onDismissMap = { showFullMap = false },
        cropRequest = pendingCrop,
        onCancelCrop = { pendingCrop = null },
        onCrop = { croppedContactId, source, geometry ->
            scope.launchAvatarCrop(
                AvatarCropLaunch(
                    source = source,
                    geometry = geometry,
                    contactId = croppedContactId,
                    invalidImage = avatarInvalidImage,
                    setPending = { pendingCrop = it },
                    setSaving = { isSavingAvatar = it },
                    setError = { errorMessage = it },
                    performAndReload = { action ->
                        performAction(onSucceeded = { reload() }, action = action)
                    },
                ),
            )
        },
        pathViewModel = pathViewModel,
        pathError = pathState?.errorMessage,
        showDiscoveryResult = pathState?.showDiscoveryResult == true,
        discoveryResult = pathState?.discoveryResult,
    )

    Scaffold(
        modifier = modifier,
        topBar = {
            AppNestedTopBar(
                title = loaded?.displayName ?: stringResource(Res.string.contacts_detail_info),
                onBack = onBack,
            )
        },
    ) { innerPadding ->
        when {
            loaded != null -> ContactDetailBody(
                state = ContactDetailBodyState(
                    contact = loaded,
                    showFromDirectChat = showFromDirectChat,
                    isRadioReady = isRadioReady,
                    enablement = ContactDetailLogic.actionEnablement(
                        isRadioReady = isRadioReady,
                        busy = ContactDetailLogic.ActionBusyState(
                            isPinging = isPinging,
                            pathBusy = pathCommandBusy(pathBusy, pathState?.isSettingPath == true),
                            isDiscoveringPath = pathState?.isDiscovering == true,
                            isFloodRouted = loaded.isFloodRouted,
                            shareAdvertBusy = shareAdvertBusy,
                            shareAdvertSuccess = shareAdvertSuccess,
                        ),
                    ),
                    allowDelete = allowDeleteContact(selfPublicKey(), loaded.publicKey),
                    isPinging = isPinging,
                    pingResult = pingResult,
                    pathStatus = pathStatus,
                    isDiscoveringPath = pathState?.isDiscovering == true,
                    discoverySecondsRemaining = pathState?.discoverySecondsRemaining,
                    shareAdvertBusy = shareAdvertBusy,
                    shareAdvertError = shareAdvertStatus,
                    shareAdvertSuccess = shareAdvertSuccess,
                    isOffline = isOffline,
                    isSavingAvatar = isSavingAvatar,
                ),
                actions = ContactDetailBodyActions(
                    onCancelDiscovery = cancelDiscoveryAction(pathViewModel),
                    onSendMessage = { onSendMessage(loaded) },
                    onEditPath = { onEditPath(loaded) },
                    onShare = { onShare(loaded) },
                    onShareViaAdvert = {
                        scope.launchShareViaAdvert(
                            busy = shareAdvertBusy,
                            request = ShareAdvertLaunch(
                                contactService = contactService,
                                isRadioConnected = isRadioConnected,
                                publicKey = loaded.publicKey,
                                servicesUnavailable = servicesUnavailable,
                                shareUnavailable = shareUnavailable,
                                errorOccurred = errorOccurred,
                                contactErrorText = contactErrorText,
                                setBusy = { shareAdvertBusy = it },
                                setStatus = { shareAdvertStatus = it },
                                setSuccess = { shareAdvertSuccess = it },
                                setError = { errorMessage = it },
                            ),
                        )
                    },
                    onShowTelemetry = callbackWith(loaded, onShowTelemetry),
                    onShowSavedHistory = callbackWith(loaded, onShowSavedHistory),
                    onJoinRoom = callbackWith(loaded, onJoinRoom),
                    onManagement = callbackWith(loaded, onManagement),
                    onOpenInMaps = callbackWith(loaded, onOpenInMaps),
                    onExpandMap = { showFullMap = true },
                    resolveHopName = { hash -> resolvedHopName(pathState, hash) },
                    onPing = contactPingAction(
                        ping = onZeroHopPing,
                        scope = scope,
                        launchState = PingLaunch(
                            isPinging = isPinging,
                            contact = loaded,
                            setPinging = { isPinging = it },
                            setResult = { pingResult = it },
                        ),
                    ),
                    onToggleFavorite = {
                        runAction(onSucceeded = { reload() }) { service ->
                            service.setContactFavorite(loaded.id, !loaded.isFavorite)
                        }
                    },
                    onRequestBlock = {
                        blockedContactToggle(
                            isBlocked = loaded.isBlocked,
                            unblock = {
                                runAction(onSucceeded = { reload() }) { service ->
                                    service.updateContactPreferences(contactID = loaded.id, isBlocked = false)
                                }
                            },
                            confirm = { showBlockConfirm = true },
                        )
                    },
                    onSaveNickname = { nickname ->
                        runAction(onSucceeded = { reload() }) { service ->
                            service.updateContactPreferences(contactID = loaded.id, nickname = nickname)
                        }
                    },
                    onDiscoverPath = {
                        scope.launchPathDiscovery(pathViewModel, loaded, pathCommands)
                    },
                    onResetPath = {
                        scope.launchPathReset(pathViewModel, loaded, pathCommands)
                    },
                    onClearMessages = { showClearConfirm = true },
                    onDelete = { showDeleteConfirm = true },
                    onAvatarPicked = { picked ->
                        scope.launchAvatarPick(
                            isSaving = isSavingAvatar,
                            picked = picked,
                            onPending = { pendingCrop = it },
                            onInvalid = { errorMessage = avatarInvalidImage },
                        )
                    },
                    onAvatarRemoved = {
                        scope.launchAvatarRemove(
                            isSaving = isSavingAvatar,
                            contactId = loaded.id,
                            setSaving = { isSavingAvatar = it },
                            performAndReload = { action ->
                                performAction(onSucceeded = { reload() }, action = action)
                            },
                        )
                    },
                ),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState()),
            )
            loadFailed -> PlaceholderPane(
                title = stringResource(Res.string.contacts_common_error),
                subtitle = stringResource(Res.string.contacts_detail_error_services_unavailable),
                icon = AppIcons.Warning,
                modifier = Modifier.padding(innerPadding),
            )
            else -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }
    }
}
