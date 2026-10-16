package one.meshcore.shared.map.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import one.meshcore.shared.chat.ui.ThreadErrorDialog
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.map.LocationService
import one.meshcore.shared.map.MapStyleSelection
import one.meshcore.shared.map.formatFixed6
import one.meshcore.shared.map.isAuthorized
import one.meshcore.shared.remotenode.NodeLocationFix
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.settings_location_picker_clear_location
import one.meshcore.shared.resources.settings_location_picker_drop_pin
import one.meshcore.shared.resources.settings_location_picker_latitude
import one.meshcore.shared.resources.settings_location_picker_longitude
import one.meshcore.shared.resources.settings_location_picker_title
import one.meshcore.shared.resources.settings_node_set_location
import one.meshcore.shared.settings.ui.rememberSettingsErrorText
import one.meshcore.shared.ui.FilledButtonProgress
import one.meshcore.shared.ui.FullScreenOverlay
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.preferenceHubFrame
import one.meshcore.shared.ui.radioConnectionHint
import org.jetbrains.compose.resources.stringResource

/** Leave the picker after a successful write; stay and surface the error when the radio refuses. */
internal sealed class LocationPickerSaveResult {
    data object Dismiss : LocationPickerSaveResult()
    data class Stay(val error: Throwable) : LocationPickerSaveResult()
}

/**
 * Mirrors iOS `LocationPickerView.saveLocation`: a failed `onSave` keeps the picker open so the
 * responder can retry without losing the pin.
 */
@Suppress("TooGenericExceptionCaught")
internal suspend fun locationPickerSaveResult(save: suspend () -> Unit): LocationPickerSaveResult = try {
    save()
    LocationPickerSaveResult.Dismiss
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    LocationPickerSaveResult.Stay(error)
}

/**
 * The coordinate the picker opens with selected, or null when the node has none. All-zero and
 * out-of-range values both mean "no manual location" — the radio writes (0,0) for an unset position —
 * so neither may drop a pin, which would otherwise mark null island as the node's location and save it
 * back on the next tap. Mirrors the three cases of iOS `LocationPickerView.loadCurrentLocation`.
 */
internal fun locationPickerInitialSelection(initial: LatLng?): LatLng? =
    initial?.takeIf { NodeLocationFix.isValidFix(it.latitude, it.longitude) }

/** A settings draft only describes a location when both coordinate fields contain usable numbers. */
internal fun locationPickerInitialFromText(latitudeText: String, longitudeText: String): LatLng? {
    val latitude = latitudeText.toDoubleOrNull() ?: return null
    val longitude = longitudeText.toDoubleOrNull() ?: return null
    return locationPickerInitialSelection(LatLng(latitude, longitude))
}

/** What a picker with no saved position does with the phone's own fix. */
internal enum class LocationPickerDeviceFraming {
    NONE,

    /** Authorized, but no fix has arrived yet; ask for one. */
    REQUEST_FIX,

    /** Frame the map on the phone's fix, which also arms the drop-pin button. */
    FRAME_ON_FIX,
}

/**
 * Whether the phone's own fix should frame a picker the node gave no position to. The node's stored
 * position always wins, an unauthorized phone is never asked, a request already in flight is not
 * repeated, and a map the user has already moved keeps its framing. Mirrors iOS
 * `LocationPickerView.loadCurrentLocation` case 2 and the `currentLocation` `onChange` guard.
 */
internal fun locationPickerDeviceFraming(
    known: LatLng?,
    phoneFix: LatLng?,
    isAuthorized: Boolean,
    hasCamera: Boolean,
    isRequestingFix: Boolean,
): LocationPickerDeviceFraming = when {
    known != null || !isAuthorized -> LocationPickerDeviceFraming.NONE
    phoneFix == null -> if (isRequestingFix) {
        LocationPickerDeviceFraming.NONE
    } else {
        LocationPickerDeviceFraming.REQUEST_FIX
    }
    hasCamera -> LocationPickerDeviceFraming.NONE
    else -> LocationPickerDeviceFraming.FRAME_ON_FIX
}

/**
 * Map-based location picker: MapLibre host with drop-pin-at-center, coordinate readout, and save.
 * Presented as a full-screen dialog with Up so it covers Settings / node / room chrome instead of
 * stacking a second bar. Mirrors iOS `LocationPickerView` (center crosshair / drop pin, no glass).
 */
