package one.meshcore.shared.tools.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.Flow
import one.meshcore.shared.chat.ui.ThreadErrorDialog
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.DiscoveredNodeStore
import one.meshcore.shared.contact.ui.message
import one.meshcore.shared.contact.ui.rememberContactServiceErrorText
import one.meshcore.shared.meshcore.MeshEvent
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.tools_node_discovery
import one.meshcore.shared.resources.tools_node_discovery_error_title
import one.meshcore.shared.resources.tools_node_discovery_not_connected_description
import one.meshcore.shared.resources.tools_node_discovery_scan_button
import one.meshcore.shared.resources.tools_node_discovery_sort_menu
import one.meshcore.shared.resources.tools_node_discovery_sort_menu_hint
import one.meshcore.shared.resources.tools_node_discovery_sort_name
import one.meshcore.shared.resources.tools_node_discovery_sort_signal
import one.meshcore.shared.resources.tools_node_discovery_stop_button
import one.meshcore.shared.resources.tools_node_discovery_unknown_node
import one.meshcore.shared.resources.tools_rx_log_not_connected
import one.meshcore.shared.tools.NodeDiscoveryFilter
import one.meshcore.shared.tools.NodeDiscoverySortOrder
import one.meshcore.shared.tools.NodeDiscoveryViewModel
import one.meshcore.shared.tools.NodeDiscoveryWorkspace
import one.meshcore.shared.ui.AppNestedTopBar
import one.meshcore.shared.ui.formattedStringResource
import one.meshcore.shared.ui.icons.AppIconButton
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.menu.McDropdownMenu
import one.meshcore.shared.ui.menu.McMenuItem
import one.meshcore.shared.ui.rememberAppHaptics
import org.jetbrains.compose.resources.stringResource

