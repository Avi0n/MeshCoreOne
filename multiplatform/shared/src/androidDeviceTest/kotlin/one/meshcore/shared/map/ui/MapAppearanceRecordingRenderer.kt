package one.meshcore.shared.map.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.map.MapCameraRegion
import one.meshcore.shared.map.MapLine
import one.meshcore.shared.map.MapPoint
import one.meshcore.shared.map.MapStyleSelection
import one.meshcore.shared.theme.AppColorSchemePreference

internal class MapAppearanceRecordingRenderer : MapLibreHostRenderer {
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
        preference = colorSchemePreference
        offline = isOffline
        Box(modifier.testTag(MAP_TAG))
    }
    companion object {
        const val MAP_TAG = "recorded-map"
    }
}
