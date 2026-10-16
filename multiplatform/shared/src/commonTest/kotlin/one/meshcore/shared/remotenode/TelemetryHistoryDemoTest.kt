package one.meshcore.shared.remotenode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Demo fixture for offline telemetry history charts. */
class TelemetryHistoryDemoTest {
    @Test
    fun samplesCoverRadioSensorsAndNeighbors() {
        val now = 1_700_000_000.0
        val samples = TelemetryHistoryDemo.sampleSnapshots(testPublicKey(), now)
        assertEquals(8, samples.size)
        assertTrue(TelemetryOverview.hasRadioData(samples))
        assertTrue(TelemetryOverview.hasTelemetryData(samples))
        assertTrue(TelemetryOverview.hasNeighborData(samples))
        val radio = RadioMetricCharts.singleSeries(samples)
        assertTrue(radio.any { it.key == RadioMetricCharts.KEY_BATTERY })
        assertTrue(radio.any { it.key == RadioMetricCharts.KEY_SNR })
        assertTrue(RadioMetricCharts.packetOverlays(samples).isNotEmpty())
        assertTrue(ChannelGroup.groups(samples).isNotEmpty())
        assertEquals(2, neighborSnrCharts(samples).size)
        assertEquals(now, samples.last().timestamp)
    }
}
