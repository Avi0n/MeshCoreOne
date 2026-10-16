package one.meshcore.shared.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.contact.contactType
import one.meshcore.shared.map.ui.rememberMapDisplayChrome
import one.meshcore.shared.model.RemoteNodeRole
import one.meshcore.shared.navigation.NavigationCoordinator
import one.meshcore.shared.navigation.navigateToRoom
import one.meshcore.shared.remotenode.NodeAuthPurpose
import one.meshcore.shared.remotenode.PostLoginDestination
import one.meshcore.shared.remotenode.TelemetryHistoryDemo
import one.meshcore.shared.remotenode.fromContactType
import one.meshcore.shared.remotenode.isAdmin
import one.meshcore.shared.remotenode.postLoginDestination
import one.meshcore.shared.remotenode.ui.NodeAuthViewModel
import one.meshcore.shared.remotenode.ui.NodeAuthenticationSheet
import one.meshcore.shared.remotenode.ui.NodeCliScreen
import one.meshcore.shared.remotenode.ui.NodeCliViewModel
import one.meshcore.shared.remotenode.ui.NodeManagementSurface
import one.meshcore.shared.remotenode.ui.NodeSettingsScreen
import one.meshcore.shared.remotenode.ui.NodeStatusScreen
import one.meshcore.shared.remotenode.ui.NodeStatusViewModel
import one.meshcore.shared.remotenode.ui.TelemetryOverviewScreen
import one.meshcore.shared.remotenode.ui.rememberLiveRoutePathContact
import one.meshcore.shared.remotenode.ui.rememberNodeAuthErrorText
import one.meshcore.shared.remotenode.ui.rememberNodeCliMessages
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_detail_telemetry_access
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_title
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_title
import one.meshcore.shared.resources.tools_cli
import one.meshcore.shared.ui.formatTemplate
import org.jetbrains.compose.resources.stringResource
import kotlin.time.ExperimentalTime

/**
 * The auth sheet for [contact] at [purpose], or null when the contact is not a repeater or room.
 * Status, CLI, and settings all resolve the role this way; a room sheet still uses a fixed room-server role.
 */
