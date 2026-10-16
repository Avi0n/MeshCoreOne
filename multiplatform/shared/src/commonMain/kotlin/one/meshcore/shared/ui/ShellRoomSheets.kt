package one.meshcore.shared.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.contact.contactType
import one.meshcore.shared.contact.getContact
import one.meshcore.shared.model.RemoteNodeRole
import one.meshcore.shared.navigation.navigateToRoom
import one.meshcore.shared.remotenode.fromContactType
import one.meshcore.shared.remotenode.ui.NodeAuthenticationSheet
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_common_ok
import one.meshcore.shared.resources.chats_error_load_messages_failed
import one.meshcore.shared.resources.chats_room_auth_not_found_description
import one.meshcore.shared.resources.chats_room_auth_not_found_title
import one.meshcore.shared.resources.localizable_error_room_server_send_failed
import one.meshcore.shared.resources.remotenodes_remote_nodes_room_info_title
import one.meshcore.shared.resources.remotenodes_remote_nodes_room_settings_title
import one.meshcore.shared.resources.remotenodes_remote_nodes_room_status_title
import one.meshcore.shared.room.fetchSession
import one.meshcore.shared.room.ui.RoomConversationScreen
import one.meshcore.shared.room.ui.RoomConversationViewModel
import one.meshcore.shared.room.ui.RoomManagementSurface
import one.meshcore.shared.room.ui.RoomStatusScreen
import one.meshcore.shared.room.ui.RoomStatusViewModel
import one.meshcore.shared.room.ui.loadRoomAuthContact
import one.meshcore.shared.room.ui.rememberRoomServerErrorText
import one.meshcore.shared.ui.formatTemplate
import one.meshcore.shared.ui.icons.AppIconButton
import one.meshcore.shared.ui.icons.AppIcons
import org.jetbrains.compose.resources.stringResource

