package one.meshcore.shared.map

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class RegionGeocodeTest {
    @Test
    fun aGeocodeResultIsReturned() = runTest {
        val found = GeocodeResult("US", "Colorado", null)
        var cancelled = 0

        val result = reverseGeocodeOrNull(
            geocode = { found },
            cancelGeocode = { cancelled += 1 },
        )

        assertSame(found, result)
        assertEquals(0, cancelled)
    }

    @Test
    fun aGeocodeExceptionReturnsNull() = runTest {
        var cancelled = 0

        val result = reverseGeocodeOrNull(
            geocode = { error("geocoder offline") },
            cancelGeocode = { cancelled += 1 },
        )

        assertNull(result)
        assertEquals(0, cancelled)
    }

    @Test
    fun aGeocodeErrorReturnsNull() = runTest {
        var cancelled = 0

        val result = reverseGeocodeOrNull(
            geocode = { throw RegionGeocodeProbeFault() },
            cancelGeocode = { cancelled += 1 },
        )

        assertNull(result)
        assertEquals(0, cancelled)
    }

    @Test
    fun aCancelledGeocodeCancelsAndRethrows() = runTest {
        val cancelled = CancellationException("picker dismissed")
        var cancelCount = 0

        val thrown = assertFailsWith<CancellationException> {
            reverseGeocodeOrNull(
                geocode = { throw cancelled },
                cancelGeocode = { cancelCount += 1 },
            )
        }

        assertSame(cancelled, thrown)
        assertEquals(1, cancelCount)
    }
}

private class RegionGeocodeProbeFault : Error("probe")
