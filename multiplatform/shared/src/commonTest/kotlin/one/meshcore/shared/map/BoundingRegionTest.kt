package one.meshcore.shared.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The bounding-region framing helper: empty→null, center at the midpoint, minimum and clamped spans. */
class BoundingRegionTest {
    @Test
    fun emptyListReturnsNull() {
        assertNull(emptyList<LatLng>().boundingRegion())
    }

    @Test
    fun framesAllCoordinatesWithCenterAtTheMidpointAndPaddedSpan() {
        val region = assertNotNull(
            listOf(LatLng(10.0, 20.0), LatLng(20.0, 40.0)).boundingRegion(),
        )

        assertEquals(15.0, region.center.latitude)
        assertEquals(30.0, region.center.longitude)
        // Extent 10°/20° × the 1.5 default padding.
        assertEquals(15.0, region.spanLatDeg)
        assertEquals(30.0, region.spanLonDeg)
    }

    @Test
    fun singlePointGetsTheMinimumSpan() {
        val region = assertNotNull(listOf(LatLng(45.0, 90.0)).boundingRegion())

        assertEquals(45.0, region.center.latitude)
        assertEquals(90.0, region.center.longitude)
        assertEquals(0.01, region.spanLatDeg)
        assertEquals(0.01, region.spanLonDeg)
    }

    @Test
    fun latitudeSpanIsClampedSoCornersStayInRange() {
        // Near the pole the padded span (5° × 1.5 = 7.5) exceeds (90 - |centerLat|) * 2 = 7.0, so it is
        // clamped to 7.0 and the top corner lands exactly on 90°.
        val region = assertNotNull(
            listOf(LatLng(84.0, 0.0), LatLng(89.0, 0.0)).boundingRegion(),
        )

        assertEquals(86.5, region.center.latitude)
        assertEquals(7.0, region.spanLatDeg)
        assertTrue(region.center.latitude + region.spanLatDeg / 2 <= 90.0)
    }

    @Test
    fun centerAllIgnoresADroppedPin() {
        val contact = MapPoint(
            id = "c",
            coordinate = LatLng(10.0, 20.0),
            pinStyle = PinStyle.CONTACT_CHAT,
            label = "A",
            isClusterable = true,
        )
        val dropped = MapPoint(
            id = "pin",
            coordinate = LatLng(80.0, 170.0),
            pinStyle = PinStyle.DROPPED_PIN,
            label = null,
            isClusterable = false,
        )
        assertTrue(hasPinsForCenterAll(listOf(contact), emptyList()))
        assertFalse(hasPinsForCenterAll(emptyList(), emptyList()))
        val region = assertNotNull(centerAllRegion(listOf(contact), emptyList()))
        assertEquals(10.0, region.center.latitude)
        assertEquals(20.0, region.center.longitude)
        // The dropped pin is drawn, but it must not be in this framing set.
        assertTrue(dropped.coordinate.latitude != region.center.latitude)
    }
}
