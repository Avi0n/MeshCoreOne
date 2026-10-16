package one.meshcore.shared.map.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.map.MapCameraRegion
import one.meshcore.shared.map.MapLine
import one.meshcore.shared.map.MapPoint
import one.meshcore.shared.map.MapStyleSelection
import one.meshcore.shared.theme.AppColorSchemePreference

/**
 * Platform map surface. Android provides [LocalMapLibreHostRenderer]; hosts that do not
 * (desktop, previews) keep the list-only Map tab body in [MapScreen]. No expect/actual —
 * a map change does not wait on a JVM host.
 *
 * @param initialCamera restored camera, or null to frame pins on first load
 * @param cameraTarget one-shot framing request (center-all, deep-link focus); host applies then caller clears
 * @param cameraTargetVersion bump to re-fire an unchanged [cameraTarget]; leave at 0 to dedupe on the region
 * @param lines path/SNR polylines (empty until a caller supplies MapLine geometry)
 * @param colorSchemePreference basemap appearance: `system` follows app Appearance, `light`/`dark` force it
 * @param isOffline pin the basemap to the style the offline packs were downloaded against
 * @param interactive when false, pan/zoom gestures are disabled — for inline previews inside scrolling content
 * @param showLabels draw the per-pin name labels; toggled from the map-options menu
 * @param clusteringEnabled merge nearby node pins into count bubbles; toggled from the map-options menu
 * @param northLocked disable rotation and snap the heading to north, toggled from the map-options menu
 * @param showsUserLocation track the device's live position with the platform location indicator
 * @param showsScale draw a live distance scale (iOS `showsScale`)
 * @param onCameraIdle user-driven camera settled; persist via [MapCameraStore]
 * @param onPointClick pin feature id tapped
 * @param onMapClick empty-map surface tap (e.g. LOS relocate); ignored when a pin is hit
 * @param onMapLongPress long-press coordinate for dropped pin (empty map surface)
 * @param onStyleLoadingChange true while a basemap style loads, false once it finishes; drives the spinner
 */
interface MapLibreHostRenderer {
    @Composable
    fun Host(
        points: List<MapPoint>,
        modifier: Modifier,
        style: MapStyleSelection,
        initialCamera: MapCameraRegion?,
        cameraTarget: MapCameraRegion?,
        cameraTargetVersion: Int,
        lines: List<MapLine>,
        colorSchemePreference: AppColorSchemePreference,
        isOffline: Boolean,
        interactive: Boolean,
        showLabels: Boolean,
        northLocked: Boolean,
        showsUserLocation: Boolean,
        onCameraIdle: ((MapCameraRegion) -> Unit)?,
        onPointClick: ((String) -> Unit)?,
        onMapClick: ((LatLng) -> Unit)?,
        onMapLongPress: ((LatLng) -> Unit)?,
        onStyleLoadingChange: ((Boolean) -> Unit)?,
    )
}

object NoOpMapLibreHostRenderer : MapLibreHostRenderer {
    @Composable
    override fun Host(
        points: List<MapPoint>,
        modifier: Modifier,
        style: MapStyleSelection,
        initialCamera: MapCameraRegion?,
        cameraTarget: MapCameraRegion?,
        cameraTargetVersion: Int,
        lines: List<MapLine>,
        colorSchemePreference: AppColorSchemePreference,
        isOffline: Boolean,
        interactive: Boolean,
        showLabels: Boolean,
        northLocked: Boolean,
        showsUserLocation: Boolean,
        onCameraIdle: ((MapCameraRegion) -> Unit)?,
        onPointClick: ((String) -> Unit)?,
        onMapClick: ((LatLng) -> Unit)?,
        onMapLongPress: ((LatLng) -> Unit)?,
        onStyleLoadingChange: ((Boolean) -> Unit)?,
    ) = Unit
}

val LocalMapLibreHostRenderer = staticCompositionLocalOf<MapLibreHostRenderer> { NoOpMapLibreHostRenderer }

@Composable
fun MapLibreHost(
    points: List<MapPoint>,
    modifier: Modifier = Modifier,
    style: MapStyleSelection = MapStyleSelection.STANDARD,
    initialCamera: MapCameraRegion? = null,
    cameraTarget: MapCameraRegion? = null,
    cameraTargetVersion: Int = 0,
    lines: List<MapLine> = emptyList(),
    colorSchemePreference: AppColorSchemePreference = LocalMapColorSchemePreference.current,
    isOffline: Boolean = LocalMapOffline.current,
    interactive: Boolean = true,
    showLabels: Boolean = true,
    clusteringEnabled: Boolean = true,
    northLocked: Boolean = false,
    showsUserLocation: Boolean = false,
    showsScale: Boolean = false,
    onCameraIdle: ((MapCameraRegion) -> Unit)? = null,
    onPointClick: ((String) -> Unit)? = null,
    onMapClick: ((LatLng) -> Unit)? = null,
    onMapLongPress: ((LatLng) -> Unit)? = null,
    onStyleLoadingChange: ((Boolean) -> Unit)? = null,
) {
    var scaleRegion by remember(initialCamera) { mutableStateOf(initialCamera) }
    LaunchedEffect(showsScale, cameraTarget, cameraTargetVersion) {
        if (showsScale && cameraTarget != null) {
            scaleRegion = cameraTarget
        }
    }
    val reportedIdle: ((MapCameraRegion) -> Unit)? =
        if (!showsScale && onCameraIdle == null) {
            null
        } else {
            { region ->
                if (showsScale) scaleRegion = region
                onCameraIdle?.invoke(region)
            }
        }
    val renderedPoints = if (clusteringEnabled) points else points.map { it.copy(isClusterable = false) }
    Box(modifier) {
        LocalMapLibreHostRenderer.current.Host(
            points = renderedPoints,
            modifier = Modifier.fillMaxSize(),
            style = style,
            initialCamera = initialCamera,
            cameraTarget = cameraTarget,
            cameraTargetVersion = cameraTargetVersion,
            lines = lines,
            colorSchemePreference = colorSchemePreference,
            isOffline = isOffline,
            interactive = interactive,
            showLabels = showLabels,
            northLocked = northLocked,
            showsUserLocation = showsUserLocation,
            onCameraIdle = reportedIdle,
            onPointClick = onPointClick,
            onMapClick = onMapClick,
            onMapLongPress = onMapLongPress,
            onStyleLoadingChange = onStyleLoadingChange,
        )
        if (showsScale) {
            MapScaleBar(
                region = scaleRegion,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(SCALE_BAR_INSET),
            )
        }
    }
}

private val SCALE_BAR_INSET = 12.dp
