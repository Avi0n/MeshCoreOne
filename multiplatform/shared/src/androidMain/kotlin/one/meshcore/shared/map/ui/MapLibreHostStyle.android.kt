package one.meshcore.shared.map.ui

import androidx.compose.runtime.getValue
import one.meshcore.shared.map.AppliedMapContent
import one.meshcore.shared.map.MapCameraRegion
import one.meshcore.shared.map.MapContent
import one.meshcore.shared.map.MapContentApplyAction
import one.meshcore.shared.map.MapPoint
import one.meshcore.shared.map.canFrameInitialCamera
import one.meshcore.shared.map.mapContentApplyAction
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style

// The style-load path threads the current label/user-location state and the loading callback through
// to the async setStyle completion, where the rebuilt style must have them reapplied.
internal fun MapLibreHostState.applyStyleAndPoints(map: MapLibreMap, content: MapContent, pass: StyleLoadPass) {
    currentContent = content
    val action = mapContentApplyAction(
        requested = content,
        applied = appliedContent,
        hasLiveStyle = map.style != null,
        hasFramed = hasFramed,
    )
    when (action) {
        // A pass with no style to draw on records nothing, so the load completion still sees the
        // content as unapplied and writes it.
        MapContentApplyAction.SKIP, MapContentApplyAction.AWAIT_STYLE -> Unit
        MapContentApplyAction.LOAD_STYLE -> loadStyle(map, pass)
        MapContentApplyAction.APPLY_CONTENT -> {
            map.style?.let { applyContent(it, content) }
            frameInitialIfNeeded(map, content.points, pass.initialCamera)
        }
    }
}

private fun MapLibreHostState.loadStyle(map: MapLibreMap, pass: StyleLoadPass) {
    // Only the URL is recorded here, so the next recomposition does not restart a slow load; the
    // points and lines are recorded by applyContent, once a style exists to hold them.
    appliedContent = appliedContent.copy(styleUrl = currentContent.styleUrl)
    pass.onStyleLoadingChange(true)
    map.setStyle(Style.Builder().fromUri(currentContent.styleUrl)) { loaded ->
        // Contacts usually arrive from the database while the remote style is still downloading, so
        // the completion applies the latest staged content, never the list captured when it started.
        val content = currentContent
        // Raster first: MapLibre addLayer appends at the top. iOS adds satellite/topo before
        // pin and line layers so those overlays sit on the basemap, not over the pins.
        setupRasterSources(loaded)
        applyContent(loaded, content)
        updateRasterVisibility(loaded)
        // The new style drops the old style's layers, so the live location indicator and the
        // labels-off preference must be reapplied against it, mirroring iOS didFinishLoading.
        applyShowsUserLocation(map, pass.context, pass.showsUserLocation)
        updateLabelVisibility(loaded, pass.showLabels)
        frameInitialIfNeeded(map, content.points, pass.initialCamera)
        pass.onStyleLoadingChange(false)
    }
}

/** Writes the pins and lines onto a live style and records what that style now holds. */
private fun MapLibreHostState.applyContent(style: Style, content: MapContent) {
    applyLines(style, content.lines)
    applyPoints(style, content.points)
    appliedContent = AppliedMapContent(
        styleUrl = content.styleUrl,
        points = content.points,
        lines = content.lines,
    )
}

private fun MapLibreHostState.frameInitialIfNeeded(
    map: MapLibreMap,
    points: List<MapPoint>,
    initialCamera: MapCameraRegion?,
) {
    if (hasFramed) return
    // Latch only on a pass that had something to frame. Pins routinely arrive from the database
    // after the style loads, and claiming the empty pass framed leaves the map on the world view.
    if (!canFrameInitialCamera(initialCamera, points)) return
    frameInitial(map, points, initialCamera)
    hasFramed = true
}
