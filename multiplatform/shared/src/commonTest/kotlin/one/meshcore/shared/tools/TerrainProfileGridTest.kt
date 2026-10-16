package one.meshcore.shared.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TerrainProfileGridTest {
    @Test
    fun niceStepSnapsToIosValues() {
        assertEquals(25.0, terrainNiceStep(range = 100.0, targetDivisions = 4))
        assertEquals(200.0, terrainNiceStep(range = 1000.0, targetDivisions = 5))
        assertEquals(1.0, terrainNiceStep(range = 0.0, targetDivisions = 4))
        assertEquals(1.0, terrainNiceStep(range = 100.0, targetDivisions = 0))
    }

    @Test
    fun tickValuesStartOnANiceBoundary() {
        assertEquals(listOf(100.0, 125.0, 150.0), terrainTickValues(lower = 95.0, upper = 150.0, step = 25.0))
        assertTrue(terrainTickValues(lower = 0.0, upper = 10.0, step = 0.0).isEmpty())
    }

    @Test
    fun elevationLabelsAddMetersOnTheLastTick() {
        assertEquals("120", terrainElevationTickLabel(120.4, isLast = false))
        assertEquals("120 m", terrainElevationTickLabel(120.4, isLast = true))
    }

    @Test
    fun distanceLabelsUseKmAndWholeSteps() {
        assertEquals("1", terrainDistanceTickLabel(meters = 1000.0, stepMeters = 1000.0, isLast = false))
        assertEquals("2 km", terrainDistanceTickLabel(meters = 2000.0, stepMeters = 1000.0, isLast = true))
        assertEquals("1.5", terrainDistanceTickLabel(meters = 1500.0, stepMeters = 500.0, isLast = false))
        assertEquals("1.5 km", terrainDistanceTickLabel(meters = 1500.0, stepMeters = 500.0, isLast = true))
    }
}
