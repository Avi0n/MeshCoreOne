package one.meshcore.shared.room.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import one.meshcore.shared.avatar.AvatarCategory
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.backup.RoomMessageDto
import one.meshcore.shared.chat.ConversationListStore
import one.meshcore.shared.chat.SendDMViewModel
import one.meshcore.shared.chat.launchAfterMessageActionsDismiss
import one.meshcore.shared.chat.translation.MessageTranslationPhase
import one.meshcore.shared.chat.translation.applyComposerTranslate
import one.meshcore.shared.chat.translation.applySheetTranslate
import one.meshcore.shared.chat.translation.presentOverlayIfNeeded
import one.meshcore.shared.chat.ui.SendDMSheet
import one.meshcore.shared.chat.ui.rememberMessageTranslation
import one.meshcore.shared.connection.DeviceConnectionState
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.getContact
import one.meshcore.shared.contact.ui.NodeAvatar
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_error_load_messages_failed
import one.meshcore.shared.resources.chats_room_auth_not_found_title
import one.meshcore.shared.resources.localizable_error_room_server_send_failed
import one.meshcore.shared.resources.localizable_error_room_server_session_not_found
import one.meshcore.shared.resources.remotenodes_remote_nodes_room_disconnected
import one.meshcore.shared.resources.remotenodes_remote_nodes_room_info_title
import one.meshcore.shared.room.RoomServerService
import one.meshcore.shared.room.fetchSession
import one.meshcore.shared.ui.AppNestedTopBar
import one.meshcore.shared.ui.PlaceholderPane
import one.meshcore.shared.ui.THREAD_HEADER_AVATAR_DP
import one.meshcore.shared.ui.icons.AppIconButton
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.plainTextClipEntry
import org.jetbrains.compose.resources.stringResource

