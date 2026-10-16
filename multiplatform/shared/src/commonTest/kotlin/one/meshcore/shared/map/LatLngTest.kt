package one.meshcore.shared.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LatLngTest {
    @Test
    @Suppress("MagicNumber")
    fun wrappedWorldCopiesProduceValidCoordinates() {
        val eastCopy = LatLng(0.01392, 260.20025).normalizedWorldLongitude()
        val westCopy = LatLng(0.01392, -260.20025).normalizedWorldLongitude()

        assertEquals(-99.79975, eastCopy.longitude, 0.000001)
        assertEquals(99.79975, westCopy.longitude, 0.000001)
        assertTrue(eastCopy.isValid)
        assertTrue(westCopy.isValid)
        assertEquals(LatLng(47.36019, -122.05052), LatLng(47.36019, -122.05052).normalizedWorldLongitude())
    }
}
