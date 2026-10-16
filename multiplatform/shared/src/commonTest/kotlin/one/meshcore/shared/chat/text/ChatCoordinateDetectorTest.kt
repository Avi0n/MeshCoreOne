package one.meshcore.shared.chat.text

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ports the iOS `ChatCoordinateDetectorTests` — the sole coordinate validity gate for the map path. */
class ChatCoordinateDetectorTest {
    @Test fun `a valid decimal pair is detected`() {
        val coord = ChatCoordinateDetector.firstCoordinate("Meet at 37.334900, -122.009020 tonight")
        assertTrue(coord != null)
        assertTrue(abs(coord!!.latitude - 37.3349) < TOLERANCE)
        assertTrue(abs(coord.longitude - -122.00902) < TOLERANCE)
    }

    @Test fun `an integer pair is not detected`() {
        assertNull(ChatCoordinateDetector.firstCoordinate("ratio is 3, 4 today"))
    }

    @Test fun `an out-of-range pair is rejected by the clamp not the regex`() {
        assertNull(ChatCoordinateDetector.firstCoordinate("bad 200.0, 400.0 coord"))
    }

    @Test fun `a three-number decimal list is treated as a list`() {
        assertNull(ChatCoordinateDetector.firstCoordinate("values 1.0, 2.0, 3.0 here"))
    }

    @Test fun `a version-like string is not detected`() {
        assertNull(ChatCoordinateDetector.firstCoordinate("v1.2, 3.4 release"))
    }

    @Test fun `firstCoordinate returns null when there is none`() {
        assertNull(ChatCoordinateDetector.firstCoordinate("no coordinates here"))
    }

    @Test fun `firstCoordinate returns the first of several`() {
        val coord = ChatCoordinateDetector.firstCoordinate("A 10.0, 20.0 and B 30.0, 40.0")
        assertEquals(10.0, coord?.latitude)
        assertEquals(20.0, coord?.longitude)
    }

    @Test fun `matches returns every valid coordinate in document order`() {
        val matches = ChatCoordinateDetector.matches("A 10.0, 20.0 and B 30.0, 40.0")
        assertEquals(2, matches.size)
        assertEquals(10.0, matches.first().coordinate.latitude)
        assertEquals(30.0, matches.last().coordinate.latitude)
    }

    @Test fun `a coordinate ending a sentence is detected with the period excluded`() {
        val coord = ChatCoordinateDetector.firstCoordinate("Meet at 37.7749, -122.4194.")
        assertEquals(37.7749, coord?.latitude)
    }

    private companion object {
        const val TOLERANCE = 0.000001
    }
}
