package one.meshcore.shared.map.ui

import android.content.Context
import androidx.compose.runtime.getValue
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style

/**
 * Tracks the device's live position with MapLibre's location indicator — the moving dot, its
 * accuracy ring and a heading arrow — mirroring the iOS `showsUserLocation` flag. The camera is
 * left alone; the locate control owns framing. Without a location grant the indicator stays off,
 * because activating it unpermitted throws.
 */
internal fun MapLibreHostState.applyShowsUserLocation(map: MapLibreMap, context: Context, enabled: Boolean) {
    val component = map.locationComponent
    val style = map.style
    if (!enabled || style == null || !hasLocationPermission(context)) {
        if (component.isLocationComponentActivated) {
            component.isLocationComponentEnabled = false
        }
        locationComponentStyle = null
        return
    }
    // A reloaded style drops the indicator's layers, so it must be re-activated against the new one.
    if (locationComponentStyle === style) return
    runCatching {
        component.activateLocationComponent(
            LocationComponentActivationOptions.builder(context, style).build(),
        )
        component.isLocationComponentEnabled = true
        component.renderMode = RenderMode.COMPASS
        component.cameraMode = CameraMode.NONE
        // The view was already started before the map existed, so the component starts its own
        // location updates here rather than waiting for a lifecycle callback that has passed.
        component.onStart()
    }.onSuccess { locationComponentStyle = style }
}

/** Shows or hides the name-label layer, mirroring iOS `updateLabelVisibility`. */
internal fun MapLibreHostState.updateLabelVisibility(map: MapLibreMap, showLabels: Boolean) {
    map.style?.let { updateLabelVisibility(it, showLabels) }
}

internal fun MapLibreHostState.updateLabelVisibility(style: Style, showLabels: Boolean) {
    for (layerId in listOf(LABEL_LAYER_ID, FIXED_LABEL_LAYER_ID)) {
        style.getLayer(layerId)?.setProperties(MapPinLayers.visibility(showLabels))
    }
}
