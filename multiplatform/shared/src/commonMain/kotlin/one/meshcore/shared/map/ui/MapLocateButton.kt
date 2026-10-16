package one.meshcore.shared.map.ui

import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import one.meshcore.shared.map.CENTER_ON_USER_SPAN_DEG
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.map.LocationService
import one.meshcore.shared.map.MapCameraRegion
import one.meshcore.shared.map.locateTapCoordinate
import one.meshcore.shared.map.userLocateCameraRegion
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.map_controls_center_on_my_location
import one.meshcore.shared.ui.icons.AppIcons
import org.jetbrains.compose.resources.stringResource

/**
 * Locate control shared by LOS, Trace, and path map sheets. Frames [onFrame] with a tight
 * region around the tap's resolved coordinate (iOS `MapControlsToolbar` location button).
 */
@Composable
fun MapLocateButton(
    locationService: LocationService?,
    knownLocation: () -> LatLng?,
    onFrame: (MapCameraRegion) -> Unit,
    modifier: Modifier = Modifier,
    freshFixOnly: Boolean = false,
    spanDeg: Double = CENTER_ON_USER_SPAN_DEG,
) {
    val scope = rememberCoroutineScope()
    SmallFloatingActionButton(
        onClick = {
            scope.launch {
                val coordinate = locateTapCoordinate(
                    known = knownLocation(),
                    locationService = locationService,
                    freshFixOnly = freshFixOnly,
                ) ?: return@launch
                onFrame(userLocateCameraRegion(coordinate, spanDeg))
            }
        },
        containerColor = FloatingActionButtonDefaults.containerColor,
        modifier = modifier,
    ) {
        Icon(
            imageVector = AppIcons.MyLocation,
            contentDescription = stringResource(Res.string.map_controls_center_on_my_location),
        )
    }
}
