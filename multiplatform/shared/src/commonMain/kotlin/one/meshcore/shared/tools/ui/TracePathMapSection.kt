package one.meshcore.shared.tools.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import one.meshcore.shared.map.CENTER_ON_USER_SPAN_DEG
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.map.LocationService
import one.meshcore.shared.map.MapCameraRegion
import one.meshcore.shared.map.MapFilterHost
import one.meshcore.shared.map.MapFilterState
import one.meshcore.shared.map.MapLine
import one.meshcore.shared.map.MapPoint
import one.meshcore.shared.map.boundingRegion
import one.meshcore.shared.map.ui.LocalMapOffline
import one.meshcore.shared.map.ui.MapDisplayChrome
import one.meshcore.shared.map.ui.MapFilterButton
import one.meshcore.shared.map.ui.MapLibreHost
import one.meshcore.shared.map.ui.MapLocateButton
import one.meshcore.shared.map.ui.MapOptionsMenu
import one.meshcore.shared.map.ui.PathDistanceBanner
import one.meshcore.shared.map.ui.rememberNetworkOnlyStyleGate
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_common_cancel
import one.meshcore.shared.resources.contacts_trace_clear_path
import one.meshcore.shared.resources.contacts_trace_clear_path_message
import one.meshcore.shared.resources.contacts_trace_map_center_on_path
import one.meshcore.shared.resources.contacts_trace_map_clear
import one.meshcore.shared.resources.contacts_trace_map_view_results
import one.meshcore.shared.ui.ConfirmAlertDialog
import one.meshcore.shared.ui.icons.AppIcons
import org.jetbrains.compose.resources.stringResource

/**
 * The trace map's editing surface: a MapLibre host wired for tap-to-add / tap-to-remove hop editing,
 * plus the floating controls iOS shows over `TracePathMapView` (clear, run, view results, center/fit,
 * and the map-options menu for labels, style, and north lock).
 *
 * [hopCoordinates] is the current path's repeater coordinates in hop order, used only to fit the
 * camera to the path; [points] drives what MapLibre actually renders (repeater pins, or the SNR-badge
 * overlay once a result exists).
 */
