// PortedFrom: MC1/Extensions/CLLocationCoordinate2D+BoundingRegion.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Extensions/CLLocationCoordinate2D+Distance.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.maps

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class GeoPoint(val latitude: Double, val longitude: Double) {
    init {
        require(latitude.isFinite() && longitude.isFinite())
        require(latitude in -90.0..90.0 && longitude in -180.0..180.0)
    }

    fun midpoint(other: GeoPoint): GeoPoint {
        var left = longitude
        var right = other.longitude
        if (abs(left - right) > 180.0) {
            if (left < right) left += 360.0 else right += 360.0
        }
        var result = (left + right) / 2.0
        if (result > 180.0) result -= 360.0
        return GeoPoint((latitude + other.latitude) / 2.0, result)
    }
}

fun List<GeoPoint>.boundingCamera(paddingMultiplier: Double = 1.5): MapCamera? {
    require(paddingMultiplier.isFinite() && paddingMultiplier > 0.0)
    val first = firstOrNull() ?: return null
    var minLatitude = first.latitude
    var maxLatitude = first.latitude
    var minLongitude = first.longitude
    var maxLongitude = first.longitude
    drop(1).forEach {
        minLatitude = min(minLatitude, it.latitude)
        maxLatitude = max(maxLatitude, it.latitude)
        minLongitude = min(minLongitude, it.longitude)
        maxLongitude = max(maxLongitude, it.longitude)
    }
    val longitudes = map { it.longitude }.sorted()
    val gapIndex = longitudes.indices.maxBy { index ->
        val next = if (index == longitudes.lastIndex) longitudes.first() + 360.0 else longitudes[index + 1]
        next - longitudes[index]
    }
    minLongitude = longitudes[(gapIndex + 1) % longitudes.size]
    maxLongitude = longitudes[gapIndex].let { if (it < minLongitude) it + 360.0 else it }
    val center = GeoPoint(
        latitude = (minLatitude + maxLatitude) / 2.0,
        longitude = wrapLongitude((minLongitude + maxLongitude) / 2.0),
    )
    val latitudeSpan = min(
        max(0.01, (maxLatitude - minLatitude) * paddingMultiplier),
        ((90.0 - abs(center.latitude)) * 2.0).coerceAtLeast(0.000001),
    )
    val longitudeSpan = min(max(0.01, (maxLongitude - minLongitude) * paddingMultiplier), 360.0)
    return MapCamera(center, latitudeSpan, longitudeSpan)
}

fun List<GeoPoint>.totalDistanceMeters(): Double? {
    if (size < 2) return null
    return zipWithNext().sumOf { (from, to) -> from.distanceMetersTo(to) }
}

fun GeoPoint.distanceMetersTo(other: GeoPoint): Double {
    val latitudeDelta = Math.toRadians(other.latitude - latitude)
    val longitudeDelta = Math.toRadians(other.longitude - longitude)
    val a = sin(latitudeDelta / 2.0).let { it * it } +
        cos(Math.toRadians(latitude)) * cos(Math.toRadians(other.latitude)) *
        sin(longitudeDelta / 2.0).let { it * it }
    return 2.0 * EARTH_RADIUS_METERS * asin(sqrt(a.coerceIn(0.0, 1.0)))
}

private const val EARTH_RADIUS_METERS = 6_371_008.8

data class GeoBounds(val southwest: GeoPoint, val northeast: GeoPoint) {
    init {
        require(southwest.latitude <= northeast.latitude)
    }

    fun overlaps(other: GeoBounds): Boolean {
        val longitudeOverlaps = if (crossesAntimeridian || other.crossesAntimeridian) {
            containsLongitude(other.southwest.longitude) ||
                containsLongitude(other.northeast.longitude) ||
                other.containsLongitude(southwest.longitude)
        } else {
            southwest.longitude <= other.northeast.longitude &&
                northeast.longitude >= other.southwest.longitude
        }
        return southwest.latitude <= other.northeast.latitude &&
            northeast.latitude >= other.southwest.latitude &&
            longitudeOverlaps
    }

    val crossesAntimeridian: Boolean
        get() = southwest.longitude > northeast.longitude

    fun containsLongitude(longitude: Double): Boolean =
        if (crossesAntimeridian) longitude >= southwest.longitude || longitude <= northeast.longitude
        else longitude in southwest.longitude..northeast.longitude
}
