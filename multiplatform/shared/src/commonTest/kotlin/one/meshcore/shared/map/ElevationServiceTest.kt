package one.meshcore.shared.map

import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Ports the iOS `ElevationServiceTests` pure suites (optimalSampleCount, sampleCoordinates, error
 * cases, distance integration) and adds HTTP-path coverage against canned responses through the
 * [GeoHttpClient] fake (the Swift tests never exercised the network path).
 */
class ElevationServiceTest {
    private val pointA = LatLng(37.7749, -122.4194)
    private val pointB = LatLng(37.8049, -122.3894)

    private fun service(responder: suspend (String) -> GeoHttpResponse) = ElevationService(FakeGeoHttpClient(responder))

    // MARK: optimalSampleCount

    @Test
    fun optimalSampleCountAppliesDistanceThresholds() {
        assertEquals(20, ElevationService.optimalSampleCount(0.0))
        assertEquals(20, ElevationService.optimalSampleCount(999.0))
        assertEquals(50, ElevationService.optimalSampleCount(1000.0))
        assertEquals(50, ElevationService.optimalSampleCount(4999.0))
        assertEquals(80, ElevationService.optimalSampleCount(5000.0))
        assertEquals(80, ElevationService.optimalSampleCount(19999.0))
        assertEquals(100, ElevationService.optimalSampleCount(20000.0))
        assertEquals(100, ElevationService.optimalSampleCount(1_000_000.0))
    }

    // MARK: sampleCoordinates

    @Test
    fun sampleCoordinatesKeepsEndpointsAndCount() {
        val samples = ElevationService.sampleCoordinates(pointA, pointB, 10)

        assertEquals(pointA, samples.first())
        assertEquals(pointB, samples.last())
        assertEquals(10, samples.size)
    }

    @Test
    fun sampleCoordinatesClampsCountTo2Through100() {
        assertEquals(2, ElevationService.sampleCoordinates(pointA, pointB, 1).size)
        assertEquals(2, ElevationService.sampleCoordinates(pointA, pointB, 0).size)
        assertEquals(100, ElevationService.sampleCoordinates(pointA, pointB, 150).size)
    }

    @Test
    fun sampleCoordinatesAreEvenlyDistributed() {
        val samples = ElevationService.sampleCoordinates(pointA, pointB, 5)
        val latStep = (pointB.latitude - pointA.latitude) / 4
        val lonStep = (pointB.longitude - pointA.longitude) / 4

        for (i in 0 until 5) {
            assertTrue(abs(samples[i].latitude - (pointA.latitude + i * latStep)) < TOLERANCE)
            assertTrue(abs(samples[i].longitude - (pointA.longitude + i * lonStep)) < TOLERANCE)
        }
    }

    // MARK: error cases

    @Test
    fun errorCasesCarryTheirDetail() {
        assertEquals("boom", (ElevationServiceError.NetworkError("boom")).reason)
        assertEquals("HTTP 500", (ElevationServiceError.ApiError("HTTP 500")).detail)
    }

    // MARK: distance integration

    @Test
    fun cumulativeSampleDistanceMatchesTheTotal() {
        val total = haversineMeters(pointA, pointB)
        val samples = ElevationService.sampleCoordinates(pointA, pointB, 5)

        var cumulative = 0.0
        for (i in 1 until samples.size) {
            cumulative += haversineMeters(samples[i - 1], samples[i])
        }

        assertTrue(abs(cumulative - total) < 1.0, "cumulative $cumulative should match total $total")
    }

    // MARK: HTTP path

    @Test
    fun fetchElevationsParsesTheResponseAndMeasuresDistanceFromStart() = runTest {
        val elevations = service { GeoHttpResponse(HTTP_OK, """{"elevation":[10.0,20.0]}""") }
            .fetchElevations(listOf(pointA, pointB))

        assertEquals(2, elevations.size)
        assertEquals(10.0, elevations[0].elevation)
        assertEquals(0.0, elevations[0].distanceFromAMeters)
        assertEquals(20.0, elevations[1].elevation)
        assertTrue(elevations[1].distanceFromAMeters > 0.0)
    }

    @Test
    fun elevationQueryUsesDotDecimalSeparators() = runTest {
        val client = FakeGeoHttpClient { GeoHttpResponse(HTTP_OK, """{"elevation":[10.0]}""") }
        ElevationService(client).fetchElevations(listOf(LatLng(37.7749, -122.4194)))

        val url = client.requestedUrls.single()
        assertTrue(url.contains("latitude=37.774900"), url)
        assertTrue(url.contains("longitude=-122.419400"), url)
    }

    @Test
    fun countMismatchIsAnInvalidResponse() = runTest {
        assertFailsWith<ElevationServiceError.InvalidResponse> {
            service { GeoHttpResponse(HTTP_OK, """{"elevation":[10.0]}""") }
                .fetchElevations(listOf(pointA, pointB))
        }
    }

    @Test
    fun malformedBodyIsAnInvalidResponse() = runTest {
        assertFailsWith<ElevationServiceError.InvalidResponse> {
            service { GeoHttpResponse(HTTP_OK, "not json") }.fetchElevation(pointA)
        }
    }

    @Test
    fun rateLimitIsRetriedThenSucceeds() = runTest {
        var calls = 0
        val client = FakeGeoHttpClient {
            calls++
            if (calls == 1) {
                GeoHttpResponse(HTTP_TOO_MANY_REQUESTS, "")
            } else {
                GeoHttpResponse(HTTP_OK, """{"elevation":[5.0]}""")
            }
        }

        val elevation = ElevationService(client).fetchElevation(pointA)

        assertEquals(5.0, elevation)
        assertEquals(2, client.requestedUrls.size)
    }

    @Test
    fun persistentRateLimitThrowsAfterRetries() = runTest {
        val client = FakeGeoHttpClient { GeoHttpResponse(HTTP_TOO_MANY_REQUESTS, "") }

        assertFailsWith<ElevationServiceError.RateLimited> {
            ElevationService(client).fetchElevation(pointA)
        }
        assertEquals(3, client.requestedUrls.size)
    }

    @Test
    fun transportFailureIsANetworkError() = runTest {
        assertFailsWith<ElevationServiceError.NetworkError> {
            service { throw IllegalStateException("socket closed") }.fetchElevation(pointA)
        }
    }

    @Test
    fun nonRateLimitStatusIsAnApiError() = runTest {
        assertFailsWith<ElevationServiceError.ApiError> {
            service { GeoHttpResponse(HTTP_SERVER_ERROR, "") }.fetchElevation(pointA)
        }
    }

    private companion object {
        const val TOLERANCE = 0.0001
        const val HTTP_OK = 200
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val HTTP_SERVER_ERROR = 500
    }
}
