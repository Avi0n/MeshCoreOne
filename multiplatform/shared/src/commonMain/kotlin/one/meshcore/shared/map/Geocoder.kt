package one.meshcore.shared.map

/**
 * Reverse-geocoder seam used by [RegionResolver]. Returns a plain [GeocodeResult] rather than a platform
 * placemark so the Android `android.location.Geocoder` adapter and test doubles share one surface. Mirrors
 * iOS `Geocoder`.
 */
interface Geocoder {
    /** Reverse-geocode a coordinate; [preferredLocale] is a BCP-47/ICU locale id, e.g. "en_US". */
    suspend fun reverseGeocode(location: LatLng, preferredLocale: String?): GeocodeResult?

    /** Cancel an in-flight reverse-geocode. A single retained geocoder makes this actually cancel. */
    fun cancelGeocode()
}

/** The subset of a reverse-geocode result the region resolver needs. Mirrors iOS `GeocodeResult`. */
data class GeocodeResult(val countryCode: String?, val administrativeArea: String?, val subAdministrativeArea: String?)