internal fun contactAuthSheet(contact: ContactDto, purpose: NodeAuthPurpose): ShellSheet.NodeAuth? {
    val role = RemoteNodeRole.fromContactType(contact.contactType) ?: return null
    return ShellSheet.NodeAuth(contact, role, purpose)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShellNodeSheets(
    sheet: ShellSheet,
    deps: ShellDependencies,
    onSheetChange: (ShellSheet?) -> Unit,
    dismiss: () -> Unit,
    coordinator: NavigationCoordinator,
) {
    when (sheet) {
        is ShellSheet.NodeAuth -> NodeAuthSheet(
            sheet = sheet,
            deps = deps,
            onSheetChange = onSheetChange,
            dismiss = dismiss,
            coordinator = coordinator,
        )

        is ShellSheet.NodeStatus -> NodeStatusSheetHost(
            contact = sheet.contact,
            deps = deps,
            onDismiss = dismiss,
            onConnect = {
                contactAuthSheet(sheet.contact, NodeAuthPurpose.TELEMETRY)?.let(onSheetChange)
            },
        )

        is ShellSheet.TelemetryHistory -> TelemetryHistorySheetHost(
            contact = sheet.contact,
            deps = deps,
            onDismiss = dismiss,
        )

        is ShellSheet.NodeTelemetry -> NodeTelemetrySheetHost(
            contact = sheet.contact,
            deps = deps,
            onDismiss = dismiss,
        )

        is ShellSheet.NodeCli -> NodeCliSheetHost(
            contact = sheet.contact,
            deps = deps,
            onDismiss = dismiss,
            onConnect = {
                contactAuthSheet(sheet.contact, NodeAuthPurpose.MANAGE)?.let(onSheetChange)
            },
        )

        is ShellSheet.NodeSettings -> NodeSettingsSheetHost(
            contact = sheet.contact,
            deps = deps,
            onDismiss = dismiss,
            onConnect = {
                contactAuthSheet(sheet.contact, NodeAuthPurpose.MANAGE)?.let(onSheetChange)
            },
        )

        is ShellSheet.RoomStatus -> RoomStatusSheetHost(
            contact = sheet.contact,
            deps = deps,
            onDismiss = dismiss,
            onConnect = {
                onSheetChange(
                    ShellSheet.NodeAuth(
                        sheet.contact,
                        RemoteNodeRole.ROOM_SERVER,
                        NodeAuthPurpose.TELEMETRY,
                    ),
                )
            },
        )

        is ShellSheet.RoomSettings -> RoomSettingsSheetHost(
            contact = sheet.contact,
            deps = deps,
            onDismiss = dismiss,
            onConnect = {
                onSheetChange(
                    ShellSheet.NodeAuth(
                        sheet.contact,
                        RemoteNodeRole.ROOM_SERVER,
                        NodeAuthPurpose.MANAGE,
                    ),
                )
            },
        )
        else -> Unit
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NodeAuthSheet(
    sheet: ShellSheet.NodeAuth,
    deps: ShellDependencies,
    onSheetChange: (ShellSheet?) -> Unit,
    dismiss: () -> Unit,
    coordinator: NavigationCoordinator,
) {
    val authViewModel = rememberNodeAuthViewModel(sheet.contact, sheet.role, deps)
    NodeAuthenticationSheet(
        viewModel = authViewModel,
        contact = sheet.contact,
        role = sheet.role,
        customTitle = if (sheet.purpose == NodeAuthPurpose.TELEMETRY) {
            stringResource(Res.string.contacts_detail_telemetry_access)
        } else {
            null
        },
        onSuccess = { authenticated ->
            when (postLoginDestination(sheet.role, authenticated.isAdmin, sheet.purpose)) {
                PostLoginDestination.ROOM -> {
                    coordinator.navigateToRoom(authenticated.id.value)
                    onSheetChange(null)
                }
                PostLoginDestination.SETTINGS ->
                    onSheetChange(
                        if (sheet.role == RemoteNodeRole.ROOM_SERVER) {
                            ShellSheet.RoomSettings(sheet.contact)
                        } else {
                            ShellSheet.NodeSettings(sheet.contact)
                        },
                    )
                PostLoginDestination.STATUS ->
                    onSheetChange(
                        if (sheet.role == RemoteNodeRole.ROOM_SERVER) {
                            ShellSheet.RoomStatus(sheet.contact)
                        } else {
                            ShellSheet.NodeStatus(sheet.contact)
                        },
                    )
            }
        },
        onDismiss = dismiss,
    )
}

/**
 * Resolves a connected repeater session for [contact] and hosts [NodeStatusScreen], or a not-connected
 * placeholder with a Connect affordance.
 */
@OptIn(ExperimentalTime::class)
@Composable
internal fun NodeStatusSheetHost(
    contact: ContactDto,
    deps: ShellDependencies,
    onDismiss: () -> Unit,
    onConnect: () -> Unit,
) {
    val session = rememberRepeaterSession(contact, deps.repeaterAdminService)
    val demoChrome = rememberMapDisplayChrome(deps.mapDisplayPreferences)
    val currentDeps by rememberUpdatedState(deps)
    ConnectedSessionSheet(
        title = stringResource(Res.string.remotenodes_remote_nodes_status_title),
        session = session,
        onDismiss = onDismiss,
        onConnect = onConnect,
        missing = { innerPadding ->
            val demoHistory = deps.demoModeStore?.isEnabled?.value == true
            if (demoHistory) {
                // Offline demo: charts without a live session so screenshots / review don't need radio.
                TelemetryOverviewScreen(
                    snapshots = TelemetryHistoryDemo.sampleSnapshots(
                        nodePublicKey = contact.publicKey,
                        nowEpochSeconds = kotlin.time.Clock.System.now().epochSeconds.toDouble(),
                    ),
                    nowEpochSeconds = kotlin.time.Clock.System.now().epochSeconds.toDouble(),
                    modifier = Modifier.padding(innerPadding),
                    mapColorSchemePreference = deps.resolvedMapColorScheme(),
                    isOffline = deps.networkOfflineNow(),
                    locationService = deps.locationService,
                    userLocation = deps.bestAvailableUserLocation(),
                    mapChrome = demoChrome,
                    mapStyle = demoChrome.style,
                )
            } else {
                NotConnectedPlaceholder(
                    onConnect = onConnect,
                    modifier = Modifier.padding(innerPadding),
                )
            }
        },
    ) { ready, innerPadding ->
        val demoHistory = deps.demoModeStore?.isEnabled?.value == true
        val scope = rememberCoroutineScope()
        val publicKeyHash = ready.publicKey.contentHashCode()
        val currentSnapshotService = deps.nodeSnapshotService()
        val viewModel =
            deps.remoteAdminWorkspaces?.family(ready)?.repeaterStatus()
                ?: remember(ready.id, ready.radioID, publicKeyHash) {
                    NodeStatusViewModel(
                        scope = scope,
                        session = ready,
                        dependencies = NodeStatusViewModel.Dependencies(
                            repeaterAdminService = { currentDeps.repeaterAdminService() },
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
        val statusState by viewModel.state.collectAsStateWithLifecycle()
        var liveSnapshots by remember(ready.id, ready.radioID, publicKeyHash, currentSnapshotService) {
            mutableStateOf<List<one.meshcore.shared.backup.NodeStatusSnapshotDto>>(emptyList())
        }
        LaunchedEffect(
            ready.id,
            publicKeyHash,
            currentSnapshotService,
            statusState.statusLoaded,
            statusState.neighborsLoaded,
        ) {
            liveSnapshots = currentSnapshotService?.fetchSnapshots(ready.publicKey).orEmpty()
        }
        val surface = repeaterSurfaceInputs(deps)
        NodeStatusScreen(
            viewModel = viewModel,
            session = ready,
            telemetrySnapshots = liveSnapshots,
            useDemoTelemetryHistory = demoHistory && liveSnapshots.isEmpty(),
            modifier = Modifier.padding(innerPadding),
            surface = surface,
            routePathContact = rememberLiveRoutePathContact(
                seed = contact,
                session = ready,
                contactService = deps.contactService,
                invalidations = deps.contactListInvalidations,
            ),
        )
    }
}

/**
 * Resolves a connected repeater session for [contact] and hosts [NodeSettingsScreen], or a not-connected
 * placeholder with a Connect affordance.
 */
@Composable
internal fun NodeSettingsSheetHost(
    contact: ContactDto,
    deps: ShellDependencies,
    onDismiss: () -> Unit,
    onConnect: () -> Unit,
) {
    val session = rememberRepeaterSession(contact, deps.repeaterAdminService)
    ConnectedSessionSheet(
        title = stringResource(Res.string.remotenodes_remote_nodes_settings_title),
        session = session,
        onDismiss = onDismiss,
        onConnect = onConnect,
    ) { ready, innerPadding ->
        NodeManagementSurface(
            session = ready,
            workspaces = deps.remoteAdminWorkspaces,
            routePathContact = contact,
            contactListInvalidations = deps.contactListInvalidations,
            repeaterAdminService = deps.repeaterAdminService,
            nodeSnapshotService = deps.nodeSnapshotService,
            surface = repeaterSurfaceInputs(deps),
            useDemoTelemetryHistory = deps.demoModeStore?.isEnabled?.value == true,
            isRadioConnected = { deps.connectionState.value.isConnected },
            modifier = Modifier.padding(innerPadding),
        )
    }
}

/**
 * Resolves a connected repeater session for [contact] and hosts [NodeCliScreen], or a not-connected
 * placeholder with a Connect affordance.
 */
@Composable
internal fun NodeCliSheetHost(
    contact: ContactDto,
    deps: ShellDependencies,
    onDismiss: () -> Unit,
    onConnect: () -> Unit,
) {
    val session = rememberRepeaterSession(contact, deps.repeaterAdminService)
    val messages = rememberNodeCliMessages()
    val currentMessages by rememberUpdatedState(messages)
    val currentDeps by rememberUpdatedState(deps)
    ConnectedSessionSheet(
        title = stringResource(Res.string.tools_cli),
        session = session,
        onDismiss = onDismiss,
        onConnect = onConnect,
    ) { ready, innerPadding ->
        val scope = rememberCoroutineScope()
        val viewModel =
            deps.remoteAdminWorkspaces?.cli(ready, messages) ?: remember(ready.id, ready.radioID, ready.name) {
                NodeCliViewModel(
                    scope = scope,
                    sessionName = ready.name,
                    messages = { currentMessages },
                    dependencies = NodeCliViewModel.Dependencies(
                        repeaterAdminService = { currentDeps.repeaterAdminService() },
                        sessionId = { ready.id },
                    ),
                )
            }
        DisposableEffect(viewModel) {
            onDispose { if (deps.remoteAdminWorkspaces == null) viewModel.close() }
        }
        LaunchedEffect(viewModel, messages) { viewModel.refreshLocalizedCopy() }
        NodeCliScreen(viewModel = viewModel, modifier = Modifier.padding(innerPadding))
    }
}

@Composable
internal fun rememberNodeAuthViewModel(
    contact: ContactDto,
    role: RemoteNodeRole,
    deps: ShellDependencies,
): NodeAuthViewModel {
    val scope = rememberCoroutineScope()
    val errorText = rememberNodeAuthErrorText()
    val currentErrorText by rememberUpdatedState(errorText)
    val currentDeps by rememberUpdatedState(deps)
    val viewModel = remember(contact.id, contact.radioID, contact.publicKey.contentHashCode(), role) {
        NodeAuthViewModel(
            scope = scope,
            contact = contact,
            role = role,
            dependencies = NodeAuthViewModel.Dependencies(
                repeaterAdminService = { currentDeps.repeaterAdminService() },
                roomServerService = { currentDeps.roomServerService() },
                passwordStore = { currentDeps.nodePasswordStore() },
                contactService = { currentDeps.contactService() },
                radioId = { currentDeps.currentRadioId() },
                isRadioConnected = { currentDeps.connectionState.value.isConnected },
                discoveredNodeStore = { currentDeps.discoveredNodeStore() },
                errorText = { error -> currentErrorText(error) },
                userLocation = { currentDeps.bestAvailableUserLocation() },
            ),
        )
    }
    DisposableEffect(viewModel) {
        onDispose(viewModel::close)
    }
    return viewModel
}
