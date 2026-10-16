package one.meshcore.shared.remotenode.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.backup.NodeStatusSnapshotDto
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.chat.pathdetail.loadContacts
import one.meshcore.shared.chat.pathdetail.loadDiscoveredNodes
import one.meshcore.shared.contact.DiscoveredNodeRecord
import one.meshcore.shared.contact.collectContactUiReloads
import one.meshcore.shared.map.ui.LocalMapColorSchemePreference
import one.meshcore.shared.map.ui.LocalMapOffline
import one.meshcore.shared.map.ui.rememberMapDisplayChrome
import one.meshcore.shared.remotenode.TelemetryHistoryDemo
import one.meshcore.shared.remotenode.statusHistorySnapshots
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_unknown
import one.meshcore.shared.ui.PreferenceHubHorizontalPadding
import one.meshcore.shared.ui.preferenceHubScroll
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * The repeater status screen: counters with trend deltas, live request-telemetry sensor rows, owner
 * info, neighbours (resolved names, New / Not-seen, admin Discover Neighbors, per-neighbor SNR chart),
 * battery-curve editor, route path, optional SNR map sheet, and history overview when snapshot
 * history is supplied by the host.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalTime::class)
@Composable
internal fun NodeStatusScreen(
    viewModel: NodeStatusViewModel,
    session: RemoteNodeSessionDto,
    telemetrySnapshots: List<NodeStatusSnapshotDto> = emptyList(),
    nowEpochSeconds: Double = 0.0,
    /**
     * When true and [telemetrySnapshots] is empty, show deterministic demo history charts so the
     * offline / screenshot path can exercise Radio / Sensors / Neighbors sections.
     */
    useDemoTelemetryHistory: Boolean = false,
    modifier: Modifier = Modifier,
    surface: RepeaterSurfaceInputs = RepeaterSurfaceInputs(
        mapColorSchemePreference = LocalMapColorSchemePreference.current,
        isOffline = LocalMapOffline.current,
    ),
    /** Login-route contact for the path section; null hides the route block. */
    routePathContact: ContactDto? = null,
) {
    val contactService = surface.contactService
    val radioId = surface.radioId
    val discoveredNodeStore = surface.discoveredNodeStore
    val userLocation = surface.userLocation
    val mapColorSchemePreference = surface.mapColorSchemePreference
    val isOffline = surface.isOffline
    val locationService = surface.locationService
    val isRadioReady = surface.isRadioReady
    val preferences = surface.preferences
    val mapDisplayPreferences = surface.mapDisplayPreferences
    val deviceHashSize = surface.deviceHashSize
    val state by viewModel.state.collectAsStateWithLifecycle()
    val liveRouteContact = routePathContact?.let {
        rememberLiveRoutePathContact(it, session, contactService, enabled = state.routePathExpanded)
    }
    val mapChrome = rememberMapDisplayChrome(mapDisplayPreferences, preferences)
    DisposableEffect(viewModel) {
        onDispose { viewModel.pauseDiscoveryPolls() }
    }
    val keyDisplayByteCount = NeighborPrefixResolver.keyDisplayByteCount(deviceHashSize())
    val unknownNeighborLabel = stringResource(Res.string.remotenodes_remote_nodes_status_unknown)
    var showNeighborsMap by remember { mutableStateOf(false) }
    var showStatusHistory by remember { mutableStateOf(false) }
    var showTelemetryHistory by remember { mutableStateOf(false) }
    var chartNeighbor by remember { mutableStateOf<NeighborChartTarget?>(null) }
    val currentContactService = contactService()
    val currentDiscoveredNodeStore = discoveredNodeStore()
    val currentRadioId = radioId()
    var mapContacts by remember(currentContactService, currentRadioId) {
        mutableStateOf<List<ContactDto>>(emptyList())
    }
    var nameContacts by remember(currentContactService, currentRadioId) {
        mutableStateOf<List<ContactDto>>(emptyList())
    }
    var nameDiscovered by remember(currentDiscoveredNodeStore, currentRadioId) {
        mutableStateOf<List<DiscoveredNodeRecord>>(emptyList())
    }
    val scope = rememberCoroutineScope()
    val resolvedNow = if (nowEpochSeconds > 0.0) {
        nowEpochSeconds
    } else {
        Clock.System.now().epochSeconds.toDouble()
    }
    val historySnapshots = remember(telemetrySnapshots, useDemoTelemetryHistory, session.publicKey, resolvedNow) {
        statusHistorySnapshots(
            saved = telemetrySnapshots,
            useDemo = useDemoTelemetryHistory,
            demo = {
                TelemetryHistoryDemo.sampleSnapshots(
                    nodePublicKey = session.publicKey,
                    nowEpochSeconds = resolvedNow,
                )
            },
        )
    }

    val needsNames = state.neighborsExpanded ||
        state.routePathExpanded ||
        showNeighborsMap ||
        chartNeighbor != null ||
        showStatusHistory ||
        showTelemetryHistory
    LaunchedEffect(
        needsNames,
        state.neighborsLoaded,
        state.neighbors,
        currentContactService,
        currentDiscoveredNodeStore,
        currentRadioId,
    ) {
        if (!needsNames) return@LaunchedEffect
        suspend fun reload() {
            nameContacts = loadContacts(currentContactService, currentRadioId)
            nameDiscovered = loadDiscoveredNodes(currentRadioId) { id ->
                currentDiscoveredNodeStore?.list(id).orEmpty()
            }
        }
        reload()
        collectContactUiReloads(currentContactService) { reload() }
    }

    Column(modifier = modifier.preferenceHubScroll()) {
        NodeStatusHeader(
            session = session,
            modifier = Modifier.padding(horizontal = PreferenceHubHorizontalPadding),
        )
        StatusCountersSection(
            viewModel = viewModel,
            state = state,
            nowEpochSeconds = resolvedNow,
            isRadioReady = isRadioReady,
            onOpenHistory = { showStatusHistory = true },
        )
        // Live request-telemetry (iOS order: after Status, before Neighbors/Owner). History charts
        // stay as the inline TelemetryOverviewScreen below when the host supplies snapshots.
        LiveTelemetrySection(
            viewModel = viewModel,
            state = state,
            sessionName = session.name,
            mapColorSchemePreference = mapColorSchemePreference,
            isOffline = isOffline,
            locationService = locationService,
            userLocation = userLocation(),
            isRadioReady = isRadioReady,
            mapChrome = mapChrome,
            onOpenHistory = { showTelemetryHistory = true },
        )
        OwnerInfoSection(viewModel = viewModel, state = state, isRadioReady = isRadioReady)
        NeighborsSection(
            viewModel = viewModel,
            state = state,
            session = session,
            contacts = nameContacts,
            discovered = nameDiscovered,
            userLocation = userLocation(),
            keyDisplayByteCount = keyDisplayByteCount,
            onOpenMap = {
                scope.launch {
                    val radio = radioId()
                    mapContacts = loadContacts(contactService(), radio).ifEmpty { nameContacts }
                    showNeighborsMap = true
                }
            },
            onOpenNeighborChart = { neighbor, resolution ->
                chartNeighbor = NeighborChartTarget(
                    name = NeighborPrefixResolver.neighborRowDisplayName(
                        resolution,
                        unknownNeighborLabel,
                    ),
                    prefix = neighbor.publicKeyPrefix,
                )
            },
            isRadioReady = isRadioReady,
        )
        BatteryCurveSection(
            expanded = state.batteryCurveExpanded,
            onExpandedChange = viewModel::setBatteryCurveExpanded,
            isSavingOcv = state.isSavingOcv,
            ocvError = state.ocvError,
            selectedPreset = state.ocvPreset,
            values = state.ocvValues,
            isRadioReady = isRadioReady,
            onSelectPreset = viewModel::selectOcvPreset,
            onCommitValues = viewModel::commitOcvValues,
        )
        liveRouteContact?.let { contact ->
            NodeRoutePathSection(
                expanded = state.routePathExpanded,
                onExpandedChange = viewModel::setRoutePathExpanded,
                contact = contact,
                contacts = nameContacts,
                discovered = nameDiscovered,
                userLocation = userLocation(),
            )
        }
    }

    if (showNeighborsMap) {
        NeighborsMapSheet(
            session = session,
            neighbors = state.neighbors,
            contacts = mapContacts,
            discovered = nameDiscovered,
            userLocation = userLocation(),
            keyDisplayByteCount = keyDisplayByteCount,
            colorSchemePreference = mapColorSchemePreference,
            isOffline = isOffline,
            locationService = locationService,
            preferences = preferences,
            onDismiss = { showNeighborsMap = false },
            mapChrome = mapChrome,
        )
    }

    chartNeighbor?.let { target ->
        NeighborSnrChartSheet(
            name = target.name,
            prefix = target.prefix,
            snapshots = historySnapshots,
            nowEpochSeconds = resolvedNow,
            onDismiss = { chartNeighbor = null },
        )
    }

    NodeHistoryDialogs(
        showStatusHistory = showStatusHistory,
        showTelemetryHistory = showTelemetryHistory,
        onDismissStatus = { showStatusHistory = false },
        onDismissTelemetry = { showTelemetryHistory = false },
        snapshots = historySnapshots,
        nowEpochSeconds = resolvedNow,
        ocvArray = state.ocvValues,
        neighborNameResolver = { prefix ->
            NeighborPrefixResolver.resolve(prefix, nameContacts, nameDiscovered, userLocation()).displayName
        },
        mapColorSchemePreference = mapColorSchemePreference,
        mapStyle = mapChrome.style,
        isOffline = isOffline,
        locationService = locationService,
        userLocation = userLocation(),
        mapChrome = mapChrome,
    )
}