/**
 * Legacy modal room host. Primary product path is [one.meshcore.shared.room.ui.RoomDetailPane] via
 * [one.meshcore.shared.navigation.ChatRoute.Room]; this remains for any residual [ShellSheet.RoomConversation]
 * presentation. Connect nests auth so system Back returns here rather than the list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RoomConversationSheetHost(
    contact: ContactDto,
    deps: ShellDependencies,
    onDismiss: () -> Unit,
    onLinkTap: (one.meshcore.shared.chat.text.LinkTarget) -> Unit = {},
) {
    val session = rememberRoomSession(contact, deps.roomServerService)
    val currentDeps by rememberUpdatedState(deps)
    var showSettings by remember { mutableStateOf(false) }
    var showNestedAuth by remember { mutableStateOf(false) }
    val openAuth = { showNestedAuth = true }
    val roomTitle = ((session as? ConnectedSessionLoad.Ready)?.session?.name ?: contact.name)
        .ifEmpty { stringResource(Res.string.remotenodes_remote_nodes_room_info_title) }
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            AppNestedTopBar(
                title = roomTitle,
                onBack = onDismiss,
                actions = {
                    if (session is ConnectedSessionLoad.Ready && deps.roomAdminService() != null) {
                        AppIconButton(
                            imageVector = AppIcons.Settings,
                            contentDescription = stringResource(
                                Res.string.remotenodes_remote_nodes_room_settings_title,
                            ),
                            onClick = { showSettings = true },
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        when (val resolved = session) {
            ConnectedSessionLoad.Loading -> LoadingCenter(Modifier.padding(innerPadding))
            ConnectedSessionLoad.Missing -> NotConnectedPlaceholder(
                onConnect = openAuth,
                modifier = Modifier.padding(innerPadding),
            )
            is ConnectedSessionLoad.Ready -> {
                val scope = rememberCoroutineScope()
                val publicKeyHash = resolved.session.publicKey.contentHashCode()
                val roomErrorText = rememberRoomServerErrorText()
                val loadFailedMessage = stringResource(Res.string.chats_error_load_messages_failed)
                val sendFailedMessage = stringResource(Res.string.localizable_error_room_server_send_failed)
                val currentRoomErrorText by rememberUpdatedState(roomErrorText)
                val currentLoadFailedMessage by rememberUpdatedState(loadFailedMessage)
                val currentSendFailedMessage by rememberUpdatedState(sendFailedMessage)
                val currentOnConversationsChanged by rememberUpdatedState(deps.onConversationsChanged)
                val radioConnection by deps.connectionState.collectAsStateWithLifecycle()
                val radioReady = radioConnection ==
                    one.meshcore.shared.connection.DeviceConnectionState.READY
                val viewModel = remember(
                    resolved.session.id,
                    resolved.session.radioID,
                    publicKeyHash,
                ) {
                    RoomConversationViewModel(
                        scope = scope,
                        session = resolved.session,
                        dependencies = RoomConversationViewModel.Dependencies(
                            roomServerService = { currentDeps.roomServerService() },
                            // The container is rebuilt on every connect; without this the sheet keeps
                            // collecting the dead one and the room's login quietly expires.
                            servicesChanged = deps.connectionState,
                            errorText = { error -> currentRoomErrorText(error) },
                            loadFailedMessage = { currentLoadFailedMessage },
                            sendFailedMessage = { currentSendFailedMessage },
                            onViewed = { currentOnConversationsChanged() },
                            isRadioReady = {
                                currentDeps.connectionState.value ==
                                    one.meshcore.shared.connection.DeviceConnectionState.READY
                            },
                        ),
                    )
                }
                LaunchedEffect(viewModel, deps.connectionState) {
                    viewModel.bindServicesChanged(deps.connectionState)
                }
                DisposableEffect(viewModel) {
                    onDispose(viewModel::dispose)
                }
                LaunchedEffect(viewModel, deps.appForeground) {
                    deps.appForeground.collect { viewModel.onEnterForeground() }
                }
                RoomConversationScreen(
                    viewModel = viewModel,
                    onConnect = openAuth,
                    onLinkTap = onLinkTap,
                    localNodeName = deps.localNodeName.value,
                    radioReady = radioReady,
                    modifier = Modifier.padding(innerPadding),
                )
                if (showSettings && deps.roomAdminService() != null) {
                    FullScreenOverlay(
                        onDismiss = { showSettings = false },
                        title = stringResource(Res.string.remotenodes_remote_nodes_room_settings_title),
                    ) { nestedPadding ->
                        RoomManagementSurface(
                            workspaces = deps.remoteAdminWorkspaces,
                            session = resolved.session,
                            roomAdminService = deps.roomAdminService,
                            locationService = deps.locationService,
                            isRadioReady = radioReady,
                            isRadioConnected = { deps.connectionState.value.isConnected },
                            nodeSnapshotService = deps.nodeSnapshotService,
                            contactService = deps.contactService,
                            radioId = deps.currentRadioId,
                            mapColorSchemePreference = deps.resolvedMapColorScheme(),
                            isOffline = deps.networkOfflineNow(),
                            userLocation = deps.bestAvailableUserLocation(),
                            mapDisplayPreferences = deps.mapDisplayPreferences,
                            modifier = Modifier.padding(nestedPadding),
                        )
                    }
                }
            }
        }
        if (showNestedAuth) {
            val role = RemoteNodeRole.fromContactType(contact.contactType) ?: RemoteNodeRole.ROOM_SERVER
            val authViewModel = rememberNodeAuthViewModel(contact, role, deps)
            FullScreenOverlay(onDismiss = { showNestedAuth = false }) {
                NodeAuthenticationSheet(
                    viewModel = authViewModel,
                    contact = contact,
                    role = role,
                    hideNodeDetails = true,
                    onSuccess = { authenticated ->
                        showNestedAuth = false
                        deps.coordinator.navigateToRoom(authenticated.id.value)
                        onDismiss()
                    },
                    onDismiss = { showNestedAuth = false },
                )
            }
        }
    }
}

/**
 * The disconnected-room pre-auth gate: resolves [sessionId] to its session and contact row, then shows
 * [NodeAuthenticationSheet] for the room-server role — mirroring iOS `RoomAuthenticationSheet`'s fetch-then-
 * present flow. A session or contact that can no longer be found (the room was removed mid-notification)
 * shows a not-found placeholder instead of silently failing.
 */
@Composable
internal fun RoomAuthSheetHost(
    sessionId: String,
    deps: ShellDependencies,
    onSuccess: (RemoteNodeSessionDto) -> Unit,
    onDismiss: () -> Unit,
) {
    val currentRoomService = deps.roomServerService()
    val currentContactService = deps.contactService()
    var lookup by remember(sessionId, currentRoomService, currentContactService) {
        mutableStateOf<RoomAuthLookup>(RoomAuthLookup.Loading)
    }
    LaunchedEffect(sessionId, currentRoomService, currentContactService) {
        val contact = loadRoomAuthContact(
            fetchSession = {
                currentRoomService?.fetchSession(one.meshcore.shared.model.Uuid(sessionId))
            },
            fetchContact = { session ->
                currentContactService?.getContact(session.radioID, session.publicKey)
            },
        )
        lookup = if (contact != null) RoomAuthLookup.Ready(contact) else RoomAuthLookup.Missing
    }
    when (val resolved = lookup) {
        RoomAuthLookup.Loading -> LoadingCenter()
        RoomAuthLookup.Missing -> Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(NOT_CONNECTED_SPACING, Alignment.CenterVertically),
            ) {
                Icon(
                    imageVector = ROOM_AUTH_NOT_FOUND_ICON,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(EMPTY_STATE_ICON_SIZE),
                )
                Text(
                    stringResource(Res.string.chats_room_auth_not_found_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(Res.string.chats_room_auth_not_found_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(Res.string.chats_common_ok))
            }
        }
        is RoomAuthLookup.Ready -> {
            val authViewModel = rememberNodeAuthViewModel(
                resolved.contact,
                RemoteNodeRole.ROOM_SERVER,
                deps,
            )
            NodeAuthenticationSheet(
                viewModel = authViewModel,
                contact = resolved.contact,
                role = RemoteNodeRole.ROOM_SERVER,
                onSuccess = onSuccess,
                onDismiss = onDismiss,
                hideNodeDetails = true,
            )
        }
    }
}

