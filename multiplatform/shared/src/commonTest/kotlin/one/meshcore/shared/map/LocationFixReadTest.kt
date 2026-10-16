package one.meshcore.shared.map

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class LocationFixReadTest {
    @Test
    fun aFixIsReturned() = runTest {
        val fix = LatLng(latitude = 39.7, longitude = -104.9)

        assertEquals(fix, locationFixOrNull { fix })
    }

    @Test
    fun aLocationFailureReturnsNull() = runTest {
        assertNull(locationFixOrNull { throw LocationServiceError.LocationTimeout })
    }

    @Test
    fun anotherFailurePropagates() = runTest {
        val rejected = IllegalStateException("provider disabled")

        val thrown = assertFailsWith<IllegalStateException> {
            locationFixOrNull { throw rejected }
        }

        assertSame(rejected, thrown)
    }

    @Test
    fun anErrorPropagates() = runTest {
        val fault = LocationFixProbeFault()

        val thrown = assertFailsWith<LocationFixProbeFault> {
            locationFixOrNull { throw fault }
        }

        assertSame(fault, thrown)
    }

    @Test
    fun aCancelledReadIsRethrown() = runTest {
        val cancelled = CancellationException("picker dismissed")

        val thrown = assertFailsWith<CancellationException> {
            locationFixOrNull { throw cancelled }
        }

        assertSame(cancelled, thrown)
    }
}

private class LocationFixProbeFault : Error("probe")
