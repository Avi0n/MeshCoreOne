package one.meshcore.shared.remotenode.ui

import one.meshcore.shared.remotenode.LPPSensorType
import one.meshcore.shared.remotenode.RadioMetricCharts
import kotlin.test.Test
import kotlin.test.assertEquals

class TelemetryChartChromeTest {
    @Test
    fun radioSeriesUseThemeRoles() {
        assertEquals(TelemetrySeriesRole.TERTIARY, radioMetricSeriesRole(RadioMetricCharts.KEY_BATTERY))
        assertEquals(TelemetrySeriesRole.PRIMARY, radioMetricSeriesRole(RadioMetricCharts.KEY_SNR))
        assertEquals(TelemetrySeriesRole.SECONDARY, radioMetricSeriesRole(RadioMetricCharts.KEY_RSSI))
        assertEquals(
            TelemetrySeriesRole.PRIMARY_CONTAINER,
            radioMetricSeriesRole(RadioMetricCharts.KEY_NOISE_FLOOR),
        )
        assertEquals(TelemetrySeriesRole.ERROR, radioMetricSeriesRole(RadioMetricCharts.KEY_RECEIVE_ERRORS))
        assertEquals(TelemetrySeriesRole.SECONDARY, radioMetricSeriesRole(RadioMetricCharts.KEY_POSTS_RECEIVED))
        assertEquals(
            TelemetrySeriesRole.PRIMARY_CONTAINER,
            radioMetricSeriesRole(RadioMetricCharts.KEY_POSTS_PUSHED),
        )
        assertEquals(TelemetrySeriesRole.PRIMARY, TELEMETRY_PACKET_DIRECT_ROLE)
        assertEquals(TelemetrySeriesRole.TERTIARY, TELEMETRY_PACKET_FLOOD_ROLE)
    }

    @Test
    fun sensorTypesUseThemeRoles() {
        assertEquals(TelemetrySeriesRole.TERTIARY, LPPSensorType.VOLTAGE.chartRole())
        assertEquals(TelemetrySeriesRole.ERROR, LPPSensorType.TEMPERATURE.chartRole())
        assertEquals(TelemetrySeriesRole.TERTIARY, LPPSensorType.HUMIDITY.chartRole())
        assertEquals(TelemetrySeriesRole.SECONDARY, LPPSensorType.BAROMETER.chartRole())
        assertEquals(TelemetrySeriesRole.TERTIARY, LPPSensorType.ILLUMINANCE.chartRole())
        assertEquals(TelemetrySeriesRole.TERTIARY, LPPSensorType.CURRENT.chartRole())
        assertEquals(TelemetrySeriesRole.SECONDARY, LPPSensorType.POWER.chartRole())
        assertEquals(TelemetrySeriesRole.PRIMARY, LPPSensorType.FREQUENCY.chartRole())
        assertEquals(TelemetrySeriesRole.PRIMARY, LPPSensorType.ALTITUDE.chartRole())
        assertEquals(TelemetrySeriesRole.PRIMARY, LPPSensorType.DISTANCE.chartRole())
        assertEquals(TelemetrySeriesRole.TERTIARY, LPPSensorType.ENERGY.chartRole())
        assertEquals(TelemetrySeriesRole.PRIMARY_CONTAINER, LPPSensorType.DIRECTION.chartRole())
        assertEquals(TelemetrySeriesRole.PRIMARY_CONTAINER, LPPSensorType.PERCENTAGE.chartRole())
        assertEquals(TelemetrySeriesRole.PRIMARY_CONTAINER, LPPSensorType.GPS.chartRole())
    }
}
