// AndroidOnly: WP-312 Live native viewport projection; coordinates are never interpolated from camera spans.
package com.meshcoreone.android.core.maps

/** Physical pixels local to the MapView, not dp, window coordinates, or a cached camera rectangle. */
data class MapScreenPoint(val x: Float, val y: Float) {
    init { require(x.isFinite() && y.isFinite()) }
}

sealed interface MapProjectionResult<out T> {
    data class Value<T>(val value: T) : MapProjectionResult<T>
    data object NotReady : MapProjectionResult<Nothing>
    data object OutsideViewport : MapProjectionResult<Nothing>
    data object InvalidCoordinate : MapProjectionResult<Nothing>
}

interface MapCoordinateProjection {
    fun coordinateAt(point: MapScreenPoint): MapProjectionResult<GeoPoint>
    fun screenPointFor(coordinate: GeoPoint): MapProjectionResult<MapScreenPoint>
}

/** A renderer-owned binding. Retaining a handle after unmount cannot retain/use a destroyed native map. */
class MapProjectionHandle : MapCoordinateProjection {
    private var binding: MapCoordinateProjection? = null
    internal fun bind(projection: MapCoordinateProjection?) { binding = projection }
    override fun coordinateAt(point: MapScreenPoint): MapProjectionResult<GeoPoint> =
        binding?.coordinateAt(point) ?: MapProjectionResult.NotReady
    override fun screenPointFor(coordinate: GeoPoint): MapProjectionResult<MapScreenPoint> =
        binding?.screenPointFor(coordinate) ?: MapProjectionResult.NotReady
}

internal fun wrapLongitude(longitude: Double): Double =
    ((longitude + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
