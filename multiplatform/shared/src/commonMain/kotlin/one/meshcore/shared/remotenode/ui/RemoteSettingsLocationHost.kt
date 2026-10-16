package one.meshcore.shared.remotenode.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import one.meshcore.shared.map.LocationService
import one.meshcore.shared.map.ui.LocationPickerCover

/**
 * Resumes expanded settings sections, then shows the location picker over the form. While the picker
 * is visible the form is not composed, so a save or cancel returns to the same sections.
 */
@Composable
internal fun RemoteSettingsLocationHost(
    loadKey: Any,
    onLoad: () -> Unit,
    latitudeText: String,
    longitudeText: String,
    locationService: LocationService?,
    isRadioReady: Boolean,
    onSave: (latitude: Double, longitude: Double) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (openLocationPicker: () -> Unit) -> Unit,
) {
    var showLocationPicker by remember { mutableStateOf(false) }
    LaunchedEffect(loadKey) { onLoad() }
    if (
        LocationPickerCover(
            visible = showLocationPicker,
            latitudeText = latitudeText,
            longitudeText = longitudeText,
            locationService = locationService,
            isRadioReady = isRadioReady,
            onSave = onSave,
            onCancel = { showLocationPicker = false },
            modifier = modifier,
        )
    ) {
        return
    }
    content { showLocationPicker = true }
}
