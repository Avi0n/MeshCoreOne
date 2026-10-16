package one.meshcore.shared.remotenode.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.backup.NeighborSnapshotEntry
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.chat.NodeNameMatchKind
import one.meshcore.shared.chat.NodeNameResolution
import one.meshcore.shared.contact.DiscoveredNodeRecord
import one.meshcore.shared.contact.UserLocation
import one.meshcore.shared.map.snrQuality
import one.meshcore.shared.meshcore.Neighbour
import one.meshcore.shared.remotenode.isAdmin
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.remotenodes_remote_nodes_history_new
import one.meshcore.shared.resources.remotenodes_remote_nodes_history_not_seen
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_accessibility_reload_neighbors
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_accessibility_view_neighbors_on_map
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_discover_neighbors
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_discovering_seconds
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_hours_ago
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_minutes_ago
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_neighbors
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_neighbors_footer
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_neighbors_map_title
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_no_neighbors
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_seconds_ago
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_snr_format
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_unknown
import one.meshcore.shared.ui.PreferenceHubStatusRow
import one.meshcore.shared.ui.SignalBarMeter
import one.meshcore.shared.ui.barTint
import one.meshcore.shared.ui.filledBarCount
import one.meshcore.shared.ui.formatTemplate
import one.meshcore.shared.ui.formattedStringResource
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.preferenceHubCardAction
import one.meshcore.shared.ui.radioConnectionHint
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun NeighborsSection(
    viewModel: NodeStatusViewModel,
    state: NodeStatusState,
    session: RemoteNodeSessionDto,
    contacts: List<ContactDto>,
    discovered: List<DiscoveredNodeRecord>,
    userLocation: UserLocation?,
    keyDisplayByteCount: Int,
    onOpenMap: () -> Unit,
    onOpenNeighborChart: (Neighbour, NodeNameResolution) -> Unit,
    isRadioReady: Boolean,
) {
    val viewOnMapLabel =
        stringResource(Res.string.remotenodes_remote_nodes_status_accessibility_view_neighbors_on_map)
    StatusSection(
        expanded = state.neighborsExpanded,
        onExpandedChange = viewModel::setNeighborsExpanded,
        title = stringResource(Res.string.remotenodes_remote_nodes_status_neighbors),
        isLoading = state.isLoadingNeighbors && !state.isDiscovering,
        reloadEnabled = !state.isDiscovering,
        isRadioReady = isRadioReady,
        onReload = { viewModel.requestNeighbors() },
        reloadAccessibilityLabel = stringResource(
            Res.string.remotenodes_remote_nodes_status_accessibility_reload_neighbors,
        ),
        footer = stringResource(Res.string.remotenodes_remote_nodes_status_neighbors_footer),
    ) {
        NeighborsSectionBody(
            state = state,
            contacts = contacts,
            discovered = discovered,
            userLocation = userLocation,
            keyDisplayByteCount = keyDisplayByteCount,
            viewOnMapLabel = viewOnMapLabel,
            onOpenMap = onOpenMap,
            onOpenNeighborChart = onOpenNeighborChart,
        )
        if (session.isAdmin) {
            DiscoverNeighborsButton(viewModel = viewModel, state = state, isRadioReady = isRadioReady)
        }
        if (state.neighborsSectionError != null && !state.isDiscovering && state.neighborsLoaded) {
            SectionErrorText(state.neighborsSectionError)
        }
    }
}

@Composable
private fun NeighborsSectionBody(
    state: NodeStatusState,
    contacts: List<ContactDto>,
    discovered: List<DiscoveredNodeRecord>,
    userLocation: UserLocation?,
    keyDisplayByteCount: Int,
    viewOnMapLabel: String,
    onOpenMap: () -> Unit,
    onOpenNeighborChart: (Neighbour, NodeNameResolution) -> Unit,
) {
    when {
        state.neighborsSectionError != null && !state.neighborsLoaded && !state.isDiscovering ->
            SectionErrorText(state.neighborsSectionError)
        state.neighborsLoaded && state.neighbors.isEmpty() && !state.isDiscovering -> PreferenceHubStatusRow(
            stringResource(Res.string.remotenodes_remote_nodes_status_no_neighbors),
        )
        state.neighbors.isNotEmpty() -> NeighborsList(
            state = state,
            contacts = contacts,
            discovered = discovered,
            userLocation = userLocation,
            keyDisplayByteCount = keyDisplayByteCount,
            viewOnMapLabel = viewOnMapLabel,
            onOpenMap = onOpenMap,
            onOpenNeighborChart = onOpenNeighborChart,
        )
    }
}