/**
 * Pushed [one.meshcore.shared.navigation.ChatRoute.Room] destination: resolves the session by id, hosts
 * [RoomConversationScreen] with system Back, Info, long-press actions, and nested room auth so Back from
 * auth returns to the room (not the list). In-thread Connect always presents auth, matching iOS
 * `RoomAuthenticationSheet`: a missing contact row is Not Found instead of a no-op.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RoomDetailPane(inputs: RoomDetailInputs, modifier: Modifier = Modifier) {
    val currentInputs by rememberUpdatedState(inputs)
    val sessionId = inputs.sessionId
    val roomServerService = inputs.roomServerService
    val contactService = inputs.contactService
    val radioId = inputs.radioId
    val conversationListStore = inputs.conversationListStore
    val roomAdminService = inputs.roomAdminService
    val nodePasswordStore = inputs.nodePasswordStore
    val servicesChanged = inputs.servicesChanged
    val appForeground = inputs.appForeground
    val onBack = inputs.onBack
    val nodeSnapshotService = inputs.nodeSnapshotService
    val roomNotifications = inputs.roomNotifications
    val onConversationsChanged = inputs.onConversationsChanged
    val updateBadge = inputs.updateBadge
    val onOpenContactChat = inputs.onOpenContactChat
    val onLinkTap = inputs.onLinkTap
    val localNodeName = inputs.localNodeName
    val locationService = inputs.locationService
    val replyWithQuote = inputs.replyWithQuote
    val connectionState = inputs.connectionState
    val presentSystemTranslation = inputs.presentSystemTranslation
    val translationTargetPreferences = inputs.translationTargetPreferences
    val mapDisplayPreferences = inputs.mapDisplayPreferences
    val discoveredNodeStore = inputs.discoveredNodeStore
    val userLocation = inputs.userLocation
    val radioConnection by connectionState.collectAsStateWithLifecycle()
    val radioReady = radioConnection == DeviceConnectionState.READY
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    var infoHost by remember { mutableStateOf(RoomInfoHostState()) }
    var showAuth by remember { mutableStateOf(false) }
    var selectedMessage by remember { mutableStateOf<RoomMessageDto?>(null) }
    var sendDmAuthor by remember { mutableStateOf<String?>(null) }
    var authContact by remember { mutableStateOf<ContactDto?>(null) }
    val currentRoomServerService = roomServerService()
    val currentContactService = contactService()
    val currentConversationListStore = conversationListStore()
    val currentRadioId = radioId()

    var session by remember(sessionId, currentRoomServerService, currentConversationListStore, currentRadioId) {
        mutableStateOf<RemoteNodeSessionDto?>(null)
    }
    var loadFailed by remember(sessionId, currentRoomServerService, currentConversationListStore, currentRadioId) {
        mutableStateOf(false)
    }
    LaunchedEffect(
        sessionId,
        currentRoomServerService,
        currentConversationListStore,
        currentRadioId,
        inputs.sessionSnapshot,
    ) {
        val snapshot = inputs.sessionSnapshot?.takeIf {
            it.id == Uuid.normalized(sessionId) && it.radioID == currentRadioId
        }
        val resolved = snapshot ?: resolveRoomSession(
            sessionId,
            currentRoomServerService,
            currentConversationListStore,
            currentRadioId,
        )
        currentCoroutineContext().ensureActive()
        if (currentInputs.roomServerService() !== currentRoomServerService ||
            currentInputs.conversationListStore() !== currentConversationListStore ||
            currentInputs.radioId() != currentRadioId
        ) {
            return@LaunchedEffect
        }
        if (resolved?.id == Uuid.normalized(sessionId) && resolved.radioID == currentRadioId) session = resolved
        loadFailed = session == null
    }

    val live = session
    if (live == null) {
        RoomDetailLoadingScaffold(loadFailed = loadFailed, onBack = onBack, modifier = modifier)
        return
    }

    val roomErrorText = rememberRoomServerErrorText()
    val loadFailedMessage = stringResource(Res.string.chats_error_load_messages_failed)
    val sendFailedMessage = stringResource(Res.string.localizable_error_room_server_send_failed)
    val currentRoomErrorText by rememberUpdatedState(roomErrorText)
    val currentLoadFailedMessage by rememberUpdatedState(loadFailedMessage)
    val currentSendFailedMessage by rememberUpdatedState(sendFailedMessage)
    val currentOnConversationsChanged by rememberUpdatedState(onConversationsChanged)
    val viewModel = remember(
        live.id,
        live.radioID,
        live.publicKey.contentHashCode(),
    ) {
        RoomConversationViewModel(
            scope = scope,
            session = live,
            dependencies = RoomConversationViewModel.Dependencies(
                roomServerService = { currentInputs.roomServerService() },
                notifications = roomNotifications,
                servicesChanged = servicesChanged,
                errorText = { error -> currentRoomErrorText(error) },
                loadFailedMessage = { currentLoadFailedMessage },
                sendFailedMessage = { currentSendFailedMessage },
                onViewed = { currentOnConversationsChanged() },
                isRadioReady = { currentInputs.connectionState.value == DeviceConnectionState.READY },
            ),
        )
    }
    LaunchedEffect(viewModel, live) { viewModel.adoptSessionSnapshot(live) }

    LaunchedEffect(viewModel, servicesChanged) {
        viewModel.bindServicesChanged(servicesChanged)
    }
    val conversationState by viewModel.state.collectAsStateWithLifecycle()
    val translationSession = rememberMessageTranslation(live.id, translationTargetPreferences?.state)
    val translation = translationSession.controller
    val translationRevision = translationSession.revision
    val translationPrefs = translationSession.preferences
    LaunchedEffect(conversationState.messages, translation, translationPrefs.translationOffersEnabled) {
        seedRoomTranslations(
            offersEnabled = translationPrefs.translationOffersEnabled,
            messages = conversationState.messages,
        ) { id, text ->
            translation.seedIfNeeded(id, text)
        }
    }

    LaunchedEffect(viewModel, appForeground) {
        appForeground.collect { viewModel.onEnterForeground() }
    }

    // The room session's keep-alive runs only while its conversation is on screen, matching the iOS
    // task/onDisappear pair; leaving without this stop would leave the loop ticking after the screen dies.
    DisposableEffect(viewModel) {
        onDispose { viewModel.dispose() }
    }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            val infoLabel = stringResource(Res.string.remotenodes_remote_nodes_room_info_title)
            AppNestedTopBar(
                title = conversationState.sessionName.ifEmpty { live.name },
                subtitle = roomDetailSubtitle(
                    connected = conversationState.isConnected,
                    permissionLabel = conversationState.permissionLevel.label(),
                    disconnectedLabel = stringResource(Res.string.remotenodes_remote_nodes_room_disconnected),
                ),
                titleIcon = { NodeAvatar(AvatarCategory.ROOM, THREAD_HEADER_AVATAR_DP.dp) },
                onTitleTap = { infoHost = infoHost.openInfo() },
                onBack = onBack,
                actions = {
                    AppIconButton(
                        imageVector = AppIcons.Info,
                        contentDescription = infoLabel,
                        onClick = { infoHost = infoHost.openInfo() },
                    )
                },
            )
        },
    ) { padding ->
        RoomConversationScreen(
            viewModel = viewModel,
            onConnect = {
                scope.launch {
                    authContact = resolveRoomContact(radioId, contactService, viewModel.currentSession)
                    showAuth = true
                }
            },
            onMessageLongPress = { selectedMessage = it },
            onLinkTap = { target ->
                applyRoomLinkTap(messageSelected = selectedMessage != null, target = target, open = onLinkTap)
            },
            localNodeName = localNodeName,
            radioReady = radioReady,
            translationPhase = { id ->
                translationRevision
                val fromSelf = conversationState.messages.firstOrNull { it.id == id }?.isFromSelf == true
                translation.chrome(id, fromSelf)
            },
            onTranslate = { item ->
                applyComposerTranslate(
                    showing = translation.chrome(item.id) is MessageTranslationPhase.Showing,
                    usesSystemOverlay = translationPrefs.usesSystemOverlay,
                    presentSystem = { presentSystemTranslation(item.text) },
                    perform = {
                        scope.launch {
                            translation.performAction(item.id, item.text)
                                .presentOverlayIfNeeded(presentSystemTranslation)
                        }
                    },
                )
            },
            modifier = Modifier.padding(padding),
        )
    }

    RoomDetailInfoOverlays(
        infoHost = infoHost,
        onInfoHostChange = { infoHost = it },
        currentSession = { viewModel.currentSession },
        conversationListStore = conversationListStore,
        onConversationsChanged = onConversationsChanged,
        updateBadge = updateBadge,
        connectionState = connectionState,
        roomAdminService = roomAdminService,
        nodeSnapshotService = nodeSnapshotService,
        contactService = contactService,
        radioId = radioId,
        radioReady = radioReady,
        locationService = locationService,
        mapDisplayPreferences = mapDisplayPreferences,
        scope = scope,
        workspaces = inputs.remoteAdminWorkspaces,
    )

    selectedMessage?.let { message ->
        RoomMessageActionOverlay(
            message = message,
            session = viewModel.currentSession,
            viewModel = viewModel,
            onSendDm = { name ->
                selectedMessage = null
                scope.launchAfterMessageActionsDismiss { sendDmAuthor = name }
            },
            replyWithQuote = replyWithQuote,
            displayedText = translation.displayedText(message.id, message.text),
            copyText = { text -> scope.launch { clipboard.setClipEntry(plainTextClipEntry(text)) } },
            onTranslate = {
                applySheetTranslate(
                    showing = translation.chrome(message.id) is MessageTranslationPhase.Showing,
                    presentSystem = { presentSystemTranslation(message.text) },
                    perform = {
                        scope.launch {
                            translation.performAction(message.id, message.text)
                                .presentOverlayIfNeeded(presentSystemTranslation)
                        }
                    },
                )
            },
            translateLabel = roomTranslateActionText(translation.chrome(message.id)),
            onDismiss = { selectedMessage = null },
        )
    }

    sendDmAuthor?.let { name ->
        val radio = radioId()
        if (radio != null) {
            val sendDmVm = remember(name) {
                SendDMViewModel(
                    SendDMViewModel.Dependencies(contactService = { currentInputs.contactService() }),
                )
            }
            SendDMSheet(
                viewModel = sendDmVm,
                serviceOwner = currentContactService,
                senderName = name,
                radioID = radio,
                unverifiedNickname = null,
                onSelect = { contact ->
                    sendDmAuthor = null
                    onOpenContactChat(contact.id.value)
                },
                onDismiss = { sendDmAuthor = null },
            )
        } else {
            sendDmAuthor = null
        }
    }

    if (showAuth) {
        RoomAuthOverlay(
            contact = authContact,
            roomServerService = roomServerService,
            contactService = contactService,
            radioId = radioId,
            isRadioConnected = { radioConnection.isConnected },
            nodePasswordStore = nodePasswordStore,
            discoveredNodeStore = discoveredNodeStore,
            userLocation = userLocation,
            onAuthenticated = { authenticated ->
                session = authenticated
                showAuth = false
                viewModel.refreshSession()
            },
            onDismiss = { showAuth = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoomDetailLoadingScaffold(loadFailed: Boolean, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier,
        topBar = {
            AppNestedTopBar(
                title = stringResource(Res.string.remotenodes_remote_nodes_room_info_title),
                onBack = onBack,
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            if (loadFailed) {
                PlaceholderPane(
                    title = stringResource(Res.string.chats_room_auth_not_found_title),
                    subtitle = stringResource(Res.string.localizable_error_room_server_session_not_found),
                    icon = ROOM_CONVERSATION_MISSING_ICON,
                )
            } else {
                CircularProgressIndicator()
            }
        }
    }
}

/** Missing-session empty state uses the room glyph, matching other conversation placeholders. */
internal val ROOM_CONVERSATION_MISSING_ICON get() = AppIcons.Forum

/** iOS `RoomAuthenticationSheet` not-found: `exclamationmark.triangle`. */
internal val ROOM_CONVERSATION_AUTH_MISSING_ICON get() = AppIcons.Warning

private suspend fun resolveRoomSession(
    sessionId: String,
    roomServerService: RoomServerService?,
    conversationListStore: ConversationListStore?,
    radioId: Uuid?,
): RemoteNodeSessionDto? {
    val id = Uuid.normalized(sessionId)
    val fromService = roomServerService?.fetchSession(id)
    if (fromService != null) return fromService
    return if (radioId == null) {
        null
    } else {
        conversationListStore?.fetchRooms(radioId)?.firstOrNull { it.id == id }
    }
}

private suspend fun resolveRoomContact(
    radioId: () -> Uuid?,
    contactService: () -> ContactService?,
    session: RemoteNodeSessionDto,
): ContactDto? {
    val radio = radioId() ?: return null
    return loadRoomAuthContact(
        fetchSession = { session },
        fetchContact = { current -> contactService()?.getContact(radio, current.publicKey) },
    )
}
