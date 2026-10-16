package one.meshcore.shared.map.ui

import androidx.compose.runtime.getValue
import one.meshcore.shared.map.MapCameraRegion
import one.meshcore.shared.map.MapPoint
import one.meshcore.shared.map.shouldApplyCameraTarget
import one.meshcore.shared.map.validatedBounds
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap

/**
 * Applies a framing request. The version is recorded even when the region is unusable, so an
 * invalid target cannot retry on every recomposition.
 */
internal fun MapLibreHostState.applyCameraTarget(map: MapLibreMap, target: MapCameraRegion?, version: Int) {
    if (!shouldApplyCameraTarget(target, version, lastCameraTarget, lastCameraTargetVersion)) return
    lastCameraTarget = target
    lastCameraTargetVersion = version
    target?.let { moveToRegion(map, it) }
    hasFramed = true
}

internal fun MapLibreHostState.frameInitial(map: MapLibreMap, points: List<MapPoint>, initialCamera: MapCameraRegion?) {
    if (initialCamera != null) {
        moveToRegion(map, initialCamera)
    } else {
        frameCamera(map, points)
    }
}

private fun MapLibreHostState.moveToRegion(map: MapLibreMap, region: MapCameraRegion) {
    val bounds = region.validatedBounds() ?: return
    programmaticMove = true
    val latLngBounds = LatLngBounds.Builder()
        .include(LatLng(bounds.sw.latitude, bounds.sw.longitude))
        .include(LatLng(bounds.ne.latitude, bounds.ne.longitude))
        .build()
    map.moveCamera(CameraUpdateFactory.newLatLngBounds(latLngBounds, BOUNDS_PADDING_PX))
}

private fun MapLibreHostState.frameCamera(map: MapLibreMap, points: List<MapPoint>) {
    programmaticMove = true
    when {
        points.isEmpty() -> {
            programmaticMove = false
        }
        points.size == 1 -> {
            val only = points.first().coordinate
            map.moveCamera(
                CameraUpdateFactory.newLatLngZoom(
                    LatLng(only.latitude, only.longitude),
                    SINGLE_POINT_ZOOM,
                ),
            )
        }
        else -> {
            val builder = LatLngBounds.Builder()
            points.forEach { p ->
                builder.include(LatLng(p.coordinate.latitude, p.coordinate.longitude))
            }
            map.moveCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), BOUNDS_PADDING_PX))
        }
    }
}
