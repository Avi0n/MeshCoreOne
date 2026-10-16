package one.meshcore.shared.tools.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.map.LocationService
import one.meshcore.shared.map.MapFilterHost
import one.meshcore.shared.map.MapLine
import one.meshcore.shared.map.MapPoint
import one.meshcore.shared.map.ui.MapDisplayChrome
import one.meshcore.shared.map.ui.tracePathBannerHopCount
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.pathedit.DiscoveredNode
import one.meshcore.shared.pathedit.PathHop
import one.meshcore.shared.pathedit.PickerNode
import one.meshcore.shared.prefs.PersistenceKeys
import one.meshcore.shared.prefs.PreferencesStore
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_path_edit_empty_title
import one.meshcore.shared.resources.contacts_trace_list_empty_path
import one.meshcore.shared.trace.RepeaterPathInfo
import one.meshcore.shared.trace.TracePathBuilder
import one.meshcore.shared.trace.TracePathMapBuilder
import one.meshcore.shared.trace.totalPathDistanceMeters
import one.meshcore.shared.ui.AppHaptics
import one.meshcore.shared.ui.EMPTY_STATE_ICON_SIZE
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun TracePathMapPane(
    ui: TracePathUiState,
    mapPlot: TracePathMapBuilder.Plot?,
    resultMatchesPath: Boolean,
    candidatePoints: List<MapPoint>,
    candidateLines: List<MapLine>,
    hopCoordinates: List<LatLng>,
    outboundPath: List<PathHop>,
    canRunTrace: Boolean,
    isRadioReady: Boolean,
    isOffline: Boolean,
    mapChrome: MapDisplayChrome,
    locationService: LocationService?,
    deviceLocation: () -> LatLng?,
    visibleContacts: List<ContactDto>,
    visibleDiscovered: List<DiscoveredNode>,
    mapPathState: Map<Uuid, RepeaterPathInfo>,
    builder: TracePathBuilder,
    haptics: AppHaptics,
    preferences: PreferencesStore?,
    scope: CoroutineScope,
    onRun: () -> Unit,
    onClear: () -> Unit,
    onViewResults: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val locatedPlot = mapPlot?.takeIf { ui.result?.success == true && resultMatchesPath }
    val points = if (locatedPlot != null) locatedPlot.points else candidatePoints
    val lines = if (locatedPlot != null) locatedPlot.lines else candidateLines
    val overlayResult = ui.result.takeIf { locatedPlot != null }
    if (points.isNotEmpty()) {
        Box(modifier.fillMaxSize()) {
            TracePathInteractiveMap(
                ui = ui,
                points = points,
                lines = lines,
                hopCoordinates = hopCoordinates,
                hasPath = outboundPath.isNotEmpty(),
                canRunTrace = canRunTrace,
                isRadioReady = isRadioReady,
                bannerHopCount = overlayResult?.let { tracePathBannerHopCount(it.hops.size) },
                bannerDistanceMeters = overlayResult?.let { totalPathDistanceMeters(it.hops) },
                isOffline = isOffline,
                mapChrome = mapChrome,
                locationService = locationService,
                deviceLocation = deviceLocation,
                onPointTap = { id ->
                    applyTracePathMapTap(
                        TracePathMapTap(
                            id = id,
                            isRunning = ui.isRunning,
                            contacts = visibleContacts,
                            discovered = visibleDiscovered,
                            pathState = mapPathState,
                            onAppendContact = { contact ->
                                haptics.tap()
                                builder.appendHop(PickerNode.Contact(contact))
                            },
                            onAppendDiscovered = { node ->
                                haptics.tap()
                                builder.appendHop(PickerNode.Discovered(node))
                            },
                            onRemoveLast = {
                                haptics.tap()
                                builder.removeHop(outboundPath.lastIndex)
                            },
                            onRejected = { haptics.warning() },
                            onApplied = { ui.errorMessage = null },
                        ),
                    )
                },
                onFilterChange = { next ->
                    val sanitized = next.sanitized(MapFilterHost.TRACE_PATH)
                    ui.mapFilter = sanitized
                    val prefs = preferences
                    if (prefs != null) {
                        scope.launch {
                            prefs.putString(
                                PersistenceKeys.MAP_FILTER_TRACE_PATH,
                                sanitized.toStorageString(),
                            )
                        }
                    }
                },
                onRunTrace = onRun,
                onClear = onClear,
                onViewResults = onViewResults,
                modifier = Modifier.fillMaxSize(),
            )
        }
    } else {
        Column(
            modifier = modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        ) {
            Icon(
                imageVector = TRACE_PATH_EMPTY_ICON,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(EMPTY_STATE_ICON_SIZE),
            )
            Text(
                text = stringResource(Res.string.contacts_path_edit_empty_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(Res.string.contacts_trace_list_empty_path),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
