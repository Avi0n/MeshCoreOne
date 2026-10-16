package one.meshcore.shared.tools

import one.meshcore.shared.map.LatLng
import one.meshcore.shared.rf.ClearanceStatus
import one.meshcore.shared.rf.ElevationSample
import one.meshcore.shared.rf.GeoCoordinate
import one.meshcore.shared.rf.RelayPathAnalysisResult
import one.meshcore.shared.rf.SegmentAnalysisResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RepeaterVisualizationPathFractionTest {
    @Test
    fun onPathUsesTheStoredFraction() {
        val repeater = LosRepeaterPoint(
            coordinate = ORIGIN,
            isOnPath = true,
            pathFraction = 0.3,
        )
        assertEquals(0.3, repeaterVisualizationPathFraction(repeater, emptyList(), emptyList()))
    }

    @Test
    fun offPathUsesArOverTotalDistance() {
        val repeater = LosRepeaterPoint(coordinate = ORIGIN, isOnPath = false)
        val ar = listOf(sample(0.0), sample(AR_METERS))
        val rb = listOf(sample(AR_METERS), sample(TOTAL_METERS))
        assertEquals(
            AR_METERS / TOTAL_METERS,
            repeaterVisualizationPathFraction(repeater, ar, rb),
        )
    }

    @Test
    fun offPathWithoutProfilesIsNull() {
        val repeater = LosRepeaterPoint(coordinate = ORIGIN, isOnPath = false)
        assertNull(repeaterVisualizationPathFraction(repeater, emptyList(), emptyList()))
    }

    @Test
    fun missingRepeaterIsNull() {
        assertNull(repeaterVisualizationPathFraction(null, listOf(sample(1.0)), listOf(sample(2.0))))
    }

    @Test
    fun offPathJunctionUsesRelayArDistance() {
        val repeater = LosRepeaterPoint(coordinate = ORIGIN, isOnPath = false)
        val status = LosAnalysisStatus.RelayResult(relayResult(arMeters = AR_METERS, rbMeters = RB_METERS))
        assertEquals(AR_METERS, offPathSegmentArDistanceMeters(repeater, status))
    }

    @Test
    fun onPathHasNoJunction() {
        val repeater = LosRepeaterPoint(coordinate = ORIGIN, isOnPath = true)
        val status = LosAnalysisStatus.RelayResult(relayResult(arMeters = AR_METERS, rbMeters = RB_METERS))
        assertNull(offPathSegmentArDistanceMeters(repeater, status))
    }

    private fun sample(distance: Double) = ElevationSample(
        coordinate = GeoCoordinate(0.0, 0.0),
        elevation = 0.0,
        distanceFromAMeters = distance,
    )

    private fun relayResult(arMeters: Double, rbMeters: Double) = RelayPathAnalysisResult(
        segmentAR = SegmentAnalysisResult("A", "R", ClearanceStatus.Clear, arMeters, 100.0),
        segmentRB = SegmentAnalysisResult("R", "B", ClearanceStatus.Clear, rbMeters, 100.0),
    )

    private companion object {
        val ORIGIN = LatLng(0.0, 0.0)
        const val AR_METERS = 400.0
        const val RB_METERS = 600.0
        const val TOTAL_METERS = 1_000.0
    }
}
