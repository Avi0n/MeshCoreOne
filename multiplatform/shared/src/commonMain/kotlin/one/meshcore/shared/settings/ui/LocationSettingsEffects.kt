package one.meshcore.shared.settings.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import one.meshcore.shared.settings.DeviceGpsState
import one.meshcore.shared.settings.DeviceLocationPreferences
import one.meshcore.shared.settings.GpsSource
import one.meshcore.shared.settings.LocationSettingsSnapshot
import one.meshcore.shared.settings.adoptRadioGpsAsSource
import one.meshcore.shared.settings.shouldPollDeviceGps

/** iOS polls `refreshDeviceInfo` every 3s until lat/lon is non-zero. */
private const val DEVICE_GPS_POLL_MS = 3_000L

@Composable
internal fun LoadLocationPreferences(
    deviceId: String?,
    preferences: DeviceLocationPreferences?,
    readDeviceGpsState: (suspend () -> DeviceGpsState)?,
    onAutoUpdate: (Boolean) -> Unit,
    onGpsSource: (GpsSource) -> Unit,
    onDeviceGps: (DeviceGpsState) -> Unit,
) {
    LaunchedEffect(deviceId, preferences, readDeviceGpsState) {
        val prefs = preferences
        val id = deviceId
        if (prefs != null && id != null) {
            onAutoUpdate(prefs.isAutoUpdateLocationEnabled(id))
            onGpsSource(prefs.gpsSource(id))
        }
        val state = readDeviceGpsState?.let { read ->
            runLocationSettingsCommand(onFailure = { null }) { read() }
        }
            ?: DeviceGpsState.unsupported
        onDeviceGps(state)
        if (prefs != null && id != null && adoptRadioGpsAsSource(state.isEnabled, prefs.hasSetGpsSource(id))) {
            onGpsSource(GpsSource.DEVICE)
            prefs.setGpsSource(GpsSource.DEVICE, id)
        }
    }
}

@Composable
internal fun PollDeviceGpsUntilLocated(
    autoUpdateLocation: Boolean,
    gpsSource: GpsSource,
    deviceGpsEnabled: Boolean,
    refreshLocationSnapshot: (suspend () -> LocationSettingsSnapshot?)?,
    onSnapshot: (LocationSettingsSnapshot) -> Unit,
) {
    val shouldPoll = shouldPollDeviceGps(
        autoUpdateLocation = autoUpdateLocation,
        gpsSource = gpsSource,
        deviceGpsEnabled = deviceGpsEnabled,
        hasRefresh = refreshLocationSnapshot != null,
    )
    LaunchedEffect(shouldPoll) {
        if (!shouldPoll) return@LaunchedEffect
        val refresh = refreshLocationSnapshot ?: return@LaunchedEffect
        while (true) {
            delay(DEVICE_GPS_POLL_MS)
            val next = runLocationSettingsCommand(onFailure = { null }) { refresh() }
            if (next != null) onSnapshot(next)
            if (next != null && next.hasLocation) break
        }
    }
}
