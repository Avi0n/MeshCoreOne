package one.meshcore.shared.trace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Comparison-row arithmetic and sparkline geometry, mirroring Swift `ComparisonRowView` and `MiniSparkline`. */
class TraceComparisonTest {
    @Test
    fun comparisonReportsSlowerRun() {
        val c = rttComparison(currentMs = 150, previousMs = 100)
        assertEquals(50, c.diffMs)
        assertEquals(50.0, c.percentChange)
        assertEquals(RttTrend.INCREASED, c.trend)
    }

    @Test
    fun comparisonReportsFasterRun() {
        val c = rttComparison(currentMs = 80, previousMs = 100)
        assertEquals(-20, c.diffMs)
        assertEquals(-20.0, c.percentChange)
        assertEquals(RttTrend.DECREASED, c.trend)
    }

    @Test
    fun comparisonHandlesZeroPreviousAndEqualRuns() {
        assertEquals(0.0, rttComparison(currentMs = 100, previousMs = 0).percentChange) // no divide-by-zero
        assertEquals(RttTrend.UNCHANGED, rttComparison(currentMs = 100, previousMs = 100).trend)
    }

    @Test
    fun sparklineInvertsYAndSpansTheWidth() {
        val points = sparklinePoints(values = listOf(0, 5, 10), width = 100.0, height = 50.0)
        assertEquals(3, points.size)
        assertEquals(0.0, points.first().x)
        assertEquals(100.0, points.last().x)
        // Smallest value sits at the bottom (y = height), largest at the top (y = 0).
        assertEquals(50.0, points.first().y)
        assertEquals(0.0, points.last().y)
        assertEquals(25.0, points[1].y)
    }

    @Test
    fun sparklineDrawsFlatLineForConstantValues() {
        val points = sparklinePoints(values = listOf(7, 7, 7), width = 60.0, height = 20.0)
        assertTrue(points.all { it.y == 10.0 }) // height / 2
    }

    @Test
    fun sparklineDrawsFullWidthMidLineWhenEmpty() {
        val points = sparklinePoints(values = emptyList(), width = 50.0, height = 16.0)
        assertEquals(listOf(SparklinePoint(0.0, 8.0), SparklinePoint(50.0, 8.0)), points)
    }

    @Test
    fun sparklineDrawsFullWidthMidLineForOneSample() {
        val points = sparklinePoints(values = listOf(7), width = 60.0, height = 20.0)
        assertEquals(listOf(SparklinePoint(0.0, 10.0), SparklinePoint(60.0, 10.0)), points)
    }

    @Test
    fun sparklineLockedDomainKeepsASingleSampleOnTheScale() {
        val points = sparklinePoints(
            values = listOf(-100),
            width = 10.0,
            height = 70.0,
            yMin = -130.0,
            yMax = -60.0,
        )
        assertEquals(1, points.size)
        assertEquals(0.0, points.single().x)
        assertEquals(40.0, points.single().y)
    }

    @Test
    fun sparklineFromDoublesPreservesOrderAndYInvert() {
        val points = sparklinePointsFromDoubles(listOf(0.0, 2.5, 5.0), width = 100.0, height = 40.0)
        assertEquals(3, points.size)
        assertEquals(0.0, points.first().x)
        assertEquals(100.0, points.last().x)
        assertEquals(40.0, points.first().y)
        assertEquals(0.0, points.last().y)
    }

    @Test
    fun sparklineFixedDomainPlacesNoiseFloorOnTheIosScale() {
        // −60 dBm is the top of the iOS −130…−60 window; −130 is the bottom.
        val points = sparklinePoints(
            values = listOf(-130, -95, -60),
            width = 70.0,
            height = 70.0,
            yMin = -130.0,
            yMax = -60.0,
        )
        assertEquals(70.0, points.first().y)
        assertEquals(0.0, points.last().y)
        assertEquals(35.0, points[1].y)
        val quiet = sparklinePoints(
            values = listOf(-100, -100),
            width = 10.0,
            height = 70.0,
            yMin = -130.0,
            yMax = -60.0,
        )
        assertEquals(40.0, quiet.first().y, "a flat −100 dBm must not autoscale to mid-line")
    }
}
