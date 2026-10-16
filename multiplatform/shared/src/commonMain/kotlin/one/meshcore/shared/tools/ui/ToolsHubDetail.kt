package one.meshcore.shared.tools.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import one.meshcore.shared.contact.collectContactUiReloads
import one.meshcore.shared.navigation.ToolSelection
import one.meshcore.shared.pathedit.toPathEditDiscoveredNode
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.tools_cli_not_connected
import one.meshcore.shared.resources.tools_cli_not_connected_description
import one.meshcore.shared.resources.tools_select_tool
import one.meshcore.shared.settings.RadioDeviceSnapshot
import one.meshcore.shared.tools.noiseFloorFetcher
import one.meshcore.shared.trace.SavedTracePathStore
import one.meshcore.shared.ui.AppNestedTopBar
import one.meshcore.shared.ui.PlaceholderPane
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ToolDetailPane(
    tool: ToolSelection,
    inputs: ToolsHubInputs,
    savedTraceStore: SavedTracePathStore,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isRadioReady = inputs.isRadioReady
    val isRadioConnected = inputs.isRadioConnected
    val deviceLocation = inputs.deviceLocation
    val runTracePath = inputs.runTracePath
    val rxLogStore = inputs.rxLogStore
    val radioId = inputs.radioId
    val toolsCliLocal = inputs.toolsCliLocal
    val discoveredNodeStore = inputs.discoveredNodeStore
    val contactService = inputs.contactService
    val contactListInvalidations = inputs.contactListInvalidations
    val sendNodeDiscover = inputs.sendNodeDiscover
    val meshEvents = inputs.meshEvents
    val selfPublicKey = inputs.selfPublicKey
    val radioSnapshot = inputs.radioSnapshot
    val recentHopsStore = inputs.recentHopsStore
    val preferences = inputs.preferences
    val mapDisplayPreferences = inputs.mapDisplayPreferences
    val isOffline = inputs.isOffline
    val locationService = inputs.locationService
    // Full-screen tool UIs that own their own scaffold/top bar.
    when {
        tool == ToolSelection.RX_LOG -> {
            val service = contactService()
            val radio = radioId()
            val nodeNames by produceState(
                emptyMap<String, String>(),
                service,
                radio,
                contactListInvalidations,
            ) {
                suspend fun reload() {
                    value = one.meshcore.shared.tools.buildRxLogNodeNames(
                        one.meshcore.shared.chat.pathdetail.loadContacts(service, radio),
                    )
                }
                reload()
                collectContactUiReloads(service, contactListInvalidations) { reload() }
            }
            RxLogScreen(
                store = rxLogStore,
                radioId = radioId,
                onBack = onBack,
                localPublicKeyPrefix = selfPublicKey(),
                nodeNames = nodeNames,
                isRadioReady = isRadioReady,
                modifier = modifier,
            )
        }
        tool == ToolSelection.NODE_DISCOVERY && sendNodeDiscover != null -> {
            val discover = sendNodeDiscover
            NodeDiscoveryScreen(
                sendDiscover = discover,
                workspace = inputs.nodeDiscoveryWorkspace,
                events = meshEvents,
                contactService = contactService,
                discoveredNodeStore = discoveredNodeStore,
                radioId = radioId,
                maxContacts = { radioSnapshot()?.maxContacts },
                isRadioReady = isRadioReady,
                isRadioConnected = isRadioConnected,
                onBack = onBack,
                modifier = modifier,
            )
        }
        tool == ToolSelection.TRACE_PATH -> {
            TracePathScreen(
                runTrace = runTracePath,
                workspace = inputs.tracePathWorkspace,
                deviceLocation = deviceLocation,
                savedPathStore = savedTraceStore,
                radioId = radioId,
                contactService = contactService,
                radioSnapshot = radioSnapshot,
                devicePathHashMode = { toolsCliLocal?.invoke()?.deviceCapabilities()?.pathHashMode },
                discoveredNodes = { radio ->
                    discoveredNodeStore()?.list(radio)?.map { it.toPathEditDiscoveredNode() } ?: emptyList()
                },
                recentHopsStore = recentHopsStore,
                preferences = preferences,
                mapDisplayPreferences = mapDisplayPreferences,
                isOffline = isOffline,
                locationService = locationService,
                onBack = onBack,
                modifier = modifier,
            )
        }
        else -> Scaffold(
            modifier = modifier,
            topBar = {
                AppNestedTopBar(
                    title = toolTitle(tool),
                    onBack = onBack,
                )
            },
        ) { innerPadding ->
            ToolPaneContent(
                tool = tool,
                inputs = inputs,
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
}

@Composable
private fun ToolPaneContent(tool: ToolSelection, inputs: ToolsHubInputs, modifier: Modifier = Modifier) {
    val isRadioReady = inputs.isRadioReady
    val radioId = inputs.radioId
    val toolsCliDeviceName = inputs.toolsCliDeviceName
    val toolsCliFetchContacts = inputs.toolsCliFetchContacts
    val toolsCliFetchChannels = inputs.toolsCliFetchChannels
    val toolsCliRemote = inputs.toolsCliRemote
    val toolsCliLocal = inputs.toolsCliLocal
    val contactService = inputs.contactService
    val fetchNoiseFloorReading = inputs.fetchNoiseFloorReading
    val meshEvents = inputs.meshEvents
    val elevationService = inputs.elevationService
    val shareText = inputs.shareText
    val radioSnapshot = inputs.radioSnapshot
    val preferences = inputs.preferences
    val mapDisplayPreferences = inputs.mapDisplayPreferences
    val isOffline = inputs.isOffline
    val locationService = inputs.locationService
    when {
        toolPaneShowsDisconnectedPlaceholder(tool, isRadioReady) -> PlaceholderPane(
            title = stringResource(Res.string.tools_cli_not_connected),
            subtitle = stringResource(Res.string.tools_cli_not_connected_description),
            icon = toolsDisconnectedIcon(tool),
            modifier = modifier,
        )
        tool == ToolSelection.LINE_OF_SIGHT -> LineOfSightScreen(
            workspace = inputs.lineOfSightWorkspace,
            elevationService = elevationService,
            contactService = contactService,
            radioId = radioId,
            shareText = shareText,
            // Seed the Fresnel math with the radio's own frequency instead of the catalog default.
            deviceFrequencyKHz = losSeedFrequencyKHz(radioSnapshot()),
            preferences = preferences,
            mapDisplayPreferences = mapDisplayPreferences,
            isOffline = isOffline,
            locationService = locationService,
            modifier = modifier,
        )
        tool == ToolSelection.CLI -> ToolsCliScreen(
            deviceName = toolsCliDeviceName(),
            fetchContacts = toolsCliFetchContacts ?: { null },
            fetchChannels = toolsCliFetchChannels ?: { null },
            remote = toolsCliRemote?.invoke(),
            local = toolsCliLocal?.invoke(),
            claimInitialKeyboardFocus = inputs.claimCliInitialKeyboardFocus,
            modifier = modifier,
        )
        tool == ToolSelection.NOISE_FLOOR -> NoiseFloorScreen(
            fetchReading = noiseFloorFetcher(isRadioReady, fetchNoiseFloorReading),
            sessionOwner = meshEvents(),
            radioId = radioId(),
            workspace = inputs.noiseFloorWorkspace,
            modifier = modifier,
        )
        else -> PlaceholderPane(
            title = toolTitle(tool),
            subtitle = stringResource(Res.string.tools_select_tool),
            modifier = modifier,
        )
    }
}

/**
 * The connected radio's frequency in kHz to seed the Line of Sight analysis with, or `null` when
 * disconnected or unreported, leaving the tool on its catalog default. Mirrors the one-shot seed the iOS
 * `LineOfSightViewModel.configure(deviceFrequencyKHz:)` takes from the connected device.
 */
internal fun losSeedFrequencyKHz(snapshot: RadioDeviceSnapshot?): Long? = snapshot?.frequencyKHz?.takeIf { it > 0 }

/**
 * Only the CLI uses the hub-level disconnected pane. Trace Path still shows the hop builder and
 * Noise Floor shows its own empty; RX Log and Node Discovery never reach this pane.
 */
internal fun toolPaneShowsDisconnectedPlaceholder(tool: ToolSelection, isRadioReady: Boolean): Boolean =
    !isRadioReady && tool == ToolSelection.CLI
