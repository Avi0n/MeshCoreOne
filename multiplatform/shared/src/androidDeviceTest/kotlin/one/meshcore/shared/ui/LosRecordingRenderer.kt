package one.meshcore.shared.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.map.MapCameraRegion
import one.meshcore.shared.map.MapLine
import one.meshcore.shared.map.MapPoint
import one.meshcore.shared.map.MapStyleSelection
import one.meshcore.shared.map.ui.MapLibreHostRenderer
import one.meshcore.shared.theme.AppColorSchemePreference

internal class LosRecordingRenderer : MapLibreHostRenderer {
    var points: List<MapPoint> = emptyList()
    var initialCamera: MapCameraRegion? = null
    var cameraTarget: MapCameraRegion? = null
    var cameraVersion = 0
    var idle: ((MapCameraRegion) -> Unit)? = null
    var preference: AppColorSchemePreference? = null
    var offline: Boolean? = null

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
    ) {
        this.points = points
        this.initialCamera = initialCamera
        this.cameraTarget = cameraTarget
        cameraVersion = cameraTargetVersion
        idle = onCameraIdle
        preference = colorSchemePreference
        offline = isOffline
        Box(modifier.testTag(MAP_TAG))
    }
    companion object {
        const val MAP_TAG = "los-recorded-map"
    }
}
