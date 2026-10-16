package one.meshcore.shared.map

/**
 * A geographic coordinate, the map layer's analogue of iOS `CLLocationCoordinate2D`. Value-based
 * equality (a data class) is load-bearing: [MapPoint] and [MapLine] diff by comparing coordinates,
 * so two coordinates with the same latitude/longitude must compare equal.
 */
data class LatLng(val latitude: Double, val longitude: Double)

/** MapLibre can report a tap in an adjacent world copy; emit the equivalent WGS-84 longitude. */
fun LatLng.normalizedWorldLongitude(): LatLng {
    if (longitude in MIN_LONGITUDE..MAX_LONGITUDE) return this
    val wrapped = ((longitude - MIN_LONGITUDE) % LONGITUDE_CIRCLE_DEG + LONGITUDE_CIRCLE_DEG) %
        LONGITUDE_CIRCLE_DEG + MIN_LONGITUDE
    return copy(longitude = wrapped)
}

/**
 * Whether the coordinate is a valid WGS-84 position, mirroring iOS `CLLocationCoordinate2DIsValid`.
 * A non-finite component (NaN/∞) fails the range checks, so this doubles as the finiteness guard the
 * camera trust boundaries in [MapCameraStore] and [MapCameraRegion] rely on.
 */
val LatLng.isValid: Boolean
    get() = latitude in MIN_LATITUDE..MAX_LATITUDE && longitude in MIN_LONGITUDE..MAX_LONGITUDE

/**
 * A coordinate a contact or discovered-node row can show: not the unset (0, 0) pair, and a valid
 * WGS-84 position.
 */
fun hasUsableCoordinate(latitude: Double, longitude: Double): Boolean {
    if (latitude == 0.0 && longitude == 0.0) return false
    return LatLng(latitude, longitude).isValid
}

// WGS-84 coordinate validity bounds. Named here so every map-layer range check reads the same limits.
internal const val MIN_LATITUDE = -90.0
internal const val MAX_LATITUDE = 90.0
internal const val MIN_LONGITUDE = -180.0
internal const val MAX_LONGITUDE = 180.0
private const val LONGITUDE_CIRCLE_DEG = 360.0

/** The latitude magnitude corner coordinates are clamped to before reaching the native camera. */
internal const val LATITUDE_LIMIT = 90.0
