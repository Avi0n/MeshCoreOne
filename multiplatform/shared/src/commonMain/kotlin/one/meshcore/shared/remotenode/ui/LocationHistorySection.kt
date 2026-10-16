package one.meshcore.shared.remotenode.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import one.meshcore.shared.backup.NodeStatusSnapshotDto
import one.meshcore.shared.chat.ui.LocalTimestampFormatter
import one.meshcore.shared.chat.ui.TimestampFormatter
import one.meshcore.shared.contact.UserLocation
import one.meshcore.shared.map.LocationService
import one.meshcore.shared.map.MapStyleSelection
import one.meshcore.shared.map.boundingRegion
import one.meshcore.shared.map.ui.FramedStatusMap
import one.meshcore.shared.map.ui.LocalMapOffline
import one.meshcore.shared.map.ui.MapDisplayChrome
import one.meshcore.shared.map.ui.MapLibreHost
import one.meshcore.shared.map.ui.rememberNetworkOnlyStyleGate
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.remotenode.LPPDisplay
import one.meshcore.shared.remotenode.LocalizedLengthSymbols
import one.meshcore.shared.remotenode.LocationPathMapBuilder
import one.meshcore.shared.remotenode.LocationReportFormat
import one.meshcore.shared.remotenode.MeasurementSystem
import one.meshcore.shared.remotenode.NodeLocationReport
import one.meshcore.shared.remotenode.Recency
import one.meshcore.shared.remotenode.RecencyUnit
import one.meshcore.shared.remotenode.locationReports
import one.meshcore.shared.remotenode.validCoordinate
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.remotenodes_remote_nodes_history_days_ago
import one.meshcore.shared.resources.remotenodes_remote_nodes_history_location_section
import one.meshcore.shared.resources.remotenodes_remote_nodes_history_section_not_captured
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_accessibility_view_location_on_map
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_hours_ago
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_location_map_title
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_minutes_ago
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_seconds_ago
import one.meshcore.shared.theme.AppColorSchemePreference
import one.meshcore.shared.ui.FullScreenOverlay
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.formatTemplate
import one.meshcore.shared.ui.formattedStringResource
import one.meshcore.shared.ui.icons.AppIcons
import org.jetbrains.compose.resources.stringResource

private val PREVIEW_HEIGHT = 200.dp
private val TICK_SIZE = 9.dp

/**
 * The "Location" block of the telemetry history screens: an inline, non-interactive map preview of the
 * node's GPS path with an expand affordance, plus the History list — one row per fix, newest first,
 * carrying recency, timestamp, coordinates, and altitude. Both the expand button and a row tap open the
 * full-screen map, pre-selecting the tapped report's callout. Mirrors the iOS `LocationHistorySection`.
 * [showsFullPath] false plots only the latest fix on the inline preview (telemetry History); the
 * full-screen map still uses every fix. Shows the "not captured" note when no snapshot in range holds
 * a valid fix.
 */
