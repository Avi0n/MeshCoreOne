package one.meshcore.shared.map

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Great-circle distance in meters between two coordinates (haversine, WGS-84 mean radius). The map
 * layer's stand-in for iOS `CLLocation.distance(from:)` / `RFCalculator.distance`, used by the SNR
 * midpoint badge, the elevation-sample distances, and `ContactDto.distanceMeters`.
 */
fun haversineMeters(from: LatLng, to: LatLng): Double {
    val lat1 = from.latitude * DEGREES_TO_RADIANS
    val lat2 = to.latitude * DEGREES_TO_RADIANS
    val deltaLat = (to.latitude - from.latitude) * DEGREES_TO_RADIANS
    val deltaLon = (to.longitude - from.longitude) * DEGREES_TO_RADIANS
    val a = sin(deltaLat / 2) * sin(deltaLat / 2) +
        cos(lat1) * cos(lat2) * sin(deltaLon / 2) * sin(deltaLon / 2)
    return EARTH_RADIUS_METERS * 2 * atan2(sqrt(a), sqrt(1 - a))
}

/**
 * Length of the drawn path over [coordinates] in order. Null until at least two points exist, so a
 * banner's distance always matches the polyline (iOS `[CLLocationCoordinate2D].totalDistance()`).
 */
fun totalDistanceMeters(coordinates: List<LatLng>): Double? {
    if (coordinates.size < MIN_POINTS_FOR_PATH_DISTANCE) return null
    return coordinates.zipWithNext { from, to -> haversineMeters(from, to) }.sum()
}

private const val DEGREES_TO_RADIANS = PI / 180.0
private const val EARTH_RADIUS_METERS = 6_371_000.0
private const val MIN_POINTS_FOR_PATH_DISTANCE = 2
