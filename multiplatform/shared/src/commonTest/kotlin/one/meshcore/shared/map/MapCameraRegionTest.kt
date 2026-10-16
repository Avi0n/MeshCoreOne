package one.meshcore.shared.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The camera-apply trust boundary (spec §1.7). An out-of-range or non-finite region must be rejected
 * ([validatedBounds] returns null) rather than reaching the native camera; a valid region's corner
 * latitudes are clamped to ±90 while longitude is left to wrap.
 */
class MapCameraRegionTest {
    @Test
    fun validatedBoundsReturnsCornersForAValidRegion() {
        val region = MapCameraRegion(LatLng(37.0, -122.0), spanLatDeg = 2.0, spanLonDeg = 4.0)

        val bounds = assertNotNull(region.validatedBounds())

        assertEquals(36.0, bounds.sw.latitude)
        assertEquals(-124.0, bounds.sw.longitude)
        assertEquals(38.0, bounds.ne.latitude)
        assertEquals(-120.0, bounds.ne.longitude)
    }

    @Test
    fun validatedBoundsRejectsAnInvalidCenter() {
        assertNull(MapCameraRegion(LatLng(91.0, 20.0), 1.0, 1.0).validatedBounds())
        assertNull(MapCameraRegion(LatLng(10.0, 200.0), 1.0, 1.0).validatedBounds())
        assertNull(MapCameraRegion(LatLng(Double.NaN, 20.0), 1.0, 1.0).validatedBounds())
    }

    @Test
    fun validatedBoundsRejectsANonFiniteSpan() {
        assertNull(MapCameraRegion(LatLng(10.0, 20.0), Double.NaN, 1.0).validatedBounds())
        assertNull(MapCameraRegion(LatLng(10.0, 20.0), 1.0, Double.POSITIVE_INFINITY).validatedBounds())
    }

    @Test
    fun validatedBoundsClampsCornerLatitudesToNinety() {
        // A wide span near a pole would push a corner past ±90; the latitude is clamped, longitude is not.
        val region = MapCameraRegion(LatLng(89.0, 10.0), spanLatDeg = 6.0, spanLonDeg = 20.0)

        val bounds = assertNotNull(region.validatedBounds())

        assertEquals(90.0, bounds.ne.latitude)
        assertEquals(86.0, bounds.sw.latitude)
        assertEquals(20.0, bounds.ne.longitude)
        assertEquals(0.0, bounds.sw.longitude)
    }

    @Test
    fun userLocateCameraRegionUsesTheLosSpan() {
        val region = userLocateCameraRegion(LatLng(37.33, -122.03))

        assertEquals(37.33, region.center.latitude)
        assertEquals(-122.03, region.center.longitude)
        assertEquals(USER_LOCATE_SPAN_DEG, region.spanLatDeg)
        assertEquals(USER_LOCATE_SPAN_DEG, region.spanLonDeg)
    }

    @Test
    fun userLocateCameraRegionAcceptsACustomSpan() {
        val region = userLocateCameraRegion(LatLng(51.5, -0.12), spanDeg = CENTER_ON_USER_SPAN_DEG)

        assertEquals(CENTER_ON_USER_SPAN_DEG, region.spanLatDeg)
        assertEquals(CENTER_ON_USER_SPAN_DEG, region.spanLonDeg)
    }
}
