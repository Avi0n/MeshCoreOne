package one.meshcore.shared.settings.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.map.isDenied
import one.meshcore.shared.settings.AdvertLocationPolicy
import one.meshcore.shared.settings.DeviceGpsState
import one.meshcore.shared.settings.DeviceLocationPreferences
import one.meshcore.shared.settings.LocationSettingsSnapshot
import one.meshcore.shared.ui.PreferenceHubCardPadding

/**
 * Settings → Location: stored coordinates, share-publicly policy, the map picker, and the GPS-source
 * choice that decides whether the node advertises its stored coordinate or its own live fix.
 *
 * The advert policy is written as a policy rather than a boolean because "share" alone is ambiguous: a
 * node fed by its own GPS must advertise the live fix ([AdvertLocationPolicy.SHARE]), while every other
 * configuration advertises the stored coordinate ([AdvertLocationPolicy.PREFS]).
 */
@Composable
internal fun LocationSettingsScreen(
    host: LocationSettingsHost,
    /** After the last retry, leave this page. */
    onMaxRetriesExceeded: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val snapshot = host.locationSettingsSnapshot()
    // Until a policy-taking write is supplied the boolean seam stands in, which can only
    // express PREFS/NONE; a device-GPS node then advertises its stored coordinate.
    val onSetAdvertLocationPolicy = host.setAdvertLocationPolicy
        ?: { policy -> host.setShareLocationPublicly(policy.isEnabled) }
    // Awaited, not detached: the screen reconciles the auto-update preference and the
    // advert policy only once the coordinate write is known to have stuck.
    val onLocationPicked: suspend (LatLng) -> Unit = { latLng ->
        host.setDeviceCoordinates(latLng.latitude, latLng.longitude)
    }
    val deviceId = host.connectedDeviceId
    val locationPreferences = host.deviceLocationPreferences
    val readDeviceGpsState = host.readDeviceGpsState
    val onSetDeviceGpsEnabled = host.setDeviceGpsEnabled
    val refreshLocationSnapshot: (suspend () -> LocationSettingsSnapshot?)? =
        host.refreshDeviceSettings?.let { refresh ->
            {
                refresh()
                host.locationSettingsSnapshot()
            }
        }
    val onRequestLocationPermission = host.onRequestLocationPermission
    val onOpenAppSettings = host.onOpenAppSettings
    val locationService = host.locationService
    val isDeviceConnected = host.isDeviceConnected
    val settingsErrorText = rememberSettingsErrorText()
    val retryAlert = rememberRetryAlertState()
    val scope = rememberCoroutineScope()
    var local by remember(snapshot) { mutableStateOf(snapshot) }
    var isSaving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showPicker by remember { mutableStateOf(false) }
    var autoUpdateLocation by remember { mutableStateOf(DeviceLocationPreferences.DEFAULT_AUTO_UPDATE_LOCATION) }
    var gpsSource by remember { mutableStateOf(DeviceLocationPreferences.DEFAULT_GPS_SOURCE) }
    var deviceGps by remember { mutableStateOf(DeviceGpsState.unsupported) }
    var showLocationDeniedAlert by remember { mutableStateOf(false) }

    val id = deviceId()
    LoadLocationPreferences(
        deviceId = id,
        preferences = locationPreferences,
        readDeviceGpsState = readDeviceGpsState,
        onAutoUpdate = { autoUpdateLocation = it },
        onGpsSource = { gpsSource = it },
        onDeviceGps = { deviceGps = it },
    )
    PollDeviceGpsUntilLocated(
        autoUpdateLocation = autoUpdateLocation,
        gpsSource = gpsSource,
        deviceGpsEnabled = deviceGps.isEnabled,
        refreshLocationSnapshot = refreshLocationSnapshot,
        onSnapshot = { local = it },
    )

    val bindings = LocationSettingsBindings(
        snapshot = { local },
        setSnapshot = { local = it },
        autoUpdateLocation = { autoUpdateLocation },
        setAutoUpdateLocation = { autoUpdateLocation = it },
        gpsSource = { gpsSource },
        setGpsSource = { gpsSource = it },
        deviceGps = { deviceGps },
        setDeviceGps = { deviceGps = it },
        setSaving = { isSaving = it },
        setErrorMessage = { errorMessage = it },
        setLocationDeniedAlert = { showLocationDeniedAlert = it },
        deviceId = deviceId,
        preferences = { locationPreferences },
        locationDenied = { locationService?.authorizationStatus?.value?.isDenied == true },
        onSetAdvertLocationPolicy = onSetAdvertLocationPolicy,
        onSetDeviceGpsEnabled = onSetDeviceGpsEnabled,
        onLocationPicked = onLocationPicked,
        onRequestLocationPermission = onRequestLocationPermission,
        onMaxRetriesExceeded = onMaxRetriesExceeded,
        scope = scope,
        retryAlert = retryAlert,
        settingsErrorText = settingsErrorText,
    )

    if (
        LocationPickerCover(
            visible = showPicker,
            snapshot = local,
            locationService = locationService,
            isRadioReady = isDeviceConnected,
            onSave = { picked -> bindings.persistManualCoordinate(picked) },
            onCancel = { showPicker = false },
            modifier = modifier.fillMaxSize(),
        )
    ) {
        return
    }

    val snap = local
    if (snap == null) {
        SettingsNoDeviceEmpty(modifier.fillMaxSize().padding(PreferenceHubCardPadding))
        return
    }

    LocationSettingsBody(
        snap = snap,
        autoUpdateLocation = autoUpdateLocation,
        gpsSource = gpsSource,
        deviceGps = deviceGps,
        isSaving = isSaving,
        isDeviceConnected = isDeviceConnected,
        onOpenPicker = { showPicker = true },
        onShareChange = { enabled -> bindings.writeAdvertPolicy(share = enabled, onFailure = {}) },
        onAutoUpdateChange = bindings::handleAutoUpdateChange,
        onGpsSourceChange = bindings::handleGpsSourceChange,
        onDeviceGpsToggle = bindings::handleDeviceGpsToggle,
        modifier = modifier,
    )
    LocationDeniedAlert(
        visible = showLocationDeniedAlert,
        onDismiss = { showLocationDeniedAlert = false },
        onOpenSettings = onOpenAppSettings,
    )
    SettingsWriteFeedback(retryAlert, errorMessage) { errorMessage = null }
}
