package one.meshcore.shared.remotenode.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.chat.pathdetail.loadContacts
import one.meshcore.shared.chat.pathdetail.loadDiscoveredNodes
import one.meshcore.shared.contact.DiscoveredNodeRecord
import one.meshcore.shared.contact.collectContactUiReloads
import one.meshcore.shared.map.ui.LocalMapColorSchemePreference
import one.meshcore.shared.map.ui.LocalMapOffline
import one.meshcore.shared.remotenode.NodeSnapshotService
import one.meshcore.shared.remotenode.RepeaterAdminService
import one.meshcore.shared.remotenode.isAdmin

/**
 * The repeater admin surface: settings, CLI, and telemetry, in the iOS `RepeaterSettingsView` segment
 * order. Tabs show only when [session] is an admin login; a guest sees the settings form alone.
 */
@Composable
internal fun NodeManagementSurface(
    session: RemoteNodeSessionDto,
    routePathContact: ContactDto,
    repeaterAdminService: () -> RepeaterAdminService?,
    nodeSnapshotService: () -> NodeSnapshotService?,
    surface: RepeaterSurfaceInputs = RepeaterSurfaceInputs(
        mapColorSchemePreference = LocalMapColorSchemePreference.current,
        isOffline = LocalMapOffline.current,
    ),
    contactListInvalidations: Flow<Unit> = emptyFlow(),
    modifier: Modifier = Modifier,
    useDemoTelemetryHistory: Boolean = false,
    isRadioConnected: () -> Boolean = { surface.isRadioReady },
    workspaces: RemoteAdminWorkspaces? = null,
) {
    val contactService = surface.contactService
    val discoveredNodeStore = surface.discoveredNodeStore
    val radioId = surface.radioId
    val userLocation = surface.userLocation
    val locationService = surface.locationService
    val isRadioReady = surface.isRadioReady
    val scope = rememberCoroutineScope()
    val sessionId = session.id
    val publicKeyHash = session.publicKey.contentHashCode()
    val currentRepeaterAdminService by rememberUpdatedState(repeaterAdminService)
    val currentNodeSnapshotService by rememberUpdatedState(nodeSnapshotService)
    val currentSurface by rememberUpdatedState(surface)
    val currentIsRadioConnected by rememberUpdatedState(isRadioConnected)
    val currentAdminService = repeaterAdminService()
    val currentSnapshotService = nodeSnapshotService()
    val family = workspaces?.family(session)
    LaunchedEffect(workspaces, currentAdminService) { workspaces?.reattach() }
    var tab by remember(sessionId, session.radioID) { mutableStateOf(AdminManagementTab.SETTINGS) }
    val settingsViewModel = rememberNodeSettingsViewModel(
        visible = tab == AdminManagementTab.SETTINGS,
        session = session,
        admin = currentAdminService,
        family = family,
    ) {
        NodeSettingsViewModel(
            scope = scope,
            sessionId = sessionId,
            dependencies = NodeSettingsViewModel.Dependencies(
                repeaterAdminService = { currentRepeaterAdminService() },
            ),
        )
    }
    val settingsState = settingsViewModel?.state?.collectAsStateWithLifecycle()?.value
    val routeExpanded = settingsState?.routePathExpanded == true
    val liveRouteContact = rememberLiveRoutePathContact(
        seed = routePathContact,
        session = session,
        contactService = contactService,
        invalidations = contactListInvalidations,
        enabled = routeExpanded,
    )
    val currentContactService = contactService()
    val currentDiscoveredNodeStore = discoveredNodeStore()
    val currentRadioId = radioId()
    var routeContacts by remember(sessionId, session.radioID, currentContactService, currentRadioId) {
        mutableStateOf<List<ContactDto>>(emptyList())
    }
    var routeDiscovered by remember(
        sessionId,
        session.radioID,
        currentDiscoveredNodeStore,
        currentRadioId,
    ) {
        mutableStateOf<List<DiscoveredNodeRecord>>(emptyList())
    }
    LaunchedEffect(
        routeExpanded,
        sessionId,
        session.radioID,
        currentContactService,
        currentDiscoveredNodeStore,
        currentRadioId,
        contactListInvalidations,
    ) {
        if (!routeExpanded) return@LaunchedEffect
        suspend fun reload() {
            routeContacts = loadContacts(currentContactService, currentRadioId)
            routeDiscovered = loadDiscoveredNodes(currentRadioId) { id ->
                currentDiscoveredNodeStore?.list(id).orEmpty()
            }
        }
        reload()
        collectContactUiReloads(currentContactService, contactListInvalidations) { reload() }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        if (session.isAdmin) {
            AdminManagementTabRow(selected = tab, onSelect = { tab = it })
        }
        when (tab) {
            AdminManagementTab.SETTINGS -> {
                val settingsViewModel = requireNotNull(settingsViewModel)
                DisposableEffect(settingsViewModel) {
                    val mount = settingsViewModel.settingsShown()
                    onDispose {
                        settingsViewModel.settingsHidden(mount)
                        if (workspaces == null) settingsViewModel.cleanup()
                    }
                }
                NodeSettingsScreen(
                    viewModel = settingsViewModel,
                    routePathContact = liveRouteContact,
                    routePathContacts = routeContacts,
                    routePathDiscovered = routeDiscovered,
                    userLocation = userLocation(),
                    locationService = locationService,
                    isRadioReady = isRadioReady,
                )
            }
            AdminManagementTab.CLI -> {
                val cliMessages = rememberNodeCliMessages()
                val currentCliMessages by rememberUpdatedState(cliMessages)
                val cliViewModel = family?.cli(cliMessages) ?: remember(sessionId, session.radioID, session.name) {
                    NodeCliViewModel(
                        scope = scope,
                        sessionName = session.name,
                        messages = { currentCliMessages },
                        dependencies = NodeCliViewModel.Dependencies(
                            repeaterAdminService = { currentRepeaterAdminService() },
                            sessionId = { sessionId },
                        ),
                    )
                }
                DisposableEffect(cliViewModel) {
                    onDispose { if (workspaces == null) cliViewModel.close() }
                }
                LaunchedEffect(cliViewModel, cliMessages) { cliViewModel.refreshLocalizedCopy() }
                NodeCliScreen(viewModel = cliViewModel)
            }
            AdminManagementTab.TELEMETRY -> {
                val statusViewModel = family?.repeaterStatus() ?: remember(sessionId, session.radioID, publicKeyHash) {
                    NodeStatusViewModel(
                        scope = scope,
                        session = session,
                        dependencies = NodeStatusViewModel.Dependencies(
                            repeaterAdminService = { currentRepeaterAdminService() },
                            nodeSnapshotService = { currentNodeSnapshotService() },
                            contactService = { currentSurface.contactService() },
                            radioId = { currentSurface.radioId() },
                            isRadioConnected = { currentIsRadioConnected() },
                        ),
                    )
                }
                DisposableEffect(statusViewModel) {
                    onDispose { if (workspaces == null) statusViewModel.close() }
                }
                val statusState by statusViewModel.state.collectAsStateWithLifecycle()
                var liveSnapshots by remember(
                    sessionId,
                    session.radioID,
                    publicKeyHash,
                    currentSnapshotService,
                ) {
                    mutableStateOf<List<one.meshcore.shared.backup.NodeStatusSnapshotDto>>(emptyList())
                }
                LaunchedEffect(
                    sessionId,
                    publicKeyHash,
                    currentSnapshotService,
                    statusState.statusExpanded,
                    statusState.telemetryExpanded,
                    statusState.neighborsExpanded,
                    statusState.snapshotVersion,
                    statusState.statusLoaded,
                    statusState.neighborsLoaded,
                ) {
                    if (!statusState.needsHistory) {
                        return@LaunchedEffect
                    }
                    liveSnapshots = currentSnapshotService?.fetchSnapshots(session.publicKey).orEmpty()
                }
                NodeStatusScreen(
                    viewModel = statusViewModel,
                    session = session,
                    telemetrySnapshots = liveSnapshots,
                    useDemoTelemetryHistory = useDemoTelemetryHistory && liveSnapshots.isEmpty(),
                    surface = surface,
                    routePathContact = routePathContact,
                )
            }
        }
    }
}

@Composable
private fun rememberNodeSettingsViewModel(
    visible: Boolean,
    session: RemoteNodeSessionDto,
    admin: RepeaterAdminService?,
    family: RemoteAdminFamily?,
    create: () -> NodeSettingsViewModel,
): NodeSettingsViewModel? {
    if (!visible) return null
    return family?.repeaterSettings() ?: remember(session.id, session.radioID, admin, calculation = create)
}
