package one.meshcore.shared.map

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** An elevation reading along a path: the sampled coordinate, its elevation (m), and distance from the start. */
data class ElevationSample(val coordinate: LatLng, val elevation: Double, val distanceFromAMeters: Double)

/**
 * A failure fetching elevation. Typed cases resolved to a localized message in the UI layer (the iOS
 * enum routes these through `L10n.Localizable.Common.Error.*`). Mirrors iOS `ElevationServiceError`.
 */
sealed class ElevationServiceError : Exception() {
    /** Transport failure (no connection, DNS, TLS); carries the underlying message. */
    data class NetworkError(val reason: String) : ElevationServiceError()

    /** The response was unparseable or its element count did not match the request. */
    data object InvalidResponse : ElevationServiceError()

    /** The API returned a non-OK, non-rate-limit status; carries the detail (e.g. "HTTP 500"). */
    data class ApiError(val detail: String) : ElevationServiceError()

    /** No coordinates to fetch, or no elevation returned. */
    data object NoData : ElevationServiceError()

    /** Rate-limited (HTTP 429) after exhausting retries. */
    data object RateLimited : ElevationServiceError()
}

/**
 * Fetches terrain elevation from the Open-Meteo API. A pure HTTP service (no platform dependency)
 * behind the [GeoHttpClient] seam. Mirrors iOS `ElevationService`.
 */
class ElevationService(private val httpClient: GeoHttpClient, private val json: Json = DEFAULT_JSON) {
    /** Elevation (m above sea level) for a single coordinate. */
    suspend fun fetchElevation(coordinate: LatLng): Double =
        fetchElevations(listOf(coordinate)).firstOrNull()?.elevation
            ?: throw ElevationServiceError.NoData

    /**
     * Elevations for coordinates along a path, subsampled to at most [MAX_POINTS_PER_REQUEST] with the
     * endpoints preserved. Distances are measured from the first coordinate.
     */
    suspend fun fetchElevations(path: List<LatLng>): List<ElevationSample> {
        if (path.isEmpty()) throw ElevationServiceError.NoData

        val coordinates =
            if (path.size > MAX_POINTS_PER_REQUEST) subsample(path, MAX_POINTS_PER_REQUEST) else path

        // POSIX six-digit formatting: a comma-decimal locale would corrupt the comma-joined query.
        val latitudes = coordinates.joinToString(",") { formatFixed6(it.latitude) }
        val longitudes = coordinates.joinToString(",") { formatFixed6(it.longitude) }
        val url = "$API_ENDPOINT?latitude=$latitudes&longitude=$longitudes"

        val body = performRequest(url)
        val elevations = parseElevations(body)
        if (elevations.size != coordinates.size) throw ElevationServiceError.InvalidResponse

        val start = coordinates[0]
        return coordinates.mapIndexed { index, coordinate ->
            ElevationSample(
                coordinate = coordinate,
                elevation = elevations[index],
                distanceFromAMeters = haversineMeters(start, coordinate),
            )
        }
    }

    /** Exponential-backoff GET, retrying on HTTP 429 (delay = base << attempt). */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun performRequest(url: String): String {
        fun fail(error: ElevationServiceError): Nothing = throw error
        repeat(MAX_RETRIES) { attempt ->
            val response = try {
                httpClient.get(url)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                fail(ElevationServiceError.NetworkError(error.message ?: error.toString()))
            }

            when (response.statusCode) {
                HTTP_TOO_MANY_REQUESTS -> delay(BASE_RETRY_DELAY * (1 shl attempt))
                HTTP_OK -> return response.body
                else -> fail(ElevationServiceError.ApiError("HTTP ${response.statusCode}"))
            }
        }
        fail(ElevationServiceError.RateLimited)
    }

    // The parameterless InvalidResponse mirrors the Swift case; the parse detail is intentionally dropped.
    private fun parseElevations(body: String): List<Double> = try {
        json.decodeFromString<ElevationResponse>(body).elevation
    } catch (_: SerializationException) {
        throw ElevationServiceError.InvalidResponse
    }

    @Serializable
    private data class ElevationResponse(val elevation: List<Double>)

    companion object {
        private const val API_ENDPOINT = "https://api.open-meteo.com/v1/elevation"
        private const val MAX_POINTS_PER_REQUEST = 100
        private const val MAX_RETRIES = 3
        private val BASE_RETRY_DELAY: Duration = 500.milliseconds

        private const val HTTP_OK = 200
        private const val HTTP_TOO_MANY_REQUESTS = 429

        private const val THRESHOLD_UNDER_1KM = 1000.0
        private const val THRESHOLD_1_TO_5KM = 5000.0
        private const val THRESHOLD_5_TO_20KM = 20000.0
        private const val SAMPLE_COUNT_UNDER_1KM = 20
        private const val SAMPLE_COUNT_1_TO_5KM = 50
        private const val SAMPLE_COUNT_5_TO_20KM = 80
        private const val SAMPLE_COUNT_OVER_20KM = 100

        private val DEFAULT_JSON = Json { ignoreUnknownKeys = true }

        /** Evenly spaced coordinates from [from] to [to] inclusive; [sampleCount] clamped to 2..100. */
        fun sampleCoordinates(from: LatLng, to: LatLng, sampleCount: Int): List<LatLng> {
            val count = sampleCount.coerceIn(2, MAX_POINTS_PER_REQUEST)
            return (0 until count).map { i ->
                val fraction = i.toDouble() / (count - 1)
                LatLng(
                    latitude = from.latitude + fraction * (to.latitude - from.latitude),
                    longitude = from.longitude + fraction * (to.longitude - from.longitude),
                )
            }
        }

        /** Recommended sample count for a path distance (m): <1km→20, <5km→50, <20km→80, else 100. */
        fun optimalSampleCount(distanceMeters: Double): Int = when {
            distanceMeters < THRESHOLD_UNDER_1KM -> SAMPLE_COUNT_UNDER_1KM
            distanceMeters < THRESHOLD_1_TO_5KM -> SAMPLE_COUNT_1_TO_5KM
            distanceMeters < THRESHOLD_5_TO_20KM -> SAMPLE_COUNT_5_TO_20KM
            else -> SAMPLE_COUNT_OVER_20KM
        }

        /** Subsample to [targetCount] preserving the first and last elements. */
        private fun subsample(array: List<LatLng>, targetCount: Int): List<LatLng> {
            if (array.size <= targetCount || targetCount < 2) return array

            val result = ArrayList<LatLng>(targetCount)
            result.add(array[0])

            val middleCount = targetCount - 2
            if (middleCount > 0) {
                val step = (array.size - 2).toDouble() / (middleCount + 1)
                for (i in 1..middleCount) {
                    result.add(array[(i * step).toInt()])
                }
            }

            result.add(array[array.size - 1])
            return result
        }
    }
}
