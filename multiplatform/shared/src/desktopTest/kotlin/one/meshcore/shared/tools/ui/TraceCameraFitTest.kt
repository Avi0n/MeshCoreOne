package one.meshcore.shared.tools.ui

import one.meshcore.shared.map.LatLng
import one.meshcore.shared.map.MapPoint
import one.meshcore.shared.map.PinStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The trace map's camera-fit policy, the Kotlin counterpart of the iOS `performInitialCentering`
 * split between `centerOnPathRepeaters` and `centerOnAllRepeaters`.
 */
class TraceCameraFitTest {
    @Test
    fun aPathIsFramedRatherThanEveryRepeaterOnTheMap() {
        // The path sits in a corner of a map whose other repeaters are hundreds of kilometres away.
        // Fitting all of them would leave the path a few pixels wide, which is why iOS prefers the
        // path's own repeaters whenever one exists.
        val path = listOf(LatLng(52.50, 13.40), LatLng(52.52, 13.42))
        val region = traceCameraFit(path, farAwayRepeaters() + path.map(::repeaterAt))

        assertEquals(52.51, region!!.center.latitude, TOLERANCE, "centered on the path, not the map")
        assertTrue(region.spanLatDeg < FAR_AWAY_SPAN_DEG, "span covers the path, not every repeater")
    }

    @Test
    fun withNoPathYetEveryLocatedRepeaterIsFramed() {
        val repeaters = farAwayRepeaters()

        val region = traceCameraFit(hopCoordinates = emptyList(), points = repeaters)

        assertEquals(0.0, region!!.center.latitude, TOLERANCE)
        assertTrue(region.spanLatDeg >= FAR_AWAY_SPAN_DEG, "span reaches both distant repeaters")
    }

    @Test
    fun nothingLocatedFramesNothing() {
        assertNull(traceCameraFit(hopCoordinates = emptyList(), points = emptyList()))
    }

    private fun farAwayRepeaters(): List<MapPoint> = listOf(LatLng(-5.0, -5.0), LatLng(5.0, 5.0)).map(::repeaterAt)

    private fun repeaterAt(coordinate: LatLng): MapPoint = MapPoint(
        id = "repeater-${coordinate.latitude}-${coordinate.longitude}",
        coordinate = coordinate,
        pinStyle = PinStyle.CONTACT_REPEATER,
        label = null,
        isClusterable = true,
    )

    private companion object {
        const val TOLERANCE = 0.001
        const val FAR_AWAY_SPAN_DEG = 10.0
    }
}
