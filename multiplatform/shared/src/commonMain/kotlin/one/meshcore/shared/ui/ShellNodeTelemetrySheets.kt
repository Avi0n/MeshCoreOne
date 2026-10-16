package one.meshcore.shared.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.battery.BatteryOcv
import one.meshcore.shared.chat.pathdetail.loadContacts
import one.meshcore.shared.chat.pathdetail.loadDiscoveredNodes
import one.meshcore.shared.contact.ContactDetailLogic
import one.meshcore.shared.contact.DiscoveredNodeRecord
import one.meshcore.shared.contact.contactType
import one.meshcore.shared.contact.displayName
import one.meshcore.shared.map.ui.rememberMapDisplayChrome
import one.meshcore.shared.remotenode.PUBLIC_KEY_PREFIX_LENGTH
import one.meshcore.shared.remotenode.TelemetryHistoryDemo
import one.meshcore.shared.remotenode.addressingPrefix
import one.meshcore.shared.remotenode.ui.NeighborPrefixResolver
import one.meshcore.shared.remotenode.ui.NodeTelemetryScreen
import one.meshcore.shared.remotenode.ui.NodeTelemetryViewModel
import one.meshcore.shared.remotenode.ui.TelemetryOverviewScreen
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_detail_saved_history
import one.meshcore.shared.resources.contacts_detail_telemetry
import one.meshcore.shared.resources.remotenodes_remote_nodes_history_title
import one.meshcore.shared.resources.tools_cli_not_connected
import org.jetbrains.compose.resources.stringResource
import kotlin.time.ExperimentalTime

