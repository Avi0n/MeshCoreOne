package one.meshcore.shared.map

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HasUsableCoordinateTest {
    @Test
    fun unsetAndOutOfRangeCoordinatesAreNotUsable() {
        assertFalse(hasUsableCoordinate(0.0, 0.0))
        assertFalse(hasUsableCoordinate(91.0, 0.0))
        assertFalse(hasUsableCoordinate(0.0, 181.0))
        assertFalse(hasUsableCoordinate(Double.NaN, 1.0))
        assertTrue(hasUsableCoordinate(37.33, -122.03))
        assertTrue(hasUsableCoordinate(0.0, 1.0))
    }
}