private sealed interface RoomAuthLookup {
    data object Loading : RoomAuthLookup
    data object Missing : RoomAuthLookup
    data class Ready(val contact: ContactDto) : RoomAuthLookup
}

@Composable
internal fun RoomStatusSheetHost(
    contact: ContactDto,
    deps: ShellDependencies,
    onDismiss: () -> Unit,
    onConnect: () -> Unit,
) {
    val session = rememberRoomSession(contact, deps.roomServerService)
    val currentDeps by rememberUpdatedState(deps)
    ConnectedSessionSheet(
        title = stringResource(Res.string.remotenodes_remote_nodes_room_status_title),
        session = session,
        onDismiss = onDismiss,
        onConnect = onConnect,
    ) { ready, innerPadding ->
        val scope = rememberCoroutineScope()
        val viewModel =
            deps.remoteAdminWorkspaces?.family(ready)?.roomStatus()
                ?: remember(ready.id, ready.radioID, ready.publicKey.contentHashCode()) {
                    RoomStatusViewModel(
                        scope = scope,
                        session = ready,
                        dependencies = RoomStatusViewModel.Dependencies(
                            roomAdminService = { currentDeps.roomAdminService() },
                            nodeSnapshotService = { currentDeps.nodeSnapshotService() },
                            contactService = { currentDeps.contactService() },
                            radioId = { currentDeps.currentRadioId() },
                            isRadioConnected = { currentDeps.connectionState.value.isConnected },
                        ),
                    )
                }
        DisposableEffect(viewModel) {
            onDispose { if (deps.remoteAdminWorkspaces == null) viewModel.close() }
        }
        RoomStatusScreen(
            viewModel = viewModel,
            session = ready,
            modifier = Modifier.padding(innerPadding),
            isRadioReady = deps.connectionState.collectAsStateWithLifecycle().value ==
                one.meshcore.shared.connection.DeviceConnectionState.READY,
            mapColorSchemePreference = deps.resolvedMapColorScheme(),
            isOffline = deps.networkOfflineNow(),
            locationService = deps.locationService,
            userLocation = deps.bestAvailableUserLocation(),
            mapDisplayPreferences = deps.mapDisplayPreferences,
        )
    }
}

@Composable
internal fun RoomSettingsSheetHost(
    contact: ContactDto,
    deps: ShellDependencies,
    onDismiss: () -> Unit,
    onConnect: () -> Unit,
) {
    val session = rememberRoomSession(contact, deps.roomServerService)
    ConnectedSessionSheet(
        title = stringResource(Res.string.remotenodes_remote_nodes_room_settings_title),
        session = session,
        onDismiss = onDismiss,
        onConnect = onConnect,
    ) { ready, innerPadding ->
        RoomManagementSurface(
            workspaces = deps.remoteAdminWorkspaces,
            session = ready,
            roomAdminService = deps.roomAdminService,
            locationService = deps.locationService,
            isRadioReady = deps.connectionState.collectAsStateWithLifecycle().value ==
                one.meshcore.shared.connection.DeviceConnectionState.READY,
            isRadioConnected = { deps.connectionState.value.isConnected },
            nodeSnapshotService = deps.nodeSnapshotService,
            contactService = deps.contactService,
            radioId = deps.currentRadioId,
            mapColorSchemePreference = deps.resolvedMapColorScheme(),
            isOffline = deps.networkOfflineNow(),
            userLocation = deps.bestAvailableUserLocation(),
            mapDisplayPreferences = deps.mapDisplayPreferences,
            modifier = Modifier.padding(innerPadding),
        )
    }
}

/** iOS `RoomAuthenticationSheet` not-found: `exclamationmark.triangle`. */
internal val ROOM_AUTH_NOT_FOUND_ICON get() = AppIcons.Warning
