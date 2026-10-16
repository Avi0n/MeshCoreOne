@file:OptIn(ExperimentalMaterial3Api::class)

package one.meshcore.shared.remotenode.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import one.meshcore.shared.backup.NodeStatusSnapshotDto
import one.meshcore.shared.battery.OCVPreset
import one.meshcore.shared.contact.UserLocation
import one.meshcore.shared.map.LocationService
import one.meshcore.shared.map.MapStyleSelection
import one.meshcore.shared.map.ui.LocalMapColorSchemePreference
import one.meshcore.shared.map.ui.LocalMapOffline
import one.meshcore.shared.map.ui.MapDisplayChrome
import one.meshcore.shared.remotenode.ChannelGroup
import one.meshcore.shared.remotenode.HistoryTimeRange
import one.meshcore.shared.remotenode.LPPDisplay
import one.meshcore.shared.remotenode.LocalizedLengthSymbols
import one.meshcore.shared.remotenode.MeasurementSystem
import one.meshcore.shared.remotenode.RadioMetricCharts
import one.meshcore.shared.remotenode.RadioMetricSeries
import one.meshcore.shared.remotenode.RadioPacketOverlay
import one.meshcore.shared.remotenode.TelemetryOverview
import one.meshcore.shared.remotenode.neighborSnrCharts
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.remotenodes_remote_nodes_history_all
import one.meshcore.shared.resources.remotenodes_remote_nodes_history_battery
import one.meshcore.shared.resources.remotenodes_remote_nodes_history_direct
import one.meshcore.shared.resources.remotenodes_remote_nodes_history_duplicates
import one.meshcore.shared.resources.remotenodes_remote_nodes_history_flood
import one.meshcore.shared.resources.remotenodes_remote_nodes_history_month
import one.meshcore.shared.resources.remotenodes_remote_nodes_history_neighbors_section
import one.meshcore.shared.resources.remotenodes_remote_nodes_history_no_snapshots_message
import one.meshcore.shared.resources.remotenodes_remote_nodes_history_noise_floor
import one.meshcore.shared.resources.remotenodes_remote_nodes_history_overview_title
import one.meshcore.shared.resources.remotenodes_remote_nodes_history_packets
import one.meshcore.shared.resources.remotenodes_remote_nodes_history_packets_received
import one.meshcore.shared.resources.remotenodes_remote_nodes_history_packets_sent
import one.meshcore.shared.resources.remotenodes_remote_nodes_history_radio_section
import one.meshcore.shared.resources.remotenodes_remote_nodes_history_receive_errors
import one.meshcore.shared.resources.remotenodes_remote_nodes_history_retention_notice
import one.meshcore.shared.resources.remotenodes_remote_nodes_history_rssi
import one.meshcore.shared.resources.remotenodes_remote_nodes_history_section_not_captured
import one.meshcore.shared.resources.remotenodes_remote_nodes_history_sensors_section
import one.meshcore.shared.resources.remotenodes_remote_nodes_history_snr
import one.meshcore.shared.resources.remotenodes_remote_nodes_history_three_months
import one.meshcore.shared.resources.remotenodes_remote_nodes_history_title
import one.meshcore.shared.resources.remotenodes_remote_nodes_history_week
import one.meshcore.shared.resources.remotenodes_remote_nodes_room_status_posts_pushed
import one.meshcore.shared.resources.remotenodes_remote_nodes_room_status_posts_received
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_no_telemetry_data
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_sensor_mcu_temperature
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_telemetry
import one.meshcore.shared.theme.AppColorSchemePreference
import one.meshcore.shared.ui.EMPTY_STATE_ICON_SIZE
import one.meshcore.shared.ui.PreferenceHubFooter
import one.meshcore.shared.ui.PreferenceHubSectionGap
import one.meshcore.shared.ui.formatTemplate
import one.meshcore.shared.ui.icons.AppIcons
import org.jetbrains.compose.resources.stringResource

private val SECTION_SPACING = 12.dp
private val CONTENT_PADDING = 16.dp

/** Which history drill-down to render. Mirrors iOS `NodeStatusRoute` plus contact Saved History. */
enum class NodeHistoryKind {
    /** Radio metrics, packet overlays, retention. iOS `NodeStatusHistoryView`. */
    STATUS,

