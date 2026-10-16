package one.meshcore.shared.map.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import one.meshcore.shared.map.LocationService

/**
 * Full-screen location picker for a settings form. Returns true when the picker is showing so the
 * caller can skip the form underneath.
 */
@Composable
internal fun LocationPickerCover(
    visible: Boolean,
    latitudeText: String,
    longitudeText: String,
    locationService: LocationService?,
    isRadioReady: Boolean,
    onSave: (latitude: Double, longitude: Double) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
): Boolean {
    if (!visible) return false
    LocationPickerScreen(
        initial = locationPickerInitialFromText(latitudeText, longitudeText),
        locationService = locationService,
        onSave = { picked -> onSave(picked.latitude, picked.longitude) },
        onCancel = onCancel,
        isRadioReady = isRadioReady,
        modifier = modifier.fillMaxSize(),
    )
    return true
}
