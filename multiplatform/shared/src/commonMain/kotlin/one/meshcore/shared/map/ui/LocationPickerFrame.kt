package one.meshcore.shared.map.ui

import kotlinx.coroutines.flow.StateFlow
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.map.MapCameraRegion
import one.meshcore.shared.map.MapPoint
import one.meshcore.shared.map.PinStyle

/** Span the picker frames a known position at, matching the iOS `loadCurrentLocation` region. */
internal const val DEFAULT_SPAN_DEG = 0.01

internal const val PICKER_MARKER_ID = "location-picker-pin"

/** Saved when nothing is selected; the radio reads all-zero coordinates as "no manual location". */
internal val CLEARED_LOCATION = LatLng(0.0, 0.0)

/** A known position opens already framed. No position leaves the camera unset. */
internal fun locationPickerInitialCamera(position: LatLng?): MapCameraRegion? = position?.let {
    MapCameraRegion(center = it, spanLatDeg = DEFAULT_SPAN_DEG, spanLonDeg = DEFAULT_SPAN_DEG)
}

/** The phone fix comes from the location service. Without one, the picker has no fix. */
internal fun locationPickerPhoneFixFlow(
    serviceFlow: StateFlow<LatLng?>?,
    fallback: StateFlow<LatLng?>,
): StateFlow<LatLng?> = serviceFlow ?: fallback

/**
 * Applies the framing decision. A fix frames the map; a missing fix asks for one; anything else
 * leaves the camera where it is.
 */
internal fun applyLocationPickerFraming(
    framing: LocationPickerDeviceFraming,
    phoneFix: LatLng?,
    requestFix: () -> Unit,
    frameOn: (MapCameraRegion) -> Unit,
) {
    when (framing) {
        LocationPickerDeviceFraming.NONE -> Unit
        LocationPickerDeviceFraming.REQUEST_FIX -> requestFix()
        LocationPickerDeviceFraming.FRAME_ON_FIX -> locationPickerInitialCamera(phoneFix)?.let(frameOn)
    }
}

/** The selected pin is the only marker. An empty selection draws none. */
internal fun locationPickerPoints(selected: LatLng?): List<MapPoint> = selected?.let { coord ->
    listOf(
        MapPoint(
            id = PICKER_MARKER_ID,
            coordinate = coord,
            pinStyle = PinStyle.CROSSHAIR,
            label = null,
            isClusterable = false,
        ),
    )
}.orEmpty()

/** An empty pin saves the all-zero position the radio reads as no manual location. */
internal fun locationPickerSaveCoordinate(selected: LatLng?): LatLng = selected ?: CLEARED_LOCATION

/** A successful write closes the picker. A refusal stays open with the radio's error. */
internal fun locationPickerAfterSave(
    result: LocationPickerSaveResult,
    onDismiss: () -> Unit,
    onStay: (Throwable) -> Unit,
) {
    when (result) {
        LocationPickerSaveResult.Dismiss -> onDismiss()
        is LocationPickerSaveResult.Stay -> onStay(result.error)
    }
}