    /** Sensor charts and latest-fix location preview. iOS `TelemetryHistoryView`. */
    TELEMETRY,

    /** Combined radio / sensors / neighbors / full path. iOS `TelemetryHistoryOverviewView`. */
    OVERVIEW,
}

/**
 * History charts for a node. [kind] selects Status History, Telemetry History, or the combined
 * contact Saved History overview. Mirrors iOS `NodeStatusHistoryView` / `TelemetryHistoryView` /
 * `TelemetryHistoryOverviewView` using pure builders ([RadioMetricCharts], [ChannelGroup],
 * [neighborSnrCharts]). Chat Saved History passes [showNeighbors] false so Neighbors never appear.
 */
@Composable
fun TelemetryOverviewScreen(
    snapshots: List<NodeStatusSnapshotDto>,
    nowEpochSeconds: Double,
    modifier: Modifier = Modifier,
    ocvArray: List<Int> = OCVPreset.LI_ION.ocvArray,
    neighborNameResolver: (ByteArray) -> String? = { null },
    /** Basemap appearance for the embedded location-history map, from Settings → Maps. */
    mapColorSchemePreference: AppColorSchemePreference = LocalMapColorSchemePreference.current,
    mapStyle: MapStyleSelection = MapStyleSelection.STANDARD,
    isOffline: Boolean = LocalMapOffline.current,
    locationService: LocationService? = null,
    userLocation: UserLocation? = null,
    mapChrome: MapDisplayChrome? = null,
    kind: NodeHistoryKind = NodeHistoryKind.OVERVIEW,
    /** Repeaters and rooms keep Neighbors; chat Saved History hides it (iOS `showNeighbors`). */
    showNeighbors: Boolean = true,
) {
    var range by remember { mutableStateOf(HistoryTimeRange.DEFAULT) }
    val filtered = remember(snapshots, range, nowEpochSeconds) {
        range.filter(snapshots, nowEpochSeconds)
    }
    val measurementSystem = LPPDisplay.currentMeasurementSystem()
    val lengthSymbols = LPPDisplay.currentLengthSymbols()
    val radioSeries = remember(filtered, ocvArray) { RadioMetricCharts.singleSeries(filtered, ocvArray) }
    val packetOverlays = remember(filtered) { RadioMetricCharts.packetOverlays(filtered) }
    val sensorGroups = remember(filtered, measurementSystem) {
        ChannelGroup.groups(filtered, measurementSystem)
    }
    val neighborCharts = remember(filtered, neighborNameResolver) {
        neighborSnrCharts(filtered, neighborNameResolver)
    }
    val hasRadio = remember(filtered) { TelemetryOverview.hasRadioData(filtered) }
    val hasSensors = remember(filtered) { TelemetryOverview.hasTelemetryData(filtered) }
    val hasNeighbors = remember(filtered) { TelemetryOverview.hasNeighborData(filtered) }

    if (snapshots.isEmpty()) {
        Column(
            modifier
                .fillMaxSize()
                .padding(CONTENT_PADDING),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
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
                text = emptyTitle(kind),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(Res.string.remotenodes_remote_nodes_history_no_snapshots_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(CONTENT_PADDING),
        verticalArrangement = Arrangement.spacedBy(SECTION_SPACING),
    ) {
        TimeRangeChips(range = range, onSelect = { range = it })

        when (kind) {
            NodeHistoryKind.STATUS -> {
                RadioSectionContent(radioSeries, packetOverlays)
                PreferenceHubFooter(
                    stringResource(Res.string.remotenodes_remote_nodes_history_retention_notice),
                    applyHorizontalPadding = false,
                )
            }
            NodeHistoryKind.TELEMETRY -> {
                SensorsSectionContent(sensorGroups, ocvArray, measurementSystem, lengthSymbols)
                LocationHistorySection(
                    snapshots = filtered,
                    nowEpochSeconds = nowEpochSeconds,
                    colorSchemePreference = mapColorSchemePreference,
                    mapStyle = mapStyle,
                    isOffline = isOffline,
                    locationService = locationService,
                    userLocation = userLocation,
                    mapChrome = mapChrome,
                    showsFullPath = false,
                    modifier = Modifier.padding(top = PreferenceHubSectionGap),
                )
            }
            NodeHistoryKind.OVERVIEW -> {
                if (hasRadio) {
                    HistorySectionCaption(
                        stringResource(Res.string.remotenodes_remote_nodes_history_radio_section),
                    )
                    RadioSectionContent(radioSeries, packetOverlays)
                }

                if (hasSensors) {
                    HistorySectionCaption(
                        stringResource(Res.string.remotenodes_remote_nodes_history_sensors_section),
                    )
                    SensorsSectionContent(sensorGroups, ocvArray, measurementSystem, lengthSymbols)
                } else {
                    NotCapturedNote(
                        stringResource(Res.string.remotenodes_remote_nodes_history_sensors_section),
                    )
                }

                if (TelemetryOverview.showsNeighborCharts(showNeighbors, hasNeighbors)) {
                    HistorySectionCaption(
                        stringResource(Res.string.remotenodes_remote_nodes_history_neighbors_section),
                    )
                    neighborCharts.forEach { chart ->
                        MetricChartView(
                            title = chart.name,
                            points = chart.points,
                            unit = RadioMetricCharts.UNIT_DB,
                            lineColor = TelemetrySeriesRole.PRIMARY.color(),
                        )
                    }
                } else if (TelemetryOverview.showsNeighborsNotCaptured(showNeighbors, hasNeighbors)) {
                    NotCapturedNote(
                        stringResource(Res.string.remotenodes_remote_nodes_history_neighbors_section),
                    )
                }

                LocationHistorySection(
                    snapshots = filtered,
                    nowEpochSeconds = nowEpochSeconds,
                    colorSchemePreference = mapColorSchemePreference,
                    mapStyle = mapStyle,
                    isOffline = isOffline,
                    locationService = locationService,
                    userLocation = userLocation,
                    mapChrome = mapChrome,
                    showsFullPath = true,
                    modifier = Modifier.padding(top = PreferenceHubSectionGap),
                )

                PreferenceHubFooter(
                    stringResource(Res.string.remotenodes_remote_nodes_history_retention_notice),
                    applyHorizontalPadding = false,
                )
            }
        }
    }
}

@Composable
internal fun TimeRangeChips(range: HistoryTimeRange, onSelect: (HistoryTimeRange) -> Unit) {
    val options = HistoryTimeRange.entries
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = range == option,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                label = { Text(timeRangeLabel(option), maxLines = 1) },
            )
        }
    }
}

