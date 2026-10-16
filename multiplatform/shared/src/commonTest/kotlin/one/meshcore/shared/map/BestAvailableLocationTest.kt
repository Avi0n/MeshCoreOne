package one.meshcore.shared.map

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Locate falls back to the connected radio's own position when the phone has no fix. */
class BestAvailableLocationTest {
    private val phone = LatLng(37.33, -122.03)
    private val node = LatLng(51.5, -0.12)

    @Test
    fun phoneFixWins() {
        assertEquals(phone, bestAvailableLocation(phoneLocation = phone, nodeLocation = node))
    }

    @Test
    fun nodePositionCoversADeniedOrMissingFix() {
        assertEquals(node, bestAvailableLocation(phoneLocation = null, nodeLocation = node))
    }

    @Test
    fun neitherMeansNothingToCenterOn() {
        assertNull(bestAvailableLocation(phoneLocation = null, nodeLocation = null))
    }
}

class LocateTapCoordinateTest {
    private val phone = LatLng(37.33, -122.03)
    private val node = LatLng(51.5, -0.12)
    private val fresh = LatLng(1.0, 2.0)

    @Test
    fun lastKnownWinsWhenAFreshFixIsNotRequired() = runTest {
        val service = FakeLocationService { fresh }

        val result = locateTapCoordinate(known = phone, locationService = service, freshFixOnly = false)

        assertEquals(phone, result)
        assertEquals(0, service.requestCount)
    }

    @Test
    fun missingKnownRequestsAFix() = runTest {
        val service = FakeLocationService { fresh }

        val result = locateTapCoordinate(known = null, locationService = service, freshFixOnly = false)

        assertEquals(fresh, result)
        assertEquals(1, service.requestCount)
    }

    @Test
    fun losIgnoresRadioFallbackAndRequestsAFreshFix() = runTest {
        val service = FakeLocationService { fresh }

        val result = locateTapCoordinate(known = node, locationService = service, freshFixOnly = true)

        assertEquals(fresh, result)
        assertEquals(1, service.requestCount)
    }

    @Test
    fun losWithoutAServiceReturnsNothing() = runTest {
        assertNull(locateTapCoordinate(known = node, locationService = null, freshFixOnly = true))
    }
}