@Composable
internal fun TracePathInteractiveMap(
    ui: TracePathUiState,
    points: List<MapPoint>,
    lines: List<MapLine>,
    hopCoordinates: List<LatLng>,
    hasPath: Boolean,
    canRunTrace: Boolean,
    isRadioReady: Boolean = true,
    onPointTap: (String) -> Unit,
    onRunTrace: () -> Unit,
    onClear: () -> Unit,
    onViewResults: () -> Unit,
    bannerHopCount: Int? = null,
    bannerDistanceMeters: Double? = null,
    isOffline: Boolean = LocalMapOffline.current,
    locationService: LocationService? = null,
    deviceLocation: () -> LatLng? = { null },
    onFilterChange: ((MapFilterState) -> Unit)? = null,
    mapChrome: MapDisplayChrome,
    modifier: Modifier = Modifier,
) {
    val isRunning = ui.isRunning
    val batchProgress = ui.batchProgress
    val batchEnabled = ui.batchEnabled
    val batchSize = ui.batchCount
    val showViewResults = ui.result?.success == true
    val filter = ui.mapFilter
    var cameraTarget by remember { mutableStateOf<MapCameraRegion?>(null) }
    var cameraTargetVersion by remember { mutableStateOf(0) }
    var showClearConfirm by remember { mutableStateOf(false) }
    val (styleDisabled, styleDisabledReason) = rememberNetworkOnlyStyleGate(isNetworkAvailable = !isOffline)

    // Re-fires even when the region is unchanged, so running the same path twice re-frames it.
    fun frameOnPath() {
        val fitted = traceCameraFit(hopCoordinates, points) ?: return
        cameraTarget = fitted
        cameraTargetVersion += 1
    }

    Box(modifier.fillMaxSize()) {
        MapLibreHost(
            points = points,
            lines = lines,
            style = mapChrome.style,
            // The host frames itself once, on the first pass that has anything to frame. Handing it the
            // path's own fit makes that first frame the path when one is already loaded, and all located
            // repeaters otherwise.
            initialCamera = hopCoordinates.takeIf { it.isNotEmpty() }?.boundingRegion(),
            cameraTarget = cameraTarget,
            cameraTargetVersion = cameraTargetVersion,
            isOffline = isOffline,
            colorSchemePreference = mapChrome.colorScheme,
            showLabels = mapChrome.showLabels,
            clusteringEnabled = mapChrome.clusteringEnabled,
            northLocked = mapChrome.northLocked,
            showsUserLocation = true,
            showsScale = true,
            onPointClick = onPointTap,
            modifier = Modifier.fillMaxSize(),
        )

        if (bannerHopCount != null) {
            PathDistanceBanner(
                hopCount = bannerHopCount,
                totalPathDistanceMeters = bannerDistanceMeters,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(start = FAB_INSET, end = FAB_INSET, top = FAB_INSET, bottom = ACTION_ROW_CLEARANCE),
            verticalArrangement = Arrangement.spacedBy(BUTTON_GAP),
        ) {
            MapLocateButton(
                locationService = locationService,
                knownLocation = deviceLocation,
                onFrame = { region ->
                    cameraTarget = region
                    cameraTargetVersion += 1
                },
                spanDeg = CENTER_ON_USER_SPAN_DEG,
            )
            if (onFilterChange != null) {
                MapFilterButton(
                    host = MapFilterHost.TRACE_PATH,
                    filter = filter,
                    onFilterChange = onFilterChange,
                )
            }
            MapOptionsMenu(
                chrome = mapChrome,
                styleDisabled = styleDisabled,
                styleDisabledReason = styleDisabledReason,
            )
            SmallFloatingActionButton(
                onClick = { frameOnPath() },
                containerColor = FloatingActionButtonDefaults.containerColor,
            ) {
                Icon(
                    imageVector = AppIcons.LocationOn,
                    contentDescription = stringResource(Res.string.contacts_trace_map_center_on_path),
                )
            }
        }

        if (hasPath) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(start = FAB_INSET, end = FAB_INSET, bottom = FAB_INSET),
                verticalArrangement = Arrangement.spacedBy(BUTTON_GAP),
            ) {
                TraceRunButton(
                    isRunning = isRunning,
                    batchProgress = batchProgress,
                    enabled = canRunTrace,
                    isRadioReady = isRadioReady,
                    batchEnabled = batchEnabled,
                    batchSize = batchSize,
                    onClick = {
                        frameOnPath()
                        onRunTrace()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(BUTTON_GAP),
                ) {
                    OutlinedButton(
                        onClick = { showClearConfirm = true },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(Res.string.contacts_trace_map_clear))
                    }
                    if (showViewResults) {
                        FilledTonalButton(
                            onClick = onViewResults,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(Res.string.contacts_trace_map_view_results))
                        }
                    }
                }
            }
        }
    }

    if (showClearConfirm) {
        ConfirmAlertDialog(
            title = stringResource(Res.string.contacts_trace_clear_path),
            message = stringResource(Res.string.contacts_trace_clear_path_message),
            confirmLabel = stringResource(Res.string.contacts_trace_clear_path),
            dismissLabel = stringResource(Res.string.contacts_common_cancel),
            onConfirm = {
                showClearConfirm = false
                onClear()
            },
            onDismiss = { showClearConfirm = false },
        )
    }
}

/**
 * What the trace map frames: the path's own repeaters while one is being built, and every located
 * repeater when there is no path yet. Null when neither set has a coordinate to fit.
 */
internal fun traceCameraFit(hopCoordinates: List<LatLng>, points: List<MapPoint>): MapCameraRegion? =
    hopCoordinates.takeIf { it.isNotEmpty() }?.boundingRegion()
        ?: points.map { it.coordinate }.boundingRegion()

private val FAB_INSET = 16.dp
private val ACTION_ROW_CLEARANCE = 120.dp
private val BUTTON_GAP = 8.dp
