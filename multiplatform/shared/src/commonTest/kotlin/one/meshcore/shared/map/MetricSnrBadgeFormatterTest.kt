package one.meshcore.shared.map

import one.meshcore.shared.remotenode.MeasurementSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MetricSnrBadgeFormatterTest {
    private val formatter = MetricSnrBadgeFormatter()

    @Test
    fun formatsMetersAndKilometers() {
        assertEquals("500 m", formatter.formatDistance(500.0))
        assertTrue(formatter.formatDistance(1500.0).endsWith(" km"))
        assertEquals("1.5 km", formatter.formatDistance(1500.0))
    }

    @Test
    fun formatsSnrOneDecimal() {
        assertEquals("-3.0", formatter.formatSnr(-3.0))
        assertEquals("12.5", formatter.formatSnr(12.5))
    }

    @Test
    fun snrBadgeTextComposesDistanceAndSnr() {
        val text = snrBadgeText(1000.0, -3.0, formatter)
        assertEquals("1.0 km, -3.0 dB", text)
    }

    @Test
    fun formatsImperialRoadDistance() {
        val imperial = MetricSnrBadgeFormatter(system = MeasurementSystem.IMPERIAL)
        assertEquals("7.7 mi", imperial.formatDistance(12_400.0))
        assertEquals("23 ft", imperial.formatDistance(7.0))
        assertEquals("0.1 mi", imperial.formatDistance(160.9344))
        assertEquals("525 ft", imperial.formatDistance(160.0))
    }
}
