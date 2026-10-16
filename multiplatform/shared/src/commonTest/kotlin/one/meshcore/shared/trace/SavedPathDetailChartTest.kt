package one.meshcore.shared.trace

import one.meshcore.shared.backup.TracePathRunDto
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The saved-path detail chart places runs by date. */
class SavedPathDetailChartTest {
    @Test
    fun unevenRunDatesKeepTheGap() {
        assertEquals(listOf(0.0, 0.25, 1.0), savedPathDetailXFractions(listOf(0.0, 25.0, 100.0)))
    }

    @Test
    fun identicalDatesAreNotStretched() {
        assertEquals(listOf(0.0, 0.0, 0.0), savedPathDetailXFractions(listOf(5.0, 5.0, 5.0)))
    }

    @Test
    fun anEmptySeriesHasNoFractions() {
        assertEquals(emptyList(), savedPathDetailXFractions(emptyList()))
        assertTrue(savedPathDetailPoints(emptyList(), width = 40.0, height = 20.0).isEmpty())
    }

    @Test
    fun everyRunIsPlottedPastTheSparklineCap() {
        val dates = listOf(0.0) + List(10) { 1.0 } + listOf(100.0)
        val fractions = savedPathDetailXFractions(dates)
        assertEquals(12, fractions.size)
        assertEquals(0.01, fractions[1], absoluteTolerance = 1e-12)
        assertEquals(1.0, fractions.last())
    }

    @Test
    fun aLargerRoundTripSitsHigherAndDatesSetX() {
        val runs = listOf(run(0.0, 10), run(50.0, 30), run(100.0, 20))
        val points = savedPathDetailPoints(runs, width = 100.0, height = 40.0)
        assertEquals(0.0, points[0].x)
        assertEquals(50.0, points[1].x)
        assertEquals(100.0, points[2].x)
        assertEquals(40.0, points[0].y)
        assertEquals(0.0, points[1].y)
        assertEquals(20.0, points[2].y)
    }

    @Test
    fun equalRoundTripsStayAtMidHeight() {
        val runs = listOf(run(0.0, 40), run(25.0, 40), run(100.0, 40))
        val points = savedPathDetailPoints(runs, width = 100.0, height = 40.0)
        assertEquals(listOf(0.0, 25.0, 100.0), points.map { it.x })
        assertTrue(points.all { it.y == 20.0 })
    }

    @Test
    fun dateTicksSpanTheRunsAndKeepRoundTripExtrema() {
        assertEquals(listOf(0.0, 1.0 / 3.0, 2.0 / 3.0, 1.0), savedPathDetailXTickFractions(listOf(0.0, 90.0)))
        val ticks = savedPathDetailRttTicks(listOf(10, 30, 20))
        assertEquals(10.0, ticks.first())
        assertEquals(30.0, ticks.last())
    }

    @Test
    fun oneInstantHasASingleDateTick() {
        assertEquals(listOf(0.0), savedPathDetailXTickFractions(listOf(5.0, 5.0)))
        assertEquals(listOf(12.0), savedPathDetailRttTicks(listOf(12, 12)))
    }

    @Test
    fun aLargeEpochKeepsAQuarterGap() {
        val start = 1_700_000_000.0
        assertEquals(
            listOf(0.0, 0.25, 1.0),
            savedPathDetailXFractions(listOf(start, start + 25.0, start + 100.0)),
        )
    }

    private fun run(date: Double, rtt: Int) = TracePathRunDto(
        id = Uuid.random(),
        date = date,
        success = true,
        roundTripMs = rtt,
    )
}
