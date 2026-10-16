package one.meshcore.shared.tools.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.map.LocationService
import one.meshcore.shared.map.MapStyleSelection
import one.meshcore.shared.map.USER_LOCATE_SPAN_DEG
import one.meshcore.shared.map.normalizedWorldLongitude
import one.meshcore.shared.map.ui.MapDisplayChrome
import one.meshcore.shared.map.ui.MapLibreHost
import one.meshcore.shared.map.ui.MapLocateButton
import one.meshcore.shared.map.ui.MapOptionsMenu
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.settings_location_picker_drop_pin
import one.meshcore.shared.resources.settings_location_picker_latitude
import one.meshcore.shared.resources.settings_location_picker_longitude
import one.meshcore.shared.resources.tools_line_of_sight_point_a
import one.meshcore.shared.resources.tools_line_of_sight_point_b
import one.meshcore.shared.tools.LineOfSightViewModel
import one.meshcore.shared.tools.LosPointId
import one.meshcore.shared.tools.acceptUserCameraRegion
import one.meshcore.shared.tools.clearAnalysisResults
import one.meshcore.shared.tools.frameOnUser
import one.meshcore.shared.tools.restoreCameraAfterMapRecreation
import one.meshcore.shared.tools.selectPoint
import one.meshcore.shared.tools.setPointA
import one.meshcore.shared.tools.setPointB
import one.meshcore.shared.tools.setRepeaterOffPath
import one.meshcore.shared.tools.toggleContact
import one.meshcore.shared.ui.AppOutlinedTextField
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun LosMapCanvas(
    viewModel: LineOfSightViewModel,
    mapChrome: MapDisplayChrome,
    isOffline: Boolean,
    locationService: LocationService?,
    styleDisabled: (MapStyleSelection) -> Boolean,
    styleDisabledReason: (MapStyleSelection) -> String?,
    overlayBottomInset: Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    val revision by viewModel.revision.collectAsStateWithLifecycle()
    check(revision >= 0)

    LaunchedEffect(viewModel) { viewModel.restoreCameraAfterMapRecreation() }
    Box(modifier) {
        MapLibreHost(
            points = viewModel.mapPoints,
            lines = viewModel.mapLines,
            style = mapChrome.style,
            initialCamera = viewModel.cameraRegion,
            cameraTarget = viewModel.cameraTarget,
            cameraTargetVersion = viewModel.cameraTargetVersion,
            colorSchemePreference = mapChrome.colorScheme,
            isOffline = isOffline,
            showLabels = mapChrome.showLabels,
            clusteringEnabled = mapChrome.clusteringEnabled,
            northLocked = mapChrome.northLocked,
            showsUserLocation = true,
            showsScale = true,
            onCameraIdle = { region ->
                viewModel.acceptUserCameraRegion(region)
            },
            onPointClick = { id ->
                val contact = viewModel.repeatersWithLocation.firstOrNull { it.id.value == id }
                if (contact != null) {
                    viewModel.toggleContact(contact)
                }
            },
            onMapClick = { latLng -> placeOrRelocate(viewModel, latLng) },
            onMapLongPress = { latLng -> placeOrRelocate(viewModel, latLng) },
            modifier = Modifier.fillMaxSize(),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(
                    end = 12.dp,
                    bottom = overlayBottomInset + LOS_MAP_FAB_BOTTOM_GAP,
                ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.End,
        ) {
            if (locationService != null) {
                MapLocateButton(
                    locationService = locationService,
                    knownLocation = { null },
                    onFrame = { region -> viewModel.frameOnUser(region.center) },
                    freshFixOnly = true,
                    spanDeg = USER_LOCATE_SPAN_DEG,
                )
            }
            MapOptionsMenu(
                chrome = mapChrome,
                styleDisabled = styleDisabled,
                styleDisabledReason = styleDisabledReason,
            )
        }
        FilledTonalButton(
            onClick = {
                val target = placementCoordinate(
                    center = viewModel.cameraRegion?.center ?: DEFAULT_MAP_CENTER,
                    pointA = viewModel.pointA?.coordinate,
                    placingB = viewModel.pointA != null &&
                        viewModel.pointB == null &&
                        viewModel.relocatingPoint == null,
                )
                placeOrRelocate(viewModel, target)
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = overlayBottomInset + LOS_DROP_PIN_BOTTOM_GAP),
        ) {
            Text(stringResource(Res.string.settings_location_picker_drop_pin))
        }
    }
}

private fun placeOrRelocate(viewModel: LineOfSightViewModel, latLng: LatLng) {
    val coordinate = latLng.normalizedWorldLongitude()
    val relocating = viewModel.relocatingPoint
    if (relocating != null) {
        handleRelocation(viewModel, relocating, coordinate)
    } else {
        viewModel.selectPoint(at = coordinate)
        viewModel.shouldAutoZoomOnNextResult = true
    }
}

/** When placing B and [center] still equals A, offset so the VM accepts a distinct endpoint. */
private fun placementCoordinate(center: LatLng, pointA: LatLng?, placingB: Boolean): LatLng {
    val normalizedCenter = center.normalizedWorldLongitude()
    val same = placingB &&
        pointA != null &&
        kotlin.math.abs(normalizedCenter.latitude - pointA.latitude) < COORD_SAME_EPSILON &&
        kotlin.math.abs(normalizedCenter.longitude - pointA.longitude) < COORD_SAME_EPSILON
    return if (same) {
        LatLng(
            latitude = (pointA.latitude + POINT_B_NUDGE_DEG).coerceIn(-LATITUDE_MAX, LATITUDE_MAX),
            longitude = (pointA.longitude + POINT_B_NUDGE_DEG).coerceIn(-LONGITUDE_MAX, LONGITUDE_MAX),
        )
    } else {
        normalizedCenter
    }
}

private fun handleRelocation(viewModel: LineOfSightViewModel, pointId: LosPointId, latLng: LatLng) {
    when (pointId) {
        LosPointId.POINT_A -> viewModel.setPointA(latLng)
        LosPointId.POINT_B -> viewModel.setPointB(latLng)
        LosPointId.REPEATER -> viewModel.setRepeaterOffPath(latLng)
    }
    viewModel.clearAnalysisResults()
    viewModel.relocatingPoint = null
}

@Composable
internal fun ManualCoordinateFields(viewModel: LineOfSightViewModel) {
    var lat by remember { mutableStateOf("37.7749") }
    var lon by remember { mutableStateOf("-122.4194") }
    AppOutlinedTextField(
        value = lat,
        onValueChange = { lat = it },
        label = { Text(stringResource(Res.string.settings_location_picker_latitude)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    AppOutlinedTextField(
        value = lon,
        onValueChange = { lon = it },
        label = { Text(stringResource(Res.string.settings_location_picker_longitude)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = {
            val la = lat.toDoubleOrNull() ?: return@Button
            val lo = lon.toDoubleOrNull() ?: return@Button
            viewModel.selectPoint(LatLng(la, lo))
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            stringResource(Res.string.tools_line_of_sight_point_a) + " / " +
                stringResource(Res.string.tools_line_of_sight_point_b),
        )
    }
}

internal const val LOS_DROP_PIN_BOTTOM_GAP_DP = 12f
internal const val LOS_MAP_FAB_BOTTOM_GAP_DP = 64f
private val LOS_DROP_PIN_BOTTOM_GAP = LOS_DROP_PIN_BOTTOM_GAP_DP.dp
private val LOS_MAP_FAB_BOTTOM_GAP = LOS_MAP_FAB_BOTTOM_GAP_DP.dp

private const val COORD_SAME_EPSILON = 1e-6
private const val POINT_B_NUDGE_DEG = 0.5
private const val LATITUDE_MAX = 90.0
private const val LONGITUDE_MAX = 180.0

private val DEFAULT_MAP_CENTER = LatLng(20.0, 0.0)