@Composable
fun LocationPickerScreen(
    initial: LatLng?,
    /**
     * The app-wide location manager, so a node with no stored position opens on the responder's own
     * position instead of the default world view. `null` leaves the picker on that default.
     */
    locationService: LocationService?,
    onSave: suspend (LatLng) -> Unit,
    onCancel: () -> Unit,
    isRadioReady: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val known = locationPickerInitialSelection(initial)
    var selected by remember(initial) { mutableStateOf(known) }
    // Null until the node's own position, the phone's fix, or a user pan frames the map. A seeded (0,0)
    // would both frame null island and hand it to the drop-pin button as the node's new position.
    var camera by remember(initial) { mutableStateOf(locationPickerInitialCamera(known)) }
    val noFix = remember { MutableStateFlow<LatLng?>(null) }
    val phoneFix by locationPickerPhoneFixFlow(locationService?.currentLocation, noFix).collectAsStateWithLifecycle()
    LaunchedEffect(locationService, known, phoneFix, camera) {
        val service = locationService ?: return@LaunchedEffect
        applyLocationPickerFraming(
            framing = locationPickerDeviceFraming(
                known = known,
                phoneFix = phoneFix,
                isAuthorized = service.isAuthorized,
                hasCamera = camera != null,
                isRequestingFix = service.isRequestingLocation.value,
            ),
            phoneFix = phoneFix,
            requestFix = { service.requestLocation() },
            frameOn = { camera = it },
        )
    }
    val points = locationPickerPoints(selected)
    val scope = rememberCoroutineScope()
    val settingsErrorText = rememberSettingsErrorText()
    var isSaving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val mapAppearance = LocalMapColorSchemePreference.current
    val persistPickedLocation = {
        if (!isSaving) {
            scope.launch {
                isSaving = true
                try {
                    val result = locationPickerSaveResult {
                        onSave(locationPickerSaveCoordinate(selected))
                    }
                    locationPickerAfterSave(
                        result,
                        onDismiss = onCancel,
                        onStay = { errorMessage = settingsErrorText(it) },
                    )
                } finally {
                    isSaving = false
                }
            }
        }
    }

    FullScreenOverlay(
        onDismiss = onCancel,
        title = stringResource(Res.string.settings_location_picker_title),
    ) { padding ->
        Column(modifier.fillMaxSize().padding(padding)) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                MapLibreHost(
                    points = points,
                    modifier = Modifier.fillMaxSize(),
                    style = MapStyleSelection.STANDARD,
                    colorSchemePreference = mapAppearance,
                    initialCamera = camera,
                    showsUserLocation = true,
                    onCameraIdle = { camera = it },
                    onMapClick = { selected = it },
                )
            }
            Column(
                Modifier.fillMaxWidth().preferenceHubFrame(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val coord = selected
                if (coord != null) {
                    val latLabel = stringResource(Res.string.settings_location_picker_latitude)
                    val lonLabel = stringResource(Res.string.settings_location_picker_longitude)
                    PreferenceHubSection(applyHorizontalPadding = false) {
                        ListItem(
                            headlineContent = { Text(latLabel) },
                            supportingContent = {
                                Text(
                                    text = formatFixed6(coord.latitude),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        ListItem(
                            headlineContent = { Text(lonLabel) },
                            supportingContent = {
                                Text(
                                    text = formatFixed6(coord.longitude),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                FilledTonalButton(
                    onClick = { camera?.let { selected = it.center } },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(Res.string.settings_location_picker_drop_pin))
                }
                if (coord != null) {
                    OutlinedButton(
                        onClick = { selected = null },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Text(stringResource(Res.string.settings_location_picker_clear_location))
                    }
                }
                Button(
                    onClick = { persistPickedLocation() },
                    enabled = isRadioReady && !isSaving,
                    modifier = Modifier
                        .fillMaxWidth()
                        .radioConnectionHint(isRadioReady),
                ) {
                    if (isSaving) {
                        FilledButtonProgress()
                    }
                    Text(stringResource(Res.string.settings_node_set_location))
                }
            }
        }
    }
    errorMessage?.let { message ->
        ThreadErrorDialog(message = message, onDismiss = { errorMessage = null })
    }
}
