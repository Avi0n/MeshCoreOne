package one.meshcore.shared.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GeoDistanceTest {
    @Test
    fun totalDistanceIsNullUntilTwoPoints() {
        assertNull(totalDistanceMeters(emptyList()))
        assertNull(totalDistanceMeters(listOf(LatLng(0.0, 0.0))))
    }

    @Test
    fun totalDistanceSumsConsecutiveLegs() {
        val a = LatLng(0.0, 0.0)
        val b = LatLng(0.0, 1.0)
        val c = LatLng(0.0, 2.0)
        val oneLeg = haversineMeters(a, b)
        val twoLegs = totalDistanceMeters(listOf(a, b, c))
        assertTrue(twoLegs != null)
        assertEquals(oneLeg + haversineMeters(b, c), twoLegs!!, absoluteTolerance = 0.001)
    }
}
