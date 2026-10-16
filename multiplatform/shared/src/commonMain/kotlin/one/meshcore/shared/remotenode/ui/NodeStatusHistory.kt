package one.meshcore.shared.remotenode.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import one.meshcore.shared.backup.NodeStatusSnapshotDto
import one.meshcore.shared.chat.ui.LocalTimestampFormatter
import one.meshcore.shared.chat.ui.chatMonthDayLabel
import one.meshcore.shared.contact.UserLocation
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.map.LocationService
import one.meshcore.shared.map.MapPoint
import one.meshcore.shared.map.MapStyleSelection
import one.meshcore.shared.map.PinStyle
import one.meshcore.shared.map.boundingRegion
import one.meshcore.shared.map.ui.FramedStatusMap
import one.meshcore.shared.map.ui.rememberNetworkOnlyStyleGate
import one.meshcore.shared.remotenode.NodeLocationFix
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.remotenodes_remote_nodes_history_title
import one.meshcore.shared.resources.remotenodes_remote_nodes_history_vs_date
import one.meshcore.shared.resources.remotenodes_remote_nodes_history_vs_hours_ago
import one.meshcore.shared.resources.remotenodes_remote_nodes_history_vs_minutes_ago
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_accessibility_view_location_on_map
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_location_map_title
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_telemetry
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_view_on_map
import one.meshcore.shared.theme.AppColorSchemePreference
import one.meshcore.shared.ui.FullScreenOverlay
import one.meshcore.shared.ui.formattedStringResource
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.preferenceHubCardAction
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun previousVisitLabel(previousTimestamp: Double, nowEpochSeconds: Double): String =
    when (val caption = NodeStatusFormat.previousVisitCaption(previousTimestamp, nowEpochSeconds)) {
        is PreviousVisitCaption.MinutesAgo -> formattedStringResource(
            Res.string.remotenodes_remote_nodes_history_vs_minutes_ago,
            caption.minutes,
        )
        is PreviousVisitCaption.HoursAgo -> formattedStringResource(
            Res.string.remotenodes_remote_nodes_history_vs_hours_ago,
            caption.hours,
        )
        is PreviousVisitCaption.CalendarDate -> formattedStringResource(
            Res.string.remotenodes_remote_nodes_history_vs_date,
            chatMonthDayLabel(caption.epochSeconds, formatter = LocalTimestampFormatter.current),
        )
    }

