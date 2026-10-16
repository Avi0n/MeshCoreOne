package one.meshcore.shared.map.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.map.LocationService
import one.meshcore.shared.map.MapCameraRegion
import one.meshcore.shared.map.MapStyleSelection
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.map_controls_center_all
import one.meshcore.shared.ui.icons.AppIcons
import org.jetbrains.compose.resources.stringResource

private val MAP_CORNER_INSET = 8.dp
private val MAP_CORNER_GAP = 8.dp

/**
 * Bottom-end stack on a full-screen map sheet: center-all, an optional extra control, locate, and
 * the style menu when chrome is available.
 */
@Composable
fun BoxScope.MapCornerControls(
    onCenterAll: () -> Unit,
    locationService: LocationService?,
    knownLocation: () -> LatLng?,
    onFrameUser: (MapCameraRegion) -> Unit,
    chrome: MapDisplayChrome?,
    styleDisabled: (MapStyleSelection) -> Boolean,
    styleDisabledReason: (MapStyleSelection) -> String?,
    betweenCenterAndLocate: @Composable () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(MAP_CORNER_INSET),
        verticalArrangement = Arrangement.spacedBy(MAP_CORNER_GAP),
        horizontalAlignment = Alignment.End,
    ) {
        SmallFloatingActionButton(
            onClick = onCenterAll,
            containerColor = FloatingActionButtonDefaults.containerColor,
        ) {
            Icon(
                imageVector = AppIcons.LocationOn,
                contentDescription = stringResource(Res.string.map_controls_center_all),
            )
        }
        betweenCenterAndLocate()
        MapLocateButton(
            locationService = locationService,
            knownLocation = knownLocation,
            onFrame = onFrameUser,
        )
        if (chrome != null) {
            MapOptionsMenu(
                chrome = chrome,
                styleDisabled = styleDisabled,
                styleDisabledReason = styleDisabledReason,
            )
        }
    }
}
