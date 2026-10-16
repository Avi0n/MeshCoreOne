package one.meshcore.shared.tools

import androidx.compose.ui.unit.dp
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.tools.ui.noiseFloorChartLabelStride
import one.meshcore.shared.tools.ui.noiseFloorChartMinuteDomain
import one.meshcore.shared.tools.ui.noiseFloorChartSeries
import one.meshcore.shared.tools.ui.noiseFloorChartTimeAxis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NoiseFloorChartTest {
    @Test
    fun completeMinuteLabelsThinUsingLibrarySpacingIncludingHalfMinuteDomains() {
        listOf(0.0..5.0, 0.5..5.5).forEach { domain ->
            val axis = noiseFloorChartTimeAxis(domain)
            val ticks = axis.computeTickValues(148.dp).majorTickValues
            assertEquals(2, noiseFloorChartLabelStride(axis, ticks, requiredWidth = 22f, plotWidth = 100))
            assertEquals(1, noiseFloorChartLabelStride(axis, ticks, requiredWidth = 22f, plotWidth = 200))
            assertEquals(ticks, axis.computeTickValues(148.dp).majorTickValues)
        }
    }

    @Test
    fun libraryAxisKeepsMinuteGridAcrossNarrowAndWideRollingWindows() {
        listOf(100, 148, 320, 800).forEach { width ->
            assertEquals(
                listOf(0.0, 1.0, 2.0, 3.0, 4.0, 5.0),
                noiseFloorChartTimeAxis(0.0..5.0).computeTickValues(width.dp).majorTickValues,
            )
            assertEquals(
                listOf(1.0, 2.0, 3.0, 4.0, 5.0),
                noiseFloorChartTimeAxis(0.5..5.5).computeTickValues(width.dp).majorTickValues,
            )
        }
    }

    @Test
    fun initialLatencyAndHiddenGapsKeepTimestampSpacing() {
        val readings = listOf(reading(1000), reading(6000), reading(121_000))
        val points = noiseFloorChartSeries(readings, 1000)
        assertEquals(listOf(0.0, 5.0 / 60.0, 2.0), points.map { it.x })
        assertEquals(listOf(-100.0, -100.0, -100.0), points.map { it.y })
        assertEquals(0.0..5.0, noiseFloorChartMinuteDomain(readings, 1000))
    }

    @Test
    fun rollingWindowKeepsOutsideSamplesForLibraryClipping() {
        val readings = listOf(reading(0), reading(60_000), reading(360_000))
        assertEquals(60_000.0..360_000.0, noiseFloorChartDomain(readings, 0))
        assertEquals(1.0..6.0, noiseFloorChartMinuteDomain(readings, 0))
        assertEquals(listOf(0.0, 1.0, 6.0), noiseFloorChartSeries(readings, 0).map { it.x })
    }

    @Test
    fun rollingWindowKeepsItsAbsoluteElapsedMinuteOrigin() {
        val readings = listOf(reading(0), reading(330_000))
        assertEquals(0.5..5.5, noiseFloorChartMinuteDomain(readings, 0))
        assertEquals(listOf(0.0, 5.5), noiseFloorChartSeries(readings, 0).map { it.x })
    }

    @Test
    fun visibleClockRollbackRetainsArrivalOrderAndLastArrivalDomain() {
        val readings = listOf(reading(0), reading(420_000), reading(60_000), reading(180_000))
        assertEquals(listOf(0.0, 7.0, 1.0, 3.0), noiseFloorChartSeries(readings, 0).map { it.x })
        assertEquals(0.0..5.0, noiseFloorChartMinuteDomain(readings, 0))
    }

    @Test
    fun duplicateTimesAndOutOfRangeValuesRemainDistinctRawSamples() {
        val readings = listOf(reading(0, -140), reading(60_000, -110), reading(60_000, -50), reading(180_000, -100))
        val points = noiseFloorChartSeries(readings, 0)
        assertEquals(listOf(0.0, 1.0, 1.0, 3.0), points.map { it.x })
        assertEquals(listOf(-140.0, -110.0, -50.0, -100.0), points.map { it.y })
    }

    @Test
    fun emptyAndPreOriginSamplesDoNotFabricateEndpointsOrAnInvalidDomain() {
        assertTrue(noiseFloorChartSeries(emptyList(), 1000).isEmpty())
        assertEquals(0.0..5.0, noiseFloorChartMinuteDomain(emptyList(), 1000))
        val readings = listOf(reading(500), reading(400))
        assertTrue(noiseFloorChartSeries(readings, 1000).all { it.x < 0 })
        assertEquals(0.0..5.0, noiseFloorChartMinuteDomain(readings, 1000))
        assertEquals(1, noiseFloorChartSeries(listOf(reading(6000)), 1000).size)
    }

    private fun reading(time: Long, noise: Int = -100) = NoiseFloorReading(Uuid.random(), time, noise, -70, 5.0)
}
