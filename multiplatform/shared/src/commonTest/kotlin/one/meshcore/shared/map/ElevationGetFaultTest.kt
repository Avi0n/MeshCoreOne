package one.meshcore.shared.map

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * A cancelled elevation GET is rethrown. Any other transport failure, including a thrown error,
 * becomes a network error and is not retried.
 */
class ElevationGetFaultTest {
    @Test
    fun aCancelledElevationGetIsRethrown() = runTest {
        val stopped = CancellationException("stopped")
        var gets = 0
        val service = ElevationService(
            FakeGeoHttpClient {
                gets += 1
                throw stopped
            },
        )

        val thrown = assertFailsWith<CancellationException> { service.fetchElevation(POINT) }

        assertSame(stopped, thrown)
        assertEquals(1, gets)
    }

    @Test
    fun anElevationGetErrorIsANetworkError() = runTest {
        var gets = 0
        val service = ElevationService(
            FakeGeoHttpClient {
                gets += 1
                throw ElevationGetProbeError()
            },
        )

        val thrown = assertFailsWith<ElevationServiceError.NetworkError> { service.fetchElevation(POINT) }

        assertEquals("probe", thrown.reason)
        assertEquals(1, gets)
    }

    private companion object {
        val POINT = LatLng(37.0, -122.0)
    }
}

private class ElevationGetProbeError : Error("probe")