@Composable
private fun NeighborsList(
    state: NodeStatusState,
    contacts: List<ContactDto>,
    discovered: List<DiscoveredNodeRecord>,
    userLocation: UserLocation?,
    keyDisplayByteCount: Int,
    viewOnMapLabel: String,
    onOpenMap: () -> Unit,
    onOpenNeighborChart: (Neighbour, NodeNameResolution) -> Unit,
) {
    state.neighbors.forEach { neighbor ->
        val resolution = NeighborPrefixResolver.resolve(
            neighbor.publicKeyPrefix,
            contacts,
            discovered,
            userLocation,
            keyDisplayByteCount,
        )
        val snrDelta = NodeStatusDeltas.neighborSnrDelta(
            neighbor,
            state.previousNeighborSnapshot?.neighborSnapshots,
        )
        val isNew = NodeStatusDeltas.isNewNeighbor(
            prefix = neighbor.publicKeyPrefix,
            previousNeighborSnapshot = state.previousNeighborSnapshot,
            seenPrefixes = state.seenNeighborPrefixes,
        )
        NeighborRow(
            neighbor = neighbor,
            resolution = resolution,
            snrDelta = snrDelta,
            isNew = isNew,
            keyDisplayByteCount = keyDisplayByteCount,
            onClick = { onOpenNeighborChart(neighbor, resolution) },
        )
    }
    val disappeared = NodeStatusDeltas.disappearedNeighbors(
        state.neighbors,
        state.previousNeighborSnapshot?.neighborSnapshots,
    )
    disappeared.forEach { entry ->
        val resolution = NeighborPrefixResolver.resolve(
            entry.publicKeyPrefix,
            contacts,
            discovered,
            userLocation,
            keyDisplayByteCount,
        )
        DisappearedNeighborRow(entry = entry, resolution = resolution)
    }
    StatusMapButton(
        text = stringResource(Res.string.remotenodes_remote_nodes_status_neighbors_map_title),
        spokenLabel = viewOnMapLabel,
        onClick = onOpenMap,
    )
}

@Composable
private fun DiscoverNeighborsButton(viewModel: NodeStatusViewModel, state: NodeStatusState, isRadioReady: Boolean) {
    FilledTonalButton(
        onClick = {
            if (state.isDiscovering) {
                viewModel.stopDiscovery()
            } else {
                viewModel.startDiscovery()
            }
        },
        enabled = isRadioReady && (!state.isLoadingNeighbors || state.isDiscovering),
        modifier = Modifier
            .preferenceHubCardAction()
            .radioConnectionHint(isRadioReady),
    ) {
        if (state.isDiscovering) {
            CircularProgressIndicator(
                modifier = Modifier
                    .padding(end = LABEL_SPACING)
                    .size(SPINNER_SIZE),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                text = formattedStringResource(
                    Res.string.remotenodes_remote_nodes_status_discovering_seconds,
                    state.discoverySecondsRemaining,
                ),
            )
        } else {
            Icon(
                imageVector = AppIcons.CellTower,
                contentDescription = null,
                modifier = Modifier
                    .size(SPINNER_SIZE)
                    .padding(end = 4.dp),
            )
            Text(stringResource(Res.string.remotenodes_remote_nodes_status_discover_neighbors))
        }
    }
}

@Composable
internal fun NeighborRow(
    neighbor: Neighbour,
    resolution: NodeNameResolution,
    snrDelta: Double?,
    isNew: Boolean,
    keyDisplayByteCount: Int,
    onClick: (() -> Unit)? = null,
) {
    val quality = snrQuality(neighbor.snr)
    val qualityColor = quality.barTint()
    ListItem(
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = NeighborPrefixResolver.neighborRowDisplayName(
                        resolution,
                        stringResource(Res.string.remotenodes_remote_nodes_status_unknown),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                if (isNew) {
                    Text(
                        text = stringResource(Res.string.remotenodes_remote_nodes_history_new),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (resolution.matchKind == NodeNameMatchKind.FALLBACK) {
                    PossibleMatchMarker()
                }
            }
        },
        supportingContent = {
            Column {
                Text(
                    text = NeighborPrefixResolver.fallbackName(neighbor.publicKeyPrefix, keyDisplayByteCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = lastSeenText(neighbor.secondsAgo),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                SignalBarMeter(
                    filledBars = quality.filledBarCount,
                    tint = qualityColor,
                )
                Text(
                    text = formatTemplate(
                        stringResource(Res.string.remotenodes_remote_nodes_status_snr_format),
                        listOf(NodeStatusFormat.oneDecimal(neighbor.snr)),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (snrDelta != null) {
                    StatusDeltaView(delta = snrDelta, higherIsBetter = true, unit = DB_UNIT, fractionDigits = 1)
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    )
}

@Composable
private fun DisappearedNeighborRow(entry: NeighborSnapshotEntry, resolution: NodeNameResolution) {
    ListItem(
        headlineContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = resolution.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // The sibling neighbour and route-path rows already flag a short-prefix guess; this row
                // did not, so the one place a user asks "which repeater dropped?" was the one place a
                // possibly-wrong name looked certain.
                if (resolution.matchKind == NodeNameMatchKind.FALLBACK) {
                    PossibleMatchMarker()
                }
            }
        },
        supportingContent = {
            Column {
                Text(
                    text = stringResource(Res.string.remotenodes_remote_nodes_history_not_seen),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = formatTemplate(
                        stringResource(Res.string.remotenodes_remote_nodes_status_snr_format),
                        listOf(NodeStatusFormat.oneDecimal(entry.snr)),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun lastSeenText(secondsAgo: Long): String {
    val seconds = secondsAgo.coerceAtLeast(0)
    return when {
        seconds < SECONDS_PER_MINUTE -> formattedStringResource(
            Res.string.remotenodes_remote_nodes_status_seconds_ago,
            seconds.toInt(),
        )
        seconds < SECONDS_PER_HOUR -> formattedStringResource(
            Res.string.remotenodes_remote_nodes_status_minutes_ago,
            (seconds / SECONDS_PER_MINUTE).toInt(),
        )
        else -> formattedStringResource(
            Res.string.remotenodes_remote_nodes_status_hours_ago,
            (seconds / SECONDS_PER_HOUR).toInt(),
        )
    }
}

private const val DB_UNIT = " dB"
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3600L

private val LABEL_SPACING = 6.dp
private val SPINNER_SIZE = 16.dp