@Composable
fun LocationHistorySection(
    snapshots: List<NodeStatusSnapshotDto>,
    nowEpochSeconds: Double,
    colorSchemePreference: AppColorSchemePreference,
    modifier: Modifier = Modifier,
    isOffline: Boolean = LocalMapOffline.current,
    locationService: LocationService? = null,
    userLocation: UserLocation? = null,
    mapStyle: MapStyleSelection = MapStyleSelection.STANDARD,
    mapChrome: MapDisplayChrome? = null,
    showsFullPath: Boolean = true,
) {
    val reports = remember(snapshots) { locationReports(snapshots) }
    // Distinguishes "no sheet" (null) from "sheet open with no pre-selection" (a request carrying a null id).
    var sheetRequest by remember { mutableStateOf<LocationMapRequest?>(null) }
    val sectionLabel = stringResource(Res.string.remotenodes_remote_nodes_history_location_section)

    PreferenceHubSection(
        title = sectionLabel,
        applyHorizontalPadding = false,
        modifier = modifier,
    ) {
        if (reports.isEmpty()) {
            ListItem(
                headlineContent = {
                    Text(
                        text = formatTemplate(
                            stringResource(Res.string.remotenodes_remote_nodes_history_section_not_captured),
                            listOf(sectionLabel),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LocationMapPreview(
                snapshots = snapshots,
                colorSchemePreference = colorSchemePreference,
                mapStyle = mapStyle,
                isOffline = isOffline,
                showsFullPath = showsFullPath,
                onExpand = { sheetRequest = LocationMapRequest(null) },
            )
            reports.forEachIndexed { index, snapshot ->
                LocationReportRow(
                    snapshot = snapshot,
                    isLatest = index == 0,
                    nowEpochSeconds = nowEpochSeconds,
                    onClick = { sheetRequest = LocationMapRequest(snapshot.id) },
                )
            }
        }
    }

    sheetRequest?.let { request ->
        NodeLocationMapSheet(
            snapshots = snapshots,
            nowEpochSeconds = nowEpochSeconds,
            colorSchemePreference = colorSchemePreference,
            mapStyle = mapStyle,
            mapChrome = mapChrome,
            initialSnapshotId = request.initialSnapshotId,
            isOffline = isOffline,
            locationService = locationService,
            userLocation = userLocation,
            onDismiss = { sheetRequest = null },
        )
    }
}

private data class LocationMapRequest(val initialSnapshotId: Uuid?)

@Composable
private fun LocationMapPreview(
    snapshots: List<NodeStatusSnapshotDto>,
    colorSchemePreference: AppColorSchemePreference,
    mapStyle: MapStyleSelection,
    isOffline: Boolean,
    showsFullPath: Boolean,
    onExpand: () -> Unit,
) {
    val path = remember(snapshots, showsFullPath) {
        LocationPathMapBuilder.preview(snapshots, showsFullPath)
    }
    val region = remember(path) { path.points.map { it.coordinate }.boundingRegion() }
    val expandLabel = stringResource(Res.string.remotenodes_remote_nodes_status_accessibility_view_location_on_map)
    Box(
        Modifier
            .fillMaxWidth()
            .height(PREVIEW_HEIGHT)
            .clip(MaterialTheme.shapes.medium),
    ) {
        MapLibreHost(
            points = path.points,
            lines = path.lines,
            style = mapStyle,
            initialCamera = region,
            cameraTarget = region,
            colorSchemePreference = colorSchemePreference,
            isOffline = isOffline,
            interactive = false,
            modifier = Modifier.fillMaxSize(),
        )
        SmallFloatingActionButton(
            onClick = onExpand,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .semantics { contentDescription = expandLabel },
        ) {
            Icon(imageVector = AppIcons.Map, contentDescription = null)
        }
    }
}

@Composable
private fun LocationReportRow(
    snapshot: NodeStatusSnapshotDto,
    isLatest: Boolean,
    nowEpochSeconds: Double,
    onClick: () -> Unit,
) {
    val emphasis = if (isLatest) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    val tickColor = if (isLatest) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    val system = LPPDisplay.currentMeasurementSystem()
    val symbols = LPPDisplay.currentLengthSymbols()
    ListItem(
        leadingContent = {
            Box(
                Modifier
                    .size(TICK_SIZE)
                    .background(tickColor, CircleShape),
            )
        },
        headlineContent = {
            Text(
                text = recencyText(snapshot.timestamp, nowEpochSeconds),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isLatest) FontWeight.SemiBold else FontWeight.Normal,
                color = emphasis,
            )
        },
        supportingContent = {
            Text(
                text = reportDetailLine(snapshot, system, symbols, formatter = LocalTimestampFormatter.current),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NodeLocationMapSheet(
    snapshots: List<NodeStatusSnapshotDto>,
    nowEpochSeconds: Double,
    colorSchemePreference: AppColorSchemePreference,
    initialSnapshotId: Uuid?,
    isOffline: Boolean,
    locationService: LocationService?,
    userLocation: UserLocation?,
    onDismiss: () -> Unit,
    mapStyle: MapStyleSelection = MapStyleSelection.STANDARD,
    mapChrome: MapDisplayChrome? = null,
) {
    // The full-screen map keeps every report tappable (no decimation), unlike the inline preview.
    val path = remember(snapshots) { LocationPathMapBuilder.build(snapshots, decimatePins = false) }
    val region = remember(path) { path.points.map { it.coordinate }.boundingRegion() }
    var selected by remember(initialSnapshotId, path) {
        mutableStateOf(path.reports.values.firstOrNull { it.snapshotId == initialSnapshotId })
    }
    val chrome = mapChrome
    val (styleDisabled, styleDisabledReason) = rememberNetworkOnlyStyleGate(isNetworkAvailable = !isOffline)
    FullScreenOverlay(
        onDismiss = onDismiss,
        title = stringResource(Res.string.remotenodes_remote_nodes_status_location_map_title),
    ) { innerPadding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            FramedStatusMap(
                points = path.points,
                lines = path.lines,
                region = region,
                cameraResetKey = path,
                colorSchemePreference = colorSchemePreference,
                isOffline = isOffline,
                locationService = locationService,
                userLocation = userLocation,
                chrome = chrome,
                fallbackStyle = mapStyle,
                styleDisabled = styleDisabled,
                styleDisabledReason = styleDisabledReason,
                onPointClick = { id -> selected = path.reports[id] },
            )
            selected?.let { report ->
                LocationReportCallout(
                    report = report,
                    nowEpochSeconds = nowEpochSeconds,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp),
                )
            }
        }
    }
}

@Composable
private fun LocationReportCallout(report: NodeLocationReport, nowEpochSeconds: Double, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        tonalElevation = 3.dp,
        modifier = modifier,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = recencyText(report.timestampSeconds, nowEpochSeconds),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = calloutDetailLine(
                    report,
                    LPPDisplay.currentMeasurementSystem(),
                    LPPDisplay.currentLengthSymbols(),
                    formatter = LocalTimestampFormatter.current,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The row detail: "Jul 13 07:41, 37.7847, -122.4012, 42 m", dropping coordinates/altitude when absent. */
internal fun reportDetailLine(
    snapshot: NodeStatusSnapshotDto,
    system: MeasurementSystem = MeasurementSystem.METRIC,
    symbols: LocalizedLengthSymbols = LocalizedLengthSymbols.LATIN,
    formatter: TimestampFormatter,
): String = buildString {
    append(LocationReportFormat.absoluteTime(snapshot.timestamp, formatter))
    snapshot.validCoordinate()?.let { coordinate ->
        append(LocationReportFormat.SEPARATOR)
        append(LocationReportFormat.coordinates(coordinate.latitude, coordinate.longitude))
    }
    snapshot.altitude?.let {
        append(LocationReportFormat.SEPARATOR)
        append(LocationReportFormat.altitude(it, system, symbols))
    }
}

/** The callout detail: absolute time, plus altitude when the fix carried one. A report states *when*, not where. */
internal fun calloutDetailLine(
    report: NodeLocationReport,
    system: MeasurementSystem = MeasurementSystem.METRIC,
    symbols: LocalizedLengthSymbols = LocalizedLengthSymbols.LATIN,
    formatter: TimestampFormatter,
): String = buildString {
    append(LocationReportFormat.absoluteTime(report.timestampSeconds, formatter))
    report.altitude?.let {
        append(LocationReportFormat.SEPARATOR)
        append(LocationReportFormat.altitude(it, system, symbols))
    }
}

@Composable
private fun recencyText(timestampSeconds: Double, nowEpochSeconds: Double): String =
    recencyLabel(LocationReportFormat.relativeRecency(nowEpochSeconds - timestampSeconds))

@Composable
private fun recencyLabel(recency: Recency): String {
    val resource = when (recency.unit) {
        RecencyUnit.SECONDS -> Res.string.remotenodes_remote_nodes_status_seconds_ago
        RecencyUnit.MINUTES -> Res.string.remotenodes_remote_nodes_status_minutes_ago
        RecencyUnit.HOURS -> Res.string.remotenodes_remote_nodes_status_hours_ago
        RecencyUnit.DAYS -> Res.string.remotenodes_remote_nodes_history_days_ago
    }
    return formattedStringResource(resource, recency.value)
}
