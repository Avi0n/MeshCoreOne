package one.meshcore.shared.map

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import one.meshcore.shared.backup.RegionSelection
import one.meshcore.shared.backup.RegionSource
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Resolves a [RegionSelection] from the device's current location, for the radio-region auto-suggest.
 * A one-shot orchestrator (not observable). Every failure mode — unauthorized, location timeout,
 * geocode timeout, no country code — is silent (returns null), so the caller falls through to the
 * manual picker. Mirrors iOS `RegionResolver`.
 *
 * Results are cached per rounded-degree cell for [CACHE_TTL]. The geocode is bounded by its own
 * timeout that cancels the geocoder on expiry, independent of the location request's timeout.
 */
class RegionResolver(
    private val location: LocationService,
    private val geocoder: Geocoder,
    private val regionalAreas: RegionalAreasMatcher,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private val cacheMutex = Mutex()
    private val cache = mutableMapOf<CacheKey, CachedResult>()

    suspend fun resolve(): RegionSelection? {
        if (!location.isAuthorized) return null
        val loc = currentLocationOrNull() ?: return null
        val key = CacheKey(loc)
        cached(key)?.let { return it }
        return selectionFor(loc)?.also { store(key, it) }
    }

    /** A location failure falls through to the manual picker. Every other failure still propagates. */
    private suspend fun currentLocationOrNull(): LatLng? = locationFixOrNull {
        location.requestCurrentLocation(LOCATION_TIMEOUT)
    }

    private suspend fun selectionFor(loc: LatLng): RegionSelection? {
        val result = reverseGeocode(loc) ?: return null
        val countryCode = result.countryCode ?: return null
        val normalizedAdmin = result.administrativeArea?.let(::normalize)
        val normalizedCounty = result.subAdministrativeArea
            ?.let(::normalize)
            ?.replace(COUNTY_SUFFIX, "")
        val adminCode = regionalAreas.matchSubdivision(country = countryCode, normalized = normalizedAdmin)
        val countyKey = regionalAreas.matchCounty(
            country = countryCode,
            state = adminCode,
            normalized = normalizedCounty,
        )
        return RegionSelection(
            countryCode = countryCode,
            administrativeAreaCode = adminCode,
            countyKey = countyKey,
            source = RegionSource.LOCATION,
        )
    }

    /**
     * Bounds the geocoder with its own timeout. Timeout and parent cancellation both cancel the geocoder;
     * only the timeout becomes null, and any other geocoder failure does the same.
     */
    private suspend fun reverseGeocode(loc: LatLng): GeocodeResult? = withTimeoutOrNull(GEOCODE_TIMEOUT) {
        reverseGeocodeOrNull(
            geocode = { geocoder.reverseGeocode(loc, GEOCODING_LOCALE) },
            cancelGeocode = geocoder::cancelGeocode,
        )
    }

    private fun normalize(value: String): String = foldDiacritics(value.trim().lowercase())

    private suspend fun cached(key: CacheKey): RegionSelection? = cacheMutex.withLock {
        cache[key]?.takeIf { it.isFresh }?.value
    }

    private suspend fun store(key: CacheKey, value: RegionSelection) {
        cacheMutex.withLock { cache[key] = CachedResult(value, timeSource.markNow()) }
    }

    private data class CacheKey(val lat: Int, val lng: Int) {
        constructor(loc: LatLng) : this(loc.latitude.roundToInt(), loc.longitude.roundToInt())
    }

    private inner class CachedResult(val value: RegionSelection, private val mark: TimeMark) {
        val isFresh: Boolean get() = mark.elapsedNow() < CACHE_TTL
    }

    private companion object {
        val LOCATION_TIMEOUT: Duration = 5.seconds
        val GEOCODE_TIMEOUT: Duration = 5.seconds
        val CACHE_TTL: Duration = 24.hours
        const val GEOCODING_LOCALE = "en_US"
        const val COUNTY_SUFFIX = " county"
    }
}

/**
 * Runs one reverse geocode. Cancellation cancels the geocoder and propagates. Any other failure
 * returns null so the caller can fall through to the manual picker.
 */
internal suspend fun reverseGeocodeOrNull(
    geocode: suspend () -> GeocodeResult?,
    cancelGeocode: () -> Unit,
): GeocodeResult? = try {
    geocode()
} catch (cancelled: CancellationException) {
    cancelGeocode()
    throw cancelled
} catch (_: Throwable) {
    null
}

/** A location failure falls through to the manual picker. Every other failure still propagates. */
internal suspend fun locationFixOrNull(read: suspend () -> LatLng): LatLng? = try {
    read()
} catch (_: LocationServiceError) {
    null
}
