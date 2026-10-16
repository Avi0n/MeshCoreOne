package one.meshcore.shared.remotenode.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import one.meshcore.shared.remotenode.LPPSensorType
import one.meshcore.shared.remotenode.RadioMetricCharts

/** Direct packet overlay (was iOS system blue). */
internal val TELEMETRY_PACKET_DIRECT_ROLE = TelemetrySeriesRole.PRIMARY

/** Flood packet overlay (was iOS system orange). */
internal val TELEMETRY_PACKET_FLOOD_ROLE = TelemetrySeriesRole.TERTIARY

internal fun radioMetricSeriesRole(key: String): TelemetrySeriesRole = when (key) {
    RadioMetricCharts.KEY_BATTERY -> TelemetrySeriesRole.TERTIARY
    RadioMetricCharts.KEY_SNR -> TelemetrySeriesRole.PRIMARY
    RadioMetricCharts.KEY_RSSI -> TelemetrySeriesRole.SECONDARY
    RadioMetricCharts.KEY_NOISE_FLOOR -> TelemetrySeriesRole.PRIMARY_CONTAINER
    RadioMetricCharts.KEY_RECEIVE_ERRORS -> TelemetrySeriesRole.ERROR
    RadioMetricCharts.KEY_POSTS_RECEIVED -> TelemetrySeriesRole.SECONDARY
    RadioMetricCharts.KEY_POSTS_PUSHED -> TelemetrySeriesRole.PRIMARY_CONTAINER
    else -> TelemetrySeriesRole.PRIMARY
}

internal fun LPPSensorType.chartRole(): TelemetrySeriesRole = when (this) {
    LPPSensorType.VOLTAGE, LPPSensorType.ENERGY -> TelemetrySeriesRole.TERTIARY
    LPPSensorType.TEMPERATURE -> TelemetrySeriesRole.ERROR
    LPPSensorType.HUMIDITY -> TelemetrySeriesRole.TERTIARY
    LPPSensorType.BAROMETER -> TelemetrySeriesRole.SECONDARY
    LPPSensorType.ILLUMINANCE -> TelemetrySeriesRole.TERTIARY
    LPPSensorType.CURRENT -> TelemetrySeriesRole.TERTIARY
    LPPSensorType.POWER -> TelemetrySeriesRole.SECONDARY
    LPPSensorType.FREQUENCY -> TelemetrySeriesRole.PRIMARY
    LPPSensorType.ALTITUDE, LPPSensorType.DISTANCE -> TelemetrySeriesRole.PRIMARY
    LPPSensorType.DIRECTION -> TelemetrySeriesRole.PRIMARY_CONTAINER
    else -> TelemetrySeriesRole.PRIMARY_CONTAINER
}

@Composable
internal fun TelemetrySeriesRole.color(): Color {
    val scheme = MaterialTheme.colorScheme
    return when (this) {
        TelemetrySeriesRole.PRIMARY -> scheme.primary
        TelemetrySeriesRole.SECONDARY -> scheme.secondary
        TelemetrySeriesRole.TERTIARY -> scheme.tertiary
        TelemetrySeriesRole.ERROR -> scheme.error
        TelemetrySeriesRole.PRIMARY_CONTAINER -> scheme.primaryContainer
    }
}

@Composable
internal fun radioMetricSeriesTint(key: String): Color = radioMetricSeriesRole(key).color()

@Composable
internal fun LPPSensorType.chartTint(): Color = chartRole().color()