/**
 * Live request-telemetry for a chat [contact] (iOS `NodeTelemetryView`). Requires
 * [ShellDependencies.requestTelemetry]; falls back to a disconnected placeholder when null.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalTime::class)
@Composable
internal fun NodeTelemetrySheetHost(contact: ContactDto, deps: ShellDependencies, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val request = deps.requestTelemetry
    val currentRequest by rememberUpdatedState(request)
    val currentDeps by rememberUpdatedState(deps)
    var showHistory by remember { mutableStateOf(false) }
    if (showHistory) {
        val nestedBackState = rememberNavigationEventState(NavigationEventInfo.None)
        NavigationBackHandler(
            state = nestedBackState,
            isBackEnabled = true,
            onBackCompleted = { showHistory = false },
        )
    }
    Scaffold(
        topBar = {
            AppNestedTopBar(
                title = stringResource(
                    if (showHistory) {
                        Res.string.remotenodes_remote_nodes_history_title
                    } else {
                        Res.string.contacts_detail_telemetry
                    },
                ),
                onBack = if (showHistory) {
                    { showHistory = false }
                } else {
                    onDismiss
                },
            )
        },
    ) { innerPadding ->
        if (request == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(Res.string.tools_cli_not_connected),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            val viewModel = remember(contact.id, contact.radioID, contact.publicKey.contentHashCode()) {
                val prefix = contact.publicKey.addressingPrefix()
                NodeTelemetryViewModel(
                    scope = scope,
                    publicKey = contact.publicKey,
                    dependencies = NodeTelemetryViewModel.Dependencies(
                        requestTelemetry = { currentRequest },
                        nodeSnapshotService = { currentDeps.nodeSnapshotService() },
                        expectedPrefix = prefix.takeIf { it.size == PUBLIC_KEY_PREFIX_LENGTH },
                        ocvValues = BatteryOcv.activeArray(contact),
                    ),
                )
            }
            DisposableEffect(viewModel) {
                onDispose(viewModel::close)
            }
            if (showHistory) {
                ContactTelemetryHistoryBody(
                    contact = contact,
                    deps = deps,
                    modifier = Modifier.padding(innerPadding),
                )
            } else {
                NodeTelemetryScreen(
                    viewModel = viewModel,
                    onOpenHistory = { showHistory = true },
                    isRadioReady = deps.connectionState.collectAsStateWithLifecycle().value ==
                        one.meshcore.shared.connection.DeviceConnectionState.READY,
                    contactName = contact.displayName,
                    mapColorSchemePreference = deps.resolvedMapColorScheme(),
                    isOffline = deps.networkOfflineNow(),
                    locationService = deps.locationService,
                    userLocation = deps.bestAvailableUserLocation(),
                    mapDisplayPreferences = deps.mapDisplayPreferences,
                    preferences = deps.pathEditPreferences,
                    modifier = Modifier.padding(innerPadding),
                )
            }
        }
    }
}

/**
 * Room-backed telemetry history for [contact], with demo samples when the store is empty and demo
 * mode is on. Mirrors iOS `TelemetryHistoryOverviewView` entry from contact detail.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalTime::class)
@Composable
internal fun TelemetryHistorySheetHost(contact: ContactDto, deps: ShellDependencies, onDismiss: () -> Unit) {
    Scaffold(
        topBar = {
            AppNestedTopBar(
                title = stringResource(Res.string.contacts_detail_saved_history),
                onBack = onDismiss,
            )
        },
    ) { innerPadding ->
        ContactTelemetryHistoryBody(
            contact = contact,
            deps = deps,
            modifier = Modifier.padding(innerPadding),
        )
    }
}

@OptIn(ExperimentalTime::class)
@Composable
private fun ContactTelemetryHistoryBody(contact: ContactDto, deps: ShellDependencies, modifier: Modifier = Modifier) {
    val publicKeyHash = contact.publicKey.contentHashCode()
    val currentSnapshotService = deps.nodeSnapshotService()
    val currentContactService = deps.contactService()
    val currentDiscoveredNodeStore = deps.discoveredNodeStore()
    var snapshots by remember(contact.id, publicKeyHash, currentSnapshotService) {
        mutableStateOf<List<one.meshcore.shared.backup.NodeStatusSnapshotDto>>(emptyList())
    }
    val nowEpochSeconds = remember {
        kotlin.time.Clock.System.now().epochSeconds.toDouble()
    }
    var contacts by remember(contact.radioID, currentContactService) { mutableStateOf<List<ContactDto>>(emptyList()) }
    var discovered by remember(contact.radioID, currentDiscoveredNodeStore) {
        mutableStateOf<List<DiscoveredNodeRecord>>(emptyList())
    }
    val mapChrome = rememberMapDisplayChrome(deps.mapDisplayPreferences)
    LaunchedEffect(
        contact.id,
        publicKeyHash,
        currentSnapshotService,
        currentContactService,
        currentDiscoveredNodeStore,
    ) {
        snapshots = telemetryHistorySnapshots(
            fetch = { currentSnapshotService?.fetchSnapshots(contact.publicKey) },
            demoEnabled = { deps.demoModeStore?.isEnabled?.value == true },
            demoSnapshots = {
                TelemetryHistoryDemo.sampleSnapshots(
                    nodePublicKey = contact.publicKey,
                    nowEpochSeconds = nowEpochSeconds,
                )
            },
        )
        contacts = loadContacts(currentContactService, contact.radioID)
        discovered = loadDiscoveredNodes(contact.radioID) { id ->
            currentDiscoveredNodeStore?.list(id).orEmpty()
        }
    }
    TelemetryOverviewScreen(
        snapshots = snapshots,
        nowEpochSeconds = nowEpochSeconds,
        modifier = modifier,
        ocvArray = BatteryOcv.activeArray(contact),
        neighborNameResolver = { prefix ->
            NeighborPrefixResolver.resolve(prefix, contacts, discovered).displayName
        },
        mapColorSchemePreference = deps.resolvedMapColorScheme(),
        isOffline = deps.networkOfflineNow(),
        locationService = deps.locationService,
        userLocation = deps.bestAvailableUserLocation(),
        mapChrome = mapChrome,
        mapStyle = mapChrome.style,
        showNeighbors = ContactDetailLogic.showTelemetryHistoryNeighbors(contact.contactType),
    )
}
