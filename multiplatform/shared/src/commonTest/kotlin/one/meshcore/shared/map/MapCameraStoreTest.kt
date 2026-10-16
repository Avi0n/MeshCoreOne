package one.meshcore.shared.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The camera-persistence trust boundary. Ports the iOS `MapCameraStoreTests`: a round-trip, rejection
 * of a region that encode produced from invalid input, and the parameterized malformed-input cases.
 */
class MapCameraStoreTest {
    @Test
    fun encodeThenDecodeRoundTripsARegion() {
        val region = MapCameraRegion(
            center = LatLng(latitude = 37.7749, longitude = -122.4194),
            spanLatDeg = 0.25,
            spanLonDeg = 0.5,
        )

        val decoded = assertNotNull(MapCameraStore.decode(MapCameraStore.encode(region)))

        assertEquals(37.7749, decoded.center.latitude)
        assertEquals(-122.4194, decoded.center.longitude)
        assertEquals(0.25, decoded.spanLatDeg)
        assertEquals(0.5, decoded.spanLonDeg)
    }

    @Test
    fun decodeRejectsARegionThatEncodeProducedFromInvalidInput() {
        val nonFinite = MapCameraRegion(LatLng(10.0, 20.0), spanLatDeg = Double.NaN, spanLonDeg = 1.0)
        val zeroSpan = MapCameraRegion(LatLng(10.0, 20.0), spanLatDeg = 0.0, spanLonDeg = 1.0)

        assertNull(MapCameraStore.decode(MapCameraStore.encode(nonFinite)))
        assertNull(MapCameraStore.decode(MapCameraStore.encode(zeroSpan)))
    }

    @Test
    fun decodeRejectsMalformedInput() {
        val malformed = listOf(
            "",
            "1,2,3",
            "1,2,3,4,5",
            "a,b,c,d",
            "10,20,nan,1",
            "91,20,1,1",
            "10,20,0,1",
            "10,20,1,-1",
        )
        for (input in malformed) {
            assertNull(MapCameraStore.decode(input), "expected null for \"$input\"")
        }
    }
}