@Composable
internal fun timeRangeLabel(range: HistoryTimeRange): String = when (range) {
    HistoryTimeRange.WEEK -> stringResource(Res.string.remotenodes_remote_nodes_history_week)
    HistoryTimeRange.MONTH -> stringResource(Res.string.remotenodes_remote_nodes_history_month)
    HistoryTimeRange.THREE_MONTHS -> stringResource(Res.string.remotenodes_remote_nodes_history_three_months)
    HistoryTimeRange.ALL -> stringResource(Res.string.remotenodes_remote_nodes_history_all)
}

@Composable
private fun emptyTitle(kind: NodeHistoryKind): String = when (kind) {
    NodeHistoryKind.TELEMETRY -> stringResource(Res.string.remotenodes_remote_nodes_status_telemetry)
    NodeHistoryKind.STATUS -> stringResource(Res.string.remotenodes_remote_nodes_history_title)
    NodeHistoryKind.OVERVIEW -> stringResource(Res.string.remotenodes_remote_nodes_history_overview_title)
}

@Composable
private fun RadioSectionContent(series: List<RadioMetricSeries>, overlays: List<RadioPacketOverlay>) {
    val directLabel = stringResource(Res.string.remotenodes_remote_nodes_history_direct)
    val floodLabel = stringResource(Res.string.remotenodes_remote_nodes_history_flood)
    series.forEach { item ->
        MetricChartView(
            title = radioSeriesTitle(item.key),
            points = item.points,
            unit = item.unit,
            yAxisDomain = item.yAxisDomain,
            lineColor = radioMetricSeriesTint(item.key),
        )
    }
    if (overlays.isNotEmpty()) {
        HistorySectionCaption(
            stringResource(Res.string.remotenodes_remote_nodes_history_packets),
        )
        overlays.forEach { overlay ->
            MetricChartView(
                title = radioSeriesTitle(overlay.key),
                points = overlay.direct,
                unit = RadioMetricCharts.UNIT_COUNT,
                secondaryPoints = overlay.flood,
                primaryLabel = directLabel,
                secondaryLabel = floodLabel,
                lineColor = TELEMETRY_PACKET_DIRECT_ROLE.color(),
                secondaryLineColor = TELEMETRY_PACKET_FLOOD_ROLE.color(),
                yAxisDomain = overlay.yAxisDomain,
            )
        }
    }
}

