package one.meshcore.shared.map.ui

import android.graphics.PointF
import android.graphics.RectF
import android.view.Gravity
import androidx.compose.runtime.getValue
import one.meshcore.shared.map.MapClustering
import one.meshcore.shared.map.normalizedWorldLongitude
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Point
import one.meshcore.shared.map.LatLng as MeshLatLng

internal fun MapLibreHostState.ensureMap(map: MapLibreMap) {
    if (mapBound) return
    mapBound = true
    map.addOnMapClickListener { latLng ->
        val screen = map.projection.toScreenLocation(latLng)
        if (expandClusterAt(map, screen)) return@addOnMapClickListener true
        // Include labels so tapping a name selects its pin instead of dismissing the map.
        val features = map.queryRenderedFeatures(
            screen,
            LAYER_ID,
            ICON_LAYER_ID,
            LABEL_LAYER_ID,
            FIXED_LAYER_ID,
            FIXED_ICON_LAYER_ID,
            FIXED_LABEL_LAYER_ID,
        )
        val id = features.firstOrNull()?.getStringProperty(PROP_ID)
        if (id != null) {
            onPointClick(id)
            true
        } else {
            onMapClick(MeshLatLng(latLng.latitude, latLng.longitude).normalizedWorldLongitude())
            true
        }
    }
    map.addOnMapLongClickListener { latLng ->
        onMapLongPress(MeshLatLng(latLng.latitude, latLng.longitude).normalizedWorldLongitude())
        true
    }
    map.addOnCameraMoveStartedListener { reason ->
        if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
            userGestureMove = true
        }
    }
    map.addOnCameraIdleListener {
        if (programmaticMove) {
            programmaticMove = false
            return@addOnCameraIdleListener
        }
        if (!userGestureMove) return@addOnCameraIdleListener
        userGestureMove = false
        regionFromVisibleBounds(map)?.let(onCameraIdle)
    }
}

/**
 * Drills into a tapped cluster: moves to the zoom that breaks it apart, plus a couple of levels so
 * the pins separate visibly. A cluster swallows the tap either way, so a pin underneath the bubble
 * is never selected instead.
 */
private fun MapLibreHostState.expandClusterAt(map: MapLibreMap, screen: PointF): Boolean {
    val half = MapClustering.CLUSTER_HIT_HALF_EDGE_PX.toFloat()
    val hitRect = RectF(screen.x - half, screen.y - half, screen.x + half, screen.y + half)
    val cluster = map.queryRenderedFeatures(hitRect, CLUSTER_CIRCLE_LAYER_ID)
        .firstOrNull { it.hasProperty(PROP_POINT_COUNT) } ?: return false
    val source = map.style?.getSourceAs<GeoJsonSource>(SOURCE_ID)
    val center = cluster.geometry() as? Point
    val zoom = source?.let { MapClustering.expansionZoom(it.getClusterExpansionZoom(cluster)) }
    if (center != null && zoom != null) {
        programmaticMove = true
        map.animateCamera(
            CameraUpdateFactory.newLatLngZoom(LatLng(center.latitude(), center.longitude()), zoom),
        )
    }
    return true
}

/** Enables or disables all pan/zoom/rotate/tilt gestures, so an inline preview never steals a scroll. */
internal fun MapLibreHostState.applyGestures(map: MapLibreMap, interactive: Boolean) {
    map.uiSettings.setAllGesturesEnabled(interactive)
}

/**
 * Compass and attribution chrome, matching iOS `MC1MapView`. The compass is hidden on
 * non-interactive previews. It fades when the camera faces north, the Android Maps idiom.
 * Logo and attribution keep MapLibre's horizontal layout and lift by the scale-bar inset.
 */
internal fun MapLibreHostState.applyChrome(map: MapLibreMap, interactive: Boolean, density: Float) {
    if (kotlin.math.abs(pinSprites.density - density) > DENSITY_EPSILON) {
        pinSprites = MapPinSprites(density)
        iconsRegisteredForStyle = null
        registeredHopIcons.clear()
    }
    val settings = map.uiSettings
    settings.isCompassEnabled = interactive
    settings.compassGravity = Gravity.TOP or Gravity.END
    val compass = dpToPx(MapLibreChrome.COMPASS_MARGIN_DP, density)
    settings.setCompassMargins(compass, compass, compass, compass)
    settings.setCompassFadeFacingNorth(true)
    val bottom = dpToPx(MapLibreChrome.ATTRIBUTION_MARGIN_BOTTOM_DP, density)
    settings.setLogoMargins(
        settings.logoMarginLeft,
        settings.logoMarginTop,
        settings.logoMarginRight,
        bottom,
    )
    settings.setAttributionMargins(
        settings.attributionMarginLeft,
        settings.attributionMarginTop,
        settings.attributionMarginRight,
        bottom,
    )
}

/**
 * North lock: disables rotation and snaps the heading back to 0, mirroring iOS
 * (`isRotateEnabled = !isNorthLocked` plus `setDirection(0)`). Only meaningful on an interactive map.
 */
internal fun MapLibreHostState.applyNorthLock(map: MapLibreMap, interactive: Boolean, northLocked: Boolean) {
    if (!interactive) return
    map.uiSettings.isRotateGesturesEnabled = !northLocked
    if (northLocked && map.cameraPosition.bearing != 0.0) {
        map.animateCamera(CameraUpdateFactory.bearingTo(0.0))
    }
}
