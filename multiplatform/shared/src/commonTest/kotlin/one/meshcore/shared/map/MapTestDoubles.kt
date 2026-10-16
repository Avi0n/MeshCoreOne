package one.meshcore.shared.map

import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.time.Duration

/**
 * Test doubles for the map geo-service seams. The production Android actuals (FusedLocation, the
 * platform geocoder, a real HTTP client) are later slices; these fakes let S2's pure logic be unit
 * tested deterministically.
 */

/** A [LocationService] whose authorization and one-shot result are fixed by the test. */
class FakeLocationService(
    status: LocationAuthorizationStatus = LocationAuthorizationStatus.AUTHORIZED_WHEN_IN_USE,
    private val locationResult: () -> LatLng = { LatLng(0.0, 0.0) },
) : LocationService {
    override val authorizationStatus = MutableStateFlow(status)
    override val currentLocation = MutableStateFlow<LatLng?>(null)
    override val isRequestingLocation = MutableStateFlow(false)

    var requestCount = 0
        private set

    override fun requestPermissionIfNeeded() = Unit

    override fun requestLocation() = Unit

    override suspend fun requestCurrentLocation(timeout: Duration): LatLng {
        requestCount++
        return locationResult()
    }
}

/** A [Geocoder] returning a fixed result (or invoking [onReverse]) and counting cancellations. */
class FakeGeocoder(
    private val result: GeocodeResult? = null,
    private val onReverse: (suspend () -> GeocodeResult?)? = null,
) : Geocoder {
    var cancelCount = 0
        private set
    var lastLocale: String? = null
        private set

    override suspend fun reverseGeocode(location: LatLng, preferredLocale: String?): GeocodeResult? {
        lastLocale = preferredLocale
        return onReverse?.invoke() ?: result
    }

    override fun cancelGeocode() {
        cancelCount++
    }
}

/** A [RegionalAreasMatcher] returning fixed codes and capturing the normalized names it was asked about. */
class FakeRegionalAreasMatcher(private val subdivision: String? = null, private val county: String? = null) :
    RegionalAreasMatcher {
    var subdivisionNormalized: String? = null
        private set
    var countyNormalized: String? = null
        private set

    override fun matchSubdivision(country: String, normalized: String?): String? {
        subdivisionNormalized = normalized
        return subdivision
    }

    override fun matchCounty(country: String, state: String?, normalized: String?): String? {
        countyNormalized = normalized
        return county
    }
}

/** A [GeoHttpClient] delegating to a lambda and recording the URLs it was asked to fetch. */
class FakeGeoHttpClient(private val responder: suspend (String) -> GeoHttpResponse) : GeoHttpClient {
    val requestedUrls = mutableListOf<String>()

    override suspend fun get(url: String): GeoHttpResponse {
        requestedUrls.add(url)
        return responder(url)
    }
}