@Composable
private fun SensorsSectionContent(
    groups: List<ChannelGroup>,
    ocvArray: List<Int>,
    system: MeasurementSystem,
    symbols: LocalizedLengthSymbols,
) {
    if (groups.isEmpty()) {
        Text(
            text = stringResource(Res.string.remotenodes_remote_nodes_status_no_telemetry_data),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    groups.forEach { channel ->
        val charts: @Composable () -> Unit = {
            channel.charts.forEach { chart ->
                MetricChartView(
                    title = if (chart.isMcuTemperature) {
                        stringResource(Res.string.remotenodes_remote_nodes_status_sensor_mcu_temperature)
                    } else {
                        chart.sensorType?.let { stringResource(LPPDisplay.sensorTypeResource(it)) } ?: chart.title
                    },
                    points = chart.points,
                    unit = chart.sensorType?.localizedUnitSymbol(system, symbols).orEmpty(),
                    yAxisDomain = RadioMetricCharts.sensorYAxisDomain(chart.sensorType, chart.points, ocvArray),
                    lineColor = chart.sensorType?.chartTint() ?: TelemetrySeriesRole.PRIMARY_CONTAINER.color(),
                )
            }
        }
        if (groups.size > 1) {
            HistorySectionCaption(
                stringResource(Res.string.remotenodes_remote_nodes_status_telemetry) +
                    ", ${channel.channel}",
            )
        }
        charts()
    }
}

@Composable
private fun HistorySectionCaption(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun NotCapturedNote(sectionName: String) {
    val template = stringResource(Res.string.remotenodes_remote_nodes_history_section_not_captured)
    Text(
        text = formatTemplate(template, listOf(sectionName)),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun radioSeriesTitle(key: String): String = when (key) {
    RadioMetricCharts.KEY_BATTERY -> stringResource(Res.string.remotenodes_remote_nodes_history_battery)
    RadioMetricCharts.KEY_SNR -> stringResource(Res.string.remotenodes_remote_nodes_history_snr)
    RadioMetricCharts.KEY_RSSI -> stringResource(Res.string.remotenodes_remote_nodes_history_rssi)
    RadioMetricCharts.KEY_NOISE_FLOOR -> stringResource(Res.string.remotenodes_remote_nodes_history_noise_floor)
    RadioMetricCharts.KEY_PACKETS_SENT -> stringResource(Res.string.remotenodes_remote_nodes_history_packets_sent)
    RadioMetricCharts.KEY_PACKETS_RECEIVED ->
        stringResource(Res.string.remotenodes_remote_nodes_history_packets_received)
    RadioMetricCharts.KEY_DUPLICATES -> stringResource(Res.string.remotenodes_remote_nodes_history_duplicates)
    RadioMetricCharts.KEY_RECEIVE_ERRORS -> stringResource(Res.string.remotenodes_remote_nodes_history_receive_errors)
    RadioMetricCharts.KEY_POSTS_RECEIVED ->
        stringResource(Res.string.remotenodes_remote_nodes_room_status_posts_received)
    RadioMetricCharts.KEY_POSTS_PUSHED ->
        stringResource(Res.string.remotenodes_remote_nodes_room_status_posts_pushed)
    else -> key
}

/** iOS `TelemetryHistoryOverviewView` empty: `chart.line.uptrend.xyaxis`. */
internal val TELEMETRY_HISTORY_EMPTY_ICON get() = AppIcons.ShowChart