/**
 * Tools → Discover Nodes: active 15s [sendNodeDiscoverRequest] scan with segment filter, sort, and
 * Add-to-contacts. Distinct from Nodes → Discover (passive advert list).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeDiscoveryScreen(
    sendDiscover: suspend (filter: Int, prefixOnly: Boolean) -> Long,
    events: () -> Flow<MeshEvent>?,
    contactService: () -> ContactService?,
    discoveredNodeStore: () -> DiscoveredNodeStore?,
    radioId: () -> Uuid?,
    maxContacts: () -> Int? = { null },
    isRadioReady: Boolean,
    isRadioConnected: () -> Boolean = { false },
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    workspace: NodeDiscoveryWorkspace? = null,
) {
    val scanFailedMessage = stringResource(Res.string.tools_node_discovery_error_title)
    val unknownNodeLabel = stringResource(Res.string.tools_node_discovery_unknown_node)
    val contactErrorText = rememberContactServiceErrorText()
    val notConnectedRepeaters = formattedStringResource(
        Res.string.tools_node_discovery_not_connected_description,
        filterTitle(NodeDiscoveryFilter.REPEATERS),
    )
    val notConnectedSensors = formattedStringResource(
        Res.string.tools_node_discovery_not_connected_description,
        filterTitle(NodeDiscoveryFilter.SENSORS),
    )
    val notConnectedMessages = mapOf(
        NodeDiscoveryFilter.REPEATERS to notConnectedRepeaters,
        NodeDiscoveryFilter.SENSORS to notConnectedSensors,
    )
    val viewModel = rememberNodeDiscoveryModel(
        dependencies = NodeDiscoveryViewModel.Dependencies(
            sendDiscover = sendDiscover, events = events, contactService = contactService,
            discoveredNodeStore = discoveredNodeStore, radioId = radioId,
            isRadioConnected = isRadioConnected, maxContacts = maxContacts,
            notConnectedMessage = { notConnectedMessages.getValue(it) },
            scanFailedMessage = { scanFailedMessage }, unknownNodeLabel = { unknownNodeLabel },
            errorText = contactErrorText,
        ),
        workspace = workspace,
        isRadioReady = isRadioReady,
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val haptics = rememberAppHaptics()
    val actions = nodeDiscoveryActions(scope, viewModel, workspace, haptics::success)
    var sortMenuOpen by remember { mutableStateOf(false) }

    // iOS NodeDiscoveryView scan/add sensory feedback: an impact when the scan starts, a success touch
    // when it returns hits and a warning when it returns nothing, plus a failure touch when an add fails;
    // retained add success follows state changes while the tool is mounted.
    var wasScanning by remember { mutableStateOf(false) }
    LaunchedEffect(state.isScanning) {
        when (
            nodeDiscoveryScanHaptic(
                wasScanning = wasScanning,
                isScanning = state.isScanning,
                hasResults = state.sortedResults.isNotEmpty(),
            )
        ) {
            NodeDiscoveryScanHaptic.TAP -> haptics.tap()
            NodeDiscoveryScanHaptic.SUCCESS -> haptics.success()
            NodeDiscoveryScanHaptic.WARNING -> haptics.warning()
            null -> Unit
        }
        wasScanning = state.isScanning
    }
    var previousAdded by remember { mutableStateOf(state.addedPublicKeyHexes) }
    LaunchedEffect(state.addedPublicKeyHexes) {
        if (workspace != null && (state.addedPublicKeyHexes - previousAdded).isNotEmpty()) haptics.success()
        previousAdded = state.addedPublicKeyHexes
    }
    LaunchedEffect(state.errorMessage, state.addError) {
        if (state.errorMessage != null || state.addError != null) haptics.failure()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            AppNestedTopBar(
                title = stringResource(Res.string.tools_node_discovery),
                onBack = onBack,
                actions = {
                    Box {
                        val sortMenuLabel = stringResource(Res.string.tools_node_discovery_sort_menu)
                        val sortMenuHint = stringResource(Res.string.tools_node_discovery_sort_menu_hint)
                        AppIconButton(
                            imageVector = AppIcons.Sort,
                            contentDescription = sortMenuLabel,
                            onClick = { sortMenuOpen = true },
                            enabled = isRadioReady,
                            modifier = Modifier.semantics {
                                stateDescription = sortMenuHint
                            },
                        )
                        McDropdownMenu(
                            expanded = sortMenuOpen,
                            onDismissRequest = { sortMenuOpen = false },
                        ) {
                            McMenuItem(
                                text = stringResource(Res.string.tools_node_discovery_sort_signal),
                                leadingIcon = AppIcons.Sensors,
                                selected = state.sortOrder == NodeDiscoverySortOrder.SNR,
                                onClick = {
                                    viewModel.setSortOrder(NodeDiscoverySortOrder.SNR)
                                    sortMenuOpen = false
                                },
                            )
                            McMenuItem(
                                text = stringResource(Res.string.tools_node_discovery_sort_name),
                                leadingIcon = AppIcons.Sort,
                                selected = state.sortOrder == NodeDiscoverySortOrder.NAME,
                                onClick = {
                                    viewModel.setSortOrder(NodeDiscoverySortOrder.NAME)
                                    sortMenuOpen = false
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (!isRadioReady) {
                EmptyBlock(
                    title = stringResource(Res.string.tools_rx_log_not_connected),
                    description = formattedStringResource(
                        Res.string.tools_node_discovery_not_connected_description,
                        filterTitle(state.filter),
                    ),
                    icon = AppIcons.CellTowerOff,
                )
                return@Column
            }

            DiscoveryResults(
                state = state,
                onFilterSelect = viewModel::setFilter,
                onAdd = actions.add,
            )

            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                if (state.isScanning) {
                    Button(
                        onClick = actions.stop,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(SCAN_SPINNER_GAP),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(SCAN_SPINNER_SIZE),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = SCAN_SPINNER_STROKE,
                            )
                            Text(stringResource(Res.string.tools_node_discovery_stop_button))
                        }
                    }
                } else {
                    Button(
                        onClick = actions.scan,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            formattedStringResource(
                                Res.string.tools_node_discovery_scan_button,
                                filterTitle(state.filter),
                            ),
                        )
                    }
                }
            }
        }
    }
    val addErrorText = state.addError?.message()
    val errorText = addErrorText ?: state.errorMessage
    errorText?.let { message ->
        ThreadErrorDialog(
            message = message,
            onDismiss = viewModel::clearError,
            dialogTitle = stringResource(Res.string.tools_node_discovery_error_title),
        )
    }
}

private val SCAN_SPINNER_SIZE = 18.dp
private val SCAN_SPINNER_STROKE = 2.dp
private val SCAN_SPINNER_GAP = 8.dp