@Composable
internal fun StatusHistoryLink(onClick: () -> Unit) {
    ListItem(
        headlineContent = {
            Text(stringResource(Res.string.remotenodes_remote_nodes_history_title))
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NodeHistoryDialog(
    snapshots: List<NodeStatusSnapshotDto>,
    nowEpochSeconds: Double,
    ocvArray: List<Int>,
    neighborNameResolver: (ByteArray) -> String?,
    mapColorSchemePreference: AppColorSchemePreference,
    mapStyle: MapStyleSelection,
    isOffline: Boolean,
    locationService: LocationService?,
    userLocation: UserLocation?,
    mapChrome: one.meshcore.shared.map.ui.MapDisplayChrome?,
    onDismiss: () -> Unit,
    kind: NodeHistoryKind,
) {
    val title = when (kind) {
        NodeHistoryKind.TELEMETRY -> stringResource(Res.string.remotenodes_remote_nodes_status_telemetry)
        NodeHistoryKind.STATUS, NodeHistoryKind.OVERVIEW ->
            stringResource(Res.string.remotenodes_remote_nodes_history_title)
    }
    FullScreenOverlay(onDismiss = onDismiss, title = title) { innerPadding ->
        TelemetryOverviewScreen(
            snapshots = snapshots,
            nowEpochSeconds = nowEpochSeconds,
            ocvArray = ocvArray,
            neighborNameResolver = neighborNameResolver,
            mapColorSchemePreference = mapColorSchemePreference,
            mapStyle = mapStyle,
            isOffline = isOffline,
            locationService = locationService,
            userLocation = userLocation,
            mapChrome = mapChrome,
            kind = kind,
            modifier = Modifier.padding(innerPadding),
        )
    }
}

/** Status history and telemetry history for one node, with the same snapshots and map. */
@Composable
internal fun NodeHistoryDialogs(
    showStatusHistory: Boolean,
    showTelemetryHistory: Boolean,
    onDismissStatus: () -> Unit,
    onDismissTelemetry: () -> Unit,
    snapshots: List<NodeStatusSnapshotDto>,
    nowEpochSeconds: Double,
    ocvArray: List<Int>,
    neighborNameResolver: (ByteArray) -> String?,
    mapColorSchemePreference: AppColorSchemePreference,
    mapStyle: MapStyleSelection,
    isOffline: Boolean,
    locationService: LocationService?,
    userLocation: UserLocation?,
    mapChrome: one.meshcore.shared.map.ui.MapDisplayChrome?,
) {
    @Composable
    fun Shown(show: Boolean, kind: NodeHistoryKind, onDismiss: () -> Unit) {
        if (!show) return
        NodeHistoryDialog(
            snapshots = snapshots,
            nowEpochSeconds = nowEpochSeconds,
            ocvArray = ocvArray,
            neighborNameResolver = neighborNameResolver,
            mapColorSchemePreference = mapColorSchemePreference,
            mapStyle = mapStyle,
            isOffline = isOffline,
            locationService = locationService,
            userLocation = userLocation,
            mapChrome = mapChrome,
            onDismiss = onDismiss,
            kind = kind,
        )
    }
    Shown(showStatusHistory, NodeHistoryKind.STATUS, onDismissStatus)
    Shown(showTelemetryHistory, NodeHistoryKind.TELEMETRY, onDismissTelemetry)
}

/** The "View on Map" affordance for a live telemetry fix (iOS `SharedNodeStatusViews` location link). */
@Composable
fun ViewLocationOnMapButton(onClick: () -> Unit) {
    StatusMapButton(
        text = stringResource(Res.string.remotenodes_remote_nodes_status_view_on_map),
        spokenLabel = stringResource(Res.string.remotenodes_remote_nodes_status_accessibility_view_location_on_map),
        onClick = onClick,
    )
}

/** Full-width map action under a status card. */
@Composable
internal fun StatusMapButton(text: String, spokenLabel: String, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier
            .preferenceHubCardAction()
            .semantics { contentDescription = spokenLabel },
    ) {
        Icon(
            imageVector = AppIcons.Map,
            contentDescription = null,
            modifier = Modifier.size(SPINNER_SIZE).padding(end = 4.dp),
        )
        Text(text)
    }
}

/** One-pin map for the fix the latest telemetry reply carried. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeLocationFixSheet(
    fix: NodeLocationFix,
    label: String,
    colorSchemePreference: AppColorSchemePreference,
    isOffline: Boolean,
    locationService: LocationService?,
    userLocation: UserLocation?,
    onDismiss: () -> Unit,
    mapStyle: MapStyleSelection = MapStyleSelection.STANDARD,
    mapChrome: one.meshcore.shared.map.ui.MapDisplayChrome? = null,
) {
    val chrome = mapChrome
    val (styleDisabled, styleDisabledReason) = rememberNetworkOnlyStyleGate(isNetworkAvailable = !isOffline)
    val coordinate = LatLng(fix.latitude, fix.longitude)
    val region = remember(coordinate) { listOf(coordinate).boundingRegion() }
    val points = remember(coordinate, label) {
        listOf(
            MapPoint(
                id = "telemetry-location-fix",
                coordinate = coordinate,
                pinStyle = PinStyle.LOCATION_FIX_LATEST,
                label = label,
                isClusterable = false,
            ),
        )
    }
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
                points = points,
                lines = emptyList(),
                region = region,
                cameraResetKey = region,
                colorSchemePreference = colorSchemePreference,
                isOffline = isOffline,
                locationService = locationService,
                userLocation = userLocation,
                chrome = chrome,
                fallbackStyle = mapStyle,
                styleDisabled = styleDisabled,
                styleDisabledReason = styleDisabledReason,
            )
        }
    }
}

private val SPINNER_SIZE = 16.dp

internal val NodeStatusState.needsHistory: Boolean
    get() = statusExpanded || telemetryExpanded || neighborsExpanded
