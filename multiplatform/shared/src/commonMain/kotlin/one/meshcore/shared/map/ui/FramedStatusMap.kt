package one.meshcore.shared.map.ui

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import one.meshcore.shared.contact.UserLocation
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.map.LocationService
import one.meshcore.shared.map.MapCameraRegion
import one.meshcore.shared.map.MapLine
import one.meshcore.shared.map.MapPoint
import one.meshcore.shared.map.MapStyleSelection
import one.meshcore.shared.theme.AppColorSchemePreference

/**
 * Status-sheet map. Optional chrome falls back to labels on, clustering on, and [fallbackStyle].
 * [cameraResetKey] resets the camera to [region] when the plotted data is replaced.
 */
@Composable
fun <T> BoxScope.FramedStatusMap(
    points: List<MapPoint>,
    lines: List<MapLine>,
    region: MapCameraRegion?,
    cameraResetKey: T,
    colorSchemePreference: AppColorSchemePreference,
    isOffline: Boolean,
    locationService: LocationService?,
    userLocation: UserLocation?,
    chrome: MapDisplayChrome?,
    fallbackStyle: MapStyleSelection,
    styleDisabled: (MapStyleSelection) -> Boolean,
    styleDisabledReason: (MapStyleSelection) -> String?,
    onPointClick: ((String) -> Unit)? = null,
    betweenCenterAndLocate: @Composable () -> Unit = {},
) {
    var cameraTarget by remember(cameraResetKey) { mutableStateOf(region) }
    var cameraTargetVersion by remember { mutableIntStateOf(0) }
    MapLibreHost(
        points = points,
        lines = lines,
        style = chrome?.style ?: fallbackStyle,
        initialCamera = region,
        cameraTarget = cameraTarget,
        cameraTargetVersion = cameraTargetVersion,
        colorSchemePreference = colorSchemePreference,
        isOffline = isOffline,
        showLabels = chrome?.showLabels ?: true,
        clusteringEnabled = chrome?.clusteringEnabled ?: true,
        northLocked = chrome?.northLocked ?: false,
        showsUserLocation = true,
        showsScale = true,
        onPointClick = onPointClick,
        modifier = Modifier.fillMaxSize(),
    )
    MapCornerControls(
        onCenterAll = {
            region?.let { framed ->
                cameraTarget = framed
                cameraTargetVersion += 1
            }
        },
        locationService = locationService,
        knownLocation = {
            userLocation?.let { LatLng(it.latitude, it.longitude) }
        },
        onFrameUser = { framed ->
            cameraTarget = framed
            cameraTargetVersion += 1
        },
        chrome = chrome,
        styleDisabled = styleDisabled,
        styleDisabledReason = styleDisabledReason,
        betweenCenterAndLocate = betweenCenterAndLocate,
    )
}
