package one.meshcore.shared.remotenode.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import one.meshcore.shared.remotenode.LPPDataPoint
import one.meshcore.shared.remotenode.LPPDisplay
import one.meshcore.shared.remotenode.groupedByChannel
import one.meshcore.shared.remotenode.hasMultipleChannels
import one.meshcore.shared.remotenode.isMcuTemperatureAt
import one.meshcore.shared.remotenode.voltageOcvPercent
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_channel
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_no_sensor_data
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_no_telemetry_data
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_request_timed_out
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_sensor_mcu_temperature
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_telemetry_timed_out
import one.meshcore.shared.ui.PreferenceHubCardPadding
import one.meshcore.shared.ui.PreferenceHubStatusRow
import one.meshcore.shared.ui.formattedStringResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun telemetrySectionErrorMessage(error: NodeSectionError): String = when (error) {
    NodeSectionError.Timeout ->
        stringResource(Res.string.remotenodes_remote_nodes_status_telemetry_timed_out)
    NodeSectionError.Failed ->
        stringResource(Res.string.remotenodes_remote_nodes_status_request_timed_out)
    is NodeSectionError.Message -> error.text
}

/** Loading, error, empty, and sensor rows for a status-card telemetry section. */
@Composable
internal fun LiveTelemetryReadout(
    isLoading: Boolean,
    loaded: Boolean,
    sectionError: NodeSectionError?,
    points: List<LPPDataPoint>,
    ocvValues: List<Int>,
) {
    when {
        isLoading && !loaded -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = TELEMETRY_SPINNER_VERTICAL_PADDING),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(TELEMETRY_SPINNER_SIZE))
            }
        }
        sectionError != null && !loaded -> PreferenceHubStatusRow(
            text = telemetrySectionErrorMessage(sectionError),
            color = MaterialTheme.colorScheme.error,
        )
        loaded && points.isEmpty() -> PreferenceHubStatusRow(
            stringResource(Res.string.remotenodes_remote_nodes_status_no_sensor_data),
        )
        loaded -> TelemetrySensorRows(points = points, ocvValues = ocvValues)
        else -> PreferenceHubStatusRow(
            stringResource(Res.string.remotenodes_remote_nodes_status_no_telemetry_data),
        )
    }
}

@Composable
internal fun TelemetrySensorRows(points: List<LPPDataPoint>, ocvValues: List<Int>) {
    if (points.hasMultipleChannels()) {
        points.groupedByChannel().forEach { (channel, channelPoints) ->
            TelemetryChannelHeading(channel)
            channelPoints.forEachIndexed { index, point ->
                TelemetrySensorRow(point, ocvValues, isMcuTemperatureAt(index, channelPoints))
            }
        }
    } else {
        points.forEachIndexed { index, point ->
            TelemetrySensorRow(point, ocvValues, isMcuTemperatureAt(index, points))
        }
    }
}

@Composable
private fun TelemetryChannelHeading(channel: Int) {
    Text(
        text = formattedStringResource(
            Res.string.remotenodes_remote_nodes_status_channel,
            channel,
        ),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = PreferenceHubCardPadding,
                end = PreferenceHubCardPadding,
                top = TELEMETRY_CHANNEL_HEADING_SPACING,
            ),
    )
}

@Composable
private fun TelemetrySensorRow(point: LPPDataPoint, ocvValues: List<Int>, isMcuTemperature: Boolean) {
    val voltagePercent = voltageOcvPercent(point, ocvValues)
    ListItem(
        headlineContent = {
            Text(
                text = if (isMcuTemperature) {
                    stringResource(Res.string.remotenodes_remote_nodes_status_sensor_mcu_temperature)
                } else {
                    LPPDisplay.typeLabel(point)
                },
                style = MaterialTheme.typography.bodyLarge,
            )
        },
        supportingContent = {
            Column {
                Text(
                    text = LPPDisplay.formattedValue(
                        point,
                        LPPDisplay.currentMeasurementSystem(),
                        LPPDisplay.currentLengthSymbols(),
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (voltagePercent != null) {
                    Text(
                        text = "$voltagePercent$PERCENT_UNIT",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

private const val PERCENT_UNIT = "%"
private val TELEMETRY_SPINNER_SIZE = 28.dp
private val TELEMETRY_SPINNER_VERTICAL_PADDING = 16.dp
private val TELEMETRY_CHANNEL_HEADING_SPACING = 6.dp
