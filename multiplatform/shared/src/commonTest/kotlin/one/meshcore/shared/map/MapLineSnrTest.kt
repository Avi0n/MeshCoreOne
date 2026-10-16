package one.meshcore.shared.map

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The SNR palette: quality thresholds, the 5-tier→4-tier line-style collapse, the antimeridian
 * midpoint, and the badge-label composition (the comma separator and the space before the unit are
 * composed in code, not by the formatter).
 */
class MapLineSnrTest {
    @Test
    fun snrQualityAppliesTheDbThresholds() {
        assertEquals(SnrQuality.EXCELLENT, snrQuality(6.1))
        assertEquals(SnrQuality.GOOD, snrQuality(6.0))
        assertEquals(SnrQuality.GOOD, snrQuality(0.1))
        assertEquals(SnrQuality.FAIR, snrQuality(0.0))
        assertEquals(SnrQuality.FAIR, snrQuality(-5.9))
        assertEquals(SnrQuality.POOR, snrQuality(-6.0))
        assertEquals(SnrQuality.POOR, snrQuality(-20.0))
        assertEquals(SnrQuality.UNKNOWN, snrQuality(null))
    }

    @Test
    fun lineStyleForSnrCollapsesExcellentAndGoodToTraceGood() {
        assertEquals(LineStyle.TRACE_GOOD, lineStyleForSnr(10.0))
        assertEquals(LineStyle.TRACE_GOOD, lineStyleForSnr(3.0))
        assertEquals(LineStyle.TRACE_MEDIUM, lineStyleForSnr(-3.0))
        assertEquals(LineStyle.TRACE_WEAK, lineStyleForSnr(-10.0))
        assertEquals(LineStyle.TRACE_UNTRACED, lineStyleForSnr(null))
    }

    @Test
    fun midpointAveragesCoordinatesWithinAHemisphere() {
        val mid = snrBadgeMidpoint(LatLng(10.0, 20.0), LatLng(30.0, 60.0))

        assertEquals(20.0, mid.latitude)
        assertEquals(40.0, mid.longitude)
    }

    @Test
    fun midpointStraddlingTheAntimeridianLandsBetweenTheNodes() {
        // 170°E and -170°E are 20° apart across the antimeridian; the midpoint is 180°, not 0°.
        val mid = snrBadgeMidpoint(LatLng(0.0, 170.0), LatLng(0.0, -170.0))

        assertEquals(0.0, mid.latitude)
        assertEquals(180.0, mid.longitude)
    }

    @Test
    fun snrBadgeTextComposesSeparatorAndUnit() {
        val formatter = object : SnrBadgeFormatter {
            override fun formatDistance(meters: Double) = "1.2 km"
            override fun formatSnr(snr: Double) = "-3.0"
            override val snrUnit = "dB"
        }

        assertEquals("1.2 km, -3.0 dB", snrBadgeText(distanceMeters = 1200.0, snr = -3.0, formatter = formatter))
    }

    @Test
    fun snrBadgeBuildsANonClusterableBadgePinAtTheMidpoint() {
        val formatter = object : SnrBadgeFormatter {
            override fun formatDistance(meters: Double) = "d"
            override fun formatSnr(snr: Double) = "s"
            override val snrUnit = "dB"
        }

        val badge = snrBadge("id-1", LatLng(0.0, 10.0), LatLng(0.0, 30.0), snr = 1.0, formatter = formatter)

        assertEquals("id-1", badge.id)
        assertEquals(PinStyle.BADGE, badge.pinStyle)
        assertEquals(false, badge.isClusterable)
        assertEquals(LatLng(0.0, 20.0), badge.coordinate)
        assertEquals("d, s dB", badge.badgeText)
    }
}
