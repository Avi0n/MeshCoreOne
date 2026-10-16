package one.meshcore.shared.remotenode.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.backup.NodeStatusSnapshotDto
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.chat.NodeNameResolution
import one.meshcore.shared.contact.DiscoveredNodeRecord
import one.meshcore.shared.contact.UserLocation
import one.meshcore.shared.map.LocationService
import one.meshcore.shared.map.MapFilterHost
import one.meshcore.shared.map.MapFilterState
import one.meshcore.shared.map.MapStyleSelection
import one.meshcore.shared.map.ui.FramedStatusMap
import one.meshcore.shared.map.ui.MapFilterButton
import one.meshcore.shared.map.ui.rememberNetworkOnlyStyleGate
import one.meshcore.shared.map.ui.rememberSnrBadgeFormatter
import one.meshcore.shared.meshcore.Neighbour
import one.meshcore.shared.model.toUppercaseHex
import one.meshcore.shared.prefs.PersistenceKeys
import one.meshcore.shared.prefs.PreferencesStore
import one.meshcore.shared.remotenode.HistoryTimeRange
import one.meshcore.shared.remotenode.NeighborSnrMapBuilder
import one.meshcore.shared.remotenode.neighborSnrSeries
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.remotenodes_remote_nodes_history_no_snapshots_message
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_neighbors_map_title
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_neighbors_not_shown
import one.meshcore.shared.theme.AppColorSchemePreference
import one.meshcore.shared.ui.EMPTY_STATE_ICON_SIZE
import one.meshcore.shared.ui.FullScreenOverlay
import one.meshcore.shared.ui.formattedPluralStringResource
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NeighborsMapSheet(
    session: RemoteNodeSessionDto,
    neighbors: List<Neighbour>,
    contacts: List<ContactDto>,
    discovered: List<DiscoveredNodeRecord>,
    userLocation: UserLocation?,
    keyDisplayByteCount: Int,
    colorSchemePreference: AppColorSchemePreference,
    isOffline: Boolean,
    locationService: LocationService?,
    preferences: PreferencesStore?,
    onDismiss: () -> Unit,
    mapStyle: MapStyleSelection = MapStyleSelection.STANDARD,
    mapChrome: one.meshcore.shared.map.ui.MapDisplayChrome? = null,
) {
    val chrome = mapChrome
    val (styleDisabled, styleDisabledReason) = rememberNetworkOnlyStyleGate(isNetworkAvailable = !isOffline)
    val scope = rememberCoroutineScope()
    var mapFilter by remember { mutableStateOf(MapFilterState.seed(MapFilterHost.NEIGHBOR_SNR)) }
    LaunchedEffect(preferences) {
        val prefs = preferences ?: return@LaunchedEffect
        val raw = prefs.getString(PersistenceKeys.MAP_FILTER_NEIGHBOR_SNR)
        mapFilter = MapFilterState.fromRaw(raw, MapFilterHost.NEIGHBOR_SNR)
    }
    val snrFormatter = rememberSnrBadgeFormatter()
    val plot = remember(
        neighbors,
        contacts,
        discovered,
        userLocation,
        session,
        mapFilter,
        keyDisplayByteCount,
        snrFormatter,
    ) {
        NeighborSnrMapBuilder.build(
            session = session,
            neighbors = neighbors,
            contacts = contacts,
            options = NeighborSnrMapBuilder.Options(
                discovered = discovered,
                userLocation = userLocation,
                filter = mapFilter,
                formatter = snrFormatter,
                keyDisplayByteCount = keyDisplayByteCount,
            ),
        )
    }
    var showUnplottableList by remember { mutableStateOf(false) }
    LaunchedEffect(plot.unplottable.isEmpty()) {
        if (plot.unplottable.isEmpty()) showUnplottableList = false
    }
    FullScreenOverlay(
        onDismiss = onDismiss,
        title = stringResource(Res.string.remotenodes_remote_nodes_status_neighbors_map_title),
    ) { innerPadding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            FramedStatusMap(
                points = plot.points,
                lines = plot.lines,
                region = plot.region,
                cameraResetKey = plot,
                colorSchemePreference = colorSchemePreference,
                isOffline = isOffline,
                locationService = locationService,
                userLocation = userLocation,
                chrome = chrome,
                fallbackStyle = mapStyle,
                styleDisabled = styleDisabled,
                styleDisabledReason = styleDisabledReason,
                betweenCenterAndLocate = {
                    MapFilterButton(
                        host = MapFilterHost.NEIGHBOR_SNR,
                        filter = mapFilter,
                        onFilterChange = { next ->
                            val sanitized = next.sanitized(MapFilterHost.NEIGHBOR_SNR)
                            mapFilter = sanitized
                            val prefs = preferences
                            if (prefs != null) {
                                scope.launch {
                                    prefs.putString(
                                        PersistenceKeys.MAP_FILTER_NEIGHBOR_SNR,
                                        sanitized.toStorageString(),
                                    )
                                }
                            }
                        },
                    )
                },
            )
            if (plot.unplottable.isNotEmpty()) {
                val notShown = formattedPluralStringResource(
                    Res.plurals.remotenodes_remote_nodes_status_neighbors_not_shown,
                    plot.unplottable.size,
                    plot.unplottable.size,
                )
                FilledTonalButton(
                    onClick = { showUnplottableList = true },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp),
                ) {
                    Text(
                        text = notShown,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
    if (showUnplottableList) {
        NeighborsNoLocationList(
            unplottable = plot.unplottable,
            keyDisplayByteCount = keyDisplayByteCount,
            onDismiss = { showUnplottableList = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NeighborsNoLocationList(
    unplottable: List<NeighborSnrMapBuilder.UnplottableNeighbor>,
    keyDisplayByteCount: Int,
    onDismiss: () -> Unit,
) {
    val title = formattedPluralStringResource(
        Res.plurals.remotenodes_remote_nodes_status_neighbors_not_shown,
        unplottable.size,
        unplottable.size,
    )
    FullScreenOverlay(onDismiss = onDismiss, title = title) { innerPadding ->
        LazyColumn(modifier = Modifier.padding(innerPadding)) {
            items(
                items = unplottable,
                key = { it.neighbor.publicKeyPrefix.toUppercaseHex() },
            ) { item ->
                NeighborRow(
                    neighbor = item.neighbor,
                    resolution = NodeNameResolution(
                        displayName = item.displayName,
                        matchKind = item.matchKind,
                    ),
                    snrDelta = null,
                    isNew = false,
                    keyDisplayByteCount = keyDisplayByteCount,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NeighborSnrChartSheet(
    name: String,
    prefix: ByteArray,
    snapshots: List<NodeStatusSnapshotDto>,
    nowEpochSeconds: Double,
    onDismiss: () -> Unit,
) {
    var range by remember { mutableStateOf(HistoryTimeRange.DEFAULT) }
    val filtered = remember(snapshots, range, nowEpochSeconds) {
        range.filter(snapshots, nowEpochSeconds)
    }
    val points = remember(prefix, filtered) { neighborSnrSeries(filtered, prefix) }
    FullScreenOverlay(onDismiss = onDismiss, title = name) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = prefix.toUppercaseHex(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            TimeRangeChips(range = range, onSelect = { range = it })
            if (points.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp, bottom = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = TELEMETRY_HISTORY_EMPTY_ICON,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(bottom = 12.dp)
                            .size(EMPTY_STATE_ICON_SIZE),
                    )
                    Text(
                        text = stringResource(Res.string.remotenodes_remote_nodes_history_no_snapshots_message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                MetricChartView(
                    title = name,
                    points = points,
                    unit = DB_UNIT.trim(),
                    lineColor = TelemetrySeriesRole.PRIMARY.color(),
                )
            }
        }
    }
}

private const val DB_UNIT = " dB"
