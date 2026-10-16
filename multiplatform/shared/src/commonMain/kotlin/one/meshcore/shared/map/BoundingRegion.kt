package one.meshcore.shared.map

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A [MapCameraRegion] that fits all coordinates with padding, or null if the list is empty. Spans are
 * at least [MIN_SPAN_DEG] and clamped so `center ± span/2` stays in range. Mirrors iOS
 * `[CLLocationCoordinate2D].boundingRegion`.
 */
fun List<LatLng>.boundingRegion(paddingMultiplier: Double = DEFAULT_PADDING_MULTIPLIER): MapCameraRegion? {
    val first = firstOrNull() ?: return null

    var minLat = first.latitude
    var maxLat = first.latitude
    var minLon = first.longitude
    var maxLon = first.longitude

    for (coord in drop(1)) {
        minLat = min(minLat, coord.latitude)
        maxLat = max(maxLat, coord.latitude)
        minLon = min(minLon, coord.longitude)
        maxLon = max(maxLon, coord.longitude)
    }

    val center = LatLng(
        latitude = (minLat + maxLat) / 2,
        longitude = (minLon + maxLon) / 2,
    )
    val rawLatDelta = max(MIN_SPAN_DEG, (maxLat - minLat) * paddingMultiplier)
    val rawLonDelta = max(MIN_SPAN_DEG, (maxLon - minLon) * paddingMultiplier)

    // Clamp spans so center ± span/2 stays within valid coordinate ranges.
    val maxLatDelta = (MAX_LATITUDE - abs(center.latitude)) * 2
    val latDelta = min(rawLatDelta, maxLatDelta)
    val lonDelta = min(rawLonDelta, MAX_LON_SPAN_DEG)

    return MapCameraRegion(center = center, spanLatDeg = latDelta, spanLonDeg = lonDelta)
}

/**
 * iOS `hasPinsForCenterAll`: Center All is for located contacts and discovered nodes.
 * A dropped pin stays drawn but does not enable the button on its own.
 */
fun hasPinsForCenterAll(contactPoints: List<MapPoint>, discoveredPoints: List<MapPoint>): Boolean =
    contactPoints.isNotEmpty() || discoveredPoints.isNotEmpty()

/**
 * iOS `centerOnAllContacts`: frame contacts and discovered nodes only, so a far
 * chat-coordinate or long-press pin does not stretch the team cluster out of view.
 */
fun centerAllRegion(contactPoints: List<MapPoint>, discoveredPoints: List<MapPoint>): MapCameraRegion? =
    (contactPoints + discoveredPoints).map { it.coordinate }.boundingRegion()

private const val DEFAULT_PADDING_MULTIPLIER = 1.5
private const val MIN_SPAN_DEG = 0.01
private const val MAX_LON_SPAN_DEG = 360.0
