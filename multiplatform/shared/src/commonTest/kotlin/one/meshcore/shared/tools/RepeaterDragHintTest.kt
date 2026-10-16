package one.meshcore.shared.tools

import one.meshcore.shared.rf.ProfileSample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RepeaterDragHintTest {
    @Test
    fun firstOnPathRepeaterShowsTheHint() {
        assertTrue(shouldShowRepeaterDragHint(hadRepeater = false, repeaterOnPath = true, hasSeenHint = false))
    }

    @Test
    fun aLaterPathTweakDoesNotReshow() {
        assertFalse(shouldShowRepeaterDragHint(hadRepeater = true, repeaterOnPath = true, hasSeenHint = false))
    }

    @Test
    fun aSeenHintNeverReturns() {
        assertFalse(shouldShowRepeaterDragHint(hadRepeater = false, repeaterOnPath = true, hasSeenHint = true))
    }

    @Test
    fun anOffPathRepeaterDoesNotShow() {
        assertFalse(shouldShowRepeaterDragHint(hadRepeater = false, repeaterOnPath = false, hasSeenHint = false))
    }

    @Test
    fun chartXMapsToTheIosDragClamp() {
        assertEquals(PATH_FRACTION_MIN, pathFractionFromChartX(0f, CHART_WIDTH))
        assertEquals(PATH_FRACTION_MAX, pathFractionFromChartX(CHART_WIDTH, CHART_WIDTH))
        assertEquals(0.5, pathFractionFromChartX(CHART_WIDTH / 2f, CHART_WIDTH), FRACTION_TOLERANCE)
    }

    @Test
    fun interpolatesLosHeightAlongTheProfile() {
        val start = ProfileSample(x = 0.0, yTerrain = 0.0, yLos = 10.0, fresnelRadius = 1.0)
        val end = ProfileSample(x = 100.0, yTerrain = 20.0, yLos = 30.0, fresnelRadius = 3.0)
        val mid = interpolateProfileAtFraction(listOf(start, end), 0.5)
        assertNotNull(mid)
        assertEquals(50.0, mid.x, FRACTION_TOLERANCE)
        assertEquals(10.0, mid.yTerrain, FRACTION_TOLERANCE)
        assertEquals(20.0, mid.yLos, FRACTION_TOLERANCE)
        assertEquals(2.0, mid.fresnelRadius, FRACTION_TOLERANCE)
    }

    @Test
    fun endpointSamplesAreTheFirstAndLast() {
        val start = ProfileSample(x = 0.0, yTerrain = 0.0, yLos = 10.0, fresnelRadius = 1.0)
        val mid = ProfileSample(x = 50.0, yTerrain = 10.0, yLos = 20.0, fresnelRadius = 2.0)
        val end = ProfileSample(x = 100.0, yTerrain = 20.0, yLos = 30.0, fresnelRadius = 3.0)
        val ends = terrainProfileEndpointSamples(listOf(start, mid, end))
        assertNotNull(ends)
        assertEquals(start, ends.first)
        assertEquals(end, ends.second)
    }

    @Test
    fun aSingleSampleHasNoEndpoints() {
        val only = ProfileSample(x = 0.0, yTerrain = 0.0, yLos = 10.0, fresnelRadius = 1.0)
        assertEquals(null, terrainProfileEndpointSamples(listOf(only)))
    }

    private companion object {
        const val CHART_WIDTH = 100f
        const val FRACTION_TOLERANCE = 1e-6
    }
}
