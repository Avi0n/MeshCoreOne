package one.meshcore.shared.map

/**
 * A camera framing target: a center coordinate plus a latitude/longitude span (degrees). The map
 * layer's analogue of iOS `MKCoordinateRegion`. Both spans are the full extent, so a corner sits at
 * `center ± span/2`.
 */
data class MapCameraRegion(val center: LatLng, val spanLatDeg: Double, val spanLonDeg: Double)

/** Tight framing for the LOS locate control (iOS LOS `MKCoordinateSpan` of 0.01°). */
const val USER_LOCATE_SPAN_DEG = 0.01

/** Default framing for `centerOnUserLocation` (iOS span of 0.02° on Trace and path maps). */
const val CENTER_ON_USER_SPAN_DEG = 0.02

/** Tight framing for a locate tap. LOS uses [USER_LOCATE_SPAN_DEG]; other maps use [CENTER_ON_USER_SPAN_DEG]. */
fun userLocateCameraRegion(center: LatLng, spanDeg: Double = USER_LOCATE_SPAN_DEG): MapCameraRegion =
    MapCameraRegion(center = center, spanLatDeg = spanDeg, spanLonDeg = spanDeg)

/** A south-west / north-east coordinate box, the analogue of iOS `MLNCoordinateBounds`. */
data class CoordinateBounds(val sw: LatLng, val ne: LatLng)

/**
 * Whether two boxes share any area, edges included. Mirrors the iOS `MLNCoordinateBounds.overlaps`
 * extension, which gates a map style on whether a downloaded pack covers what the responder can see.
 */
fun CoordinateBounds.overlaps(other: CoordinateBounds): Boolean = sw.latitude <= other.ne.latitude &&
    ne.latitude >= other.sw.latitude &&
    sw.longitude <= other.ne.longitude &&
    ne.longitude >= other.sw.longitude

/**
 * The camera-apply trust boundary (spec §1.7): the SW/NE corners to fit, or null to skip applying this
 * region. An out-of-range or non-finite value would abort the native MapLibre process, so:
 *  - null if the center coordinate is invalid (lat outside ±90, lon outside ±180, or NaN),
 *  - null if either span delta is non-finite (NaN/∞) — min/max would propagate a NaN past the clamp,
 *  - otherwise the corners, with latitudes clamped to ±90 (longitude left unclamped; MapLibre wraps it).
 *
 * A skip returns null; the caller still advances its applied-version counter so the invalid target
 * does not retry forever.
 */
fun MapCameraRegion.validatedBounds(): CoordinateBounds? {
    if (!center.isValid) return null
    if (!spanLatDeg.isFinite() || !spanLonDeg.isFinite()) return null
    val swLat = (center.latitude - spanLatDeg / 2).coerceIn(-LATITUDE_LIMIT, LATITUDE_LIMIT)
    val neLat = (center.latitude + spanLatDeg / 2).coerceIn(-LATITUDE_LIMIT, LATITUDE_LIMIT)
    return CoordinateBounds(
        sw = LatLng(swLat, center.longitude - spanLonDeg / 2),
        ne = LatLng(neLat, center.longitude + spanLonDeg / 2),
    )
}
