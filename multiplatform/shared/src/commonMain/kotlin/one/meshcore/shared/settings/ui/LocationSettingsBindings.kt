package one.meshcore.shared.settings.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.settings.AdvertLocationPolicy
import one.meshcore.shared.settings.DeviceGpsState
import one.meshcore.shared.settings.DeviceLocationPreferences
import one.meshcore.shared.settings.GpsSource
import one.meshcore.shared.settings.LocationSettingsSnapshot
import one.meshcore.shared.settings.manualCoordinateReconciliation
import one.meshcore.shared.settings.shouldBlockPhoneGpsPreference

/**
 * Live reads and writes for Settings → Location. Getters run when a save uses them, so a coroutine
 * started from an older composition still sees the toggles the user is looking at.
 */
internal data class LocationSettingsBindings(
    val snapshot: () -> LocationSettingsSnapshot?,
    val setSnapshot: (LocationSettingsSnapshot?) -> Unit,
    val autoUpdateLocation: () -> Boolean,
    val setAutoUpdateLocation: (Boolean) -> Unit,
    val gpsSource: () -> GpsSource,
    val setGpsSource: (GpsSource) -> Unit,
    val deviceGps: () -> DeviceGpsState,
    val setDeviceGps: (DeviceGpsState) -> Unit,
    val setSaving: (Boolean) -> Unit,
    val setErrorMessage: (String?) -> Unit,
    val setLocationDeniedAlert: (Boolean) -> Unit,
    val deviceId: () -> String?,
    val preferences: () -> DeviceLocationPreferences?,
    val locationDenied: () -> Boolean,
    val onSetAdvertLocationPolicy: suspend (AdvertLocationPolicy) -> Unit,
    val onSetDeviceGpsEnabled: (suspend (Boolean) -> Unit)?,
    val onLocationPicked: suspend (LatLng) -> Unit,
    val onRequestLocationPermission: () -> Unit,
    val onMaxRetriesExceeded: () -> Unit,
    val scope: CoroutineScope,
    val retryAlert: RetryAlertState,
    val settingsErrorText: (Throwable) -> String,
) {
    /** Writes the advert policy implied by the current share/GPS state, mirroring iOS. */
    suspend fun applyAdvertPolicy(share: Boolean, onFailure: () -> Unit) {
        val policy = AdvertLocationPolicy.forSharePublicly(
            share = share,
            autoUpdateLocation = autoUpdateLocation(),
            deviceHasGps = deviceGps().isSupported,
            gpsSource = gpsSource(),
        )
        val current = snapshot() ?: return
        if (current.advertLocationPolicyRaw == policy.rawValue) return
        runLocationSettingsCommand(
            onFailure = { error ->
                onFailure()
                throw error
            },
        ) {
            onSetAdvertLocationPolicy(policy)
            setSnapshot(current.copy(advertLocationPolicyRaw = policy.rawValue))
            retryAlert.reset()
        }
    }

    fun writeAdvertPolicy(share: Boolean, onFailure: () -> Unit) {
        setSaving(true)
        setErrorMessage(null)
        scope.launchRetryableSettingsWrite(
            report = SettingsWriteReport(
                retryAlert = retryAlert,
                onError = { setErrorMessage(it) },
                settingsErrorText = settingsErrorText,
                onRetry = { writeAdvertPolicy(share, onFailure) },
                onMaxRetriesExceeded = onMaxRetriesExceeded,
            ),
            onFinished = { setSaving(false) },
        ) {
            applyAdvertPolicy(share, onFailure)
        }
    }

    fun writeDeviceGps(enabled: Boolean, onSuccess: suspend () -> Unit = {}, onFailure: () -> Unit = {}) {
        val write = onSetDeviceGpsEnabled ?: return
        val previous = deviceGps()
        setSaving(true)
        setErrorMessage(null)
        scope.launch {
            try {
                runLocationSettingsCommand(
                    onFailure = { error ->
                        setDeviceGps(previous)
                        onFailure()
                        if (error.isRetryableSettingsError()) {
                            retryAlert.show(
                                message = settingsErrorText(error),
                                onRetry = { writeDeviceGps(enabled, onSuccess, onFailure) },
                                onMaxRetriesExceeded = onMaxRetriesExceeded,
                            )
                        } else {
                            setErrorMessage(settingsErrorText(error))
                        }
                    },
                ) {
                    write(enabled)
                    setDeviceGps(previous.copy(isEnabled = enabled))
                    retryAlert.reset()
                    onSuccess()
                }
            } finally {
                setSaving(false)
            }
        }
    }

    fun handleAutoUpdateChange(enabled: Boolean) {
        if (shouldBlockPhoneGpsPreference(enabled && gpsSource() == GpsSource.PHONE, locationDenied())) {
            setLocationDeniedAlert(true)
            return
        }
        setAutoUpdateLocation(enabled)
        scope.launch { persistAutoUpdate(enabled) }
        if (enabled && gpsSource() == GpsSource.PHONE) onRequestLocationPermission()
        if (gpsSource() == GpsSource.DEVICE) {
            if (enabled) {
                writeDeviceGps(
                    enabled = true,
                    onFailure = {
                        setAutoUpdateLocation(false)
                        scope.launch { persistAutoUpdate(false) }
                    },
                )
            } else if (deviceGps().isEnabled) {
                writeDeviceGps(enabled = false)
            }
        }
        if (snapshot()?.shareLocationPublicly == true) writeAdvertPolicy(share = true, onFailure = {})
    }

    fun handleGpsSourceChange(newValue: GpsSource) {
        val previous = gpsSource()
        if (newValue == previous) return
        if (shouldBlockPhoneGpsPreference(newValue == GpsSource.PHONE, locationDenied())) {
            setLocationDeniedAlert(true)
            return
        }
        setGpsSource(newValue)
        scope.launch { persistGpsSource(newValue) }
        if (newValue == GpsSource.PHONE) {
            onRequestLocationPermission()
            if (deviceGps().isEnabled) writeDeviceGps(enabled = false)
        } else if (autoUpdateLocation()) {
            writeDeviceGps(
                enabled = true,
                onFailure = {
                    setGpsSource(previous)
                    scope.launch { persistGpsSource(previous) }
                },
            )
        }
        if (snapshot()?.shareLocationPublicly == true) writeAdvertPolicy(share = true, onFailure = {})
    }

    fun handleDeviceGpsToggle(enabled: Boolean) {
        val shouldDisableAutoUpdate = !enabled && autoUpdateLocation() && gpsSource() == GpsSource.DEVICE
        writeDeviceGps(
            enabled = enabled,
            onSuccess = {
                if (!shouldDisableAutoUpdate) return@writeDeviceGps
                setAutoUpdateLocation(false)
                persistAutoUpdate(false)
                // Losing the device fix strands a SHARE policy on a node with no live position.
                if (snapshot()?.advertLocationPolicyRaw == AdvertLocationPolicy.SHARE.rawValue) {
                    writeAdvertPolicy(share = true, onFailure = {})
                }
            },
        )
    }

    /**
     * Writes a picked coordinate, then reconciles the state the write invalidated: it switches the
     * radio's on-board GPS off, so a device-fed auto-update and a live-fix advert policy both stop
     * describing the node. A failed write rolls the optimistic coordinate back. The picker surfaces
     * the error and stays open, matching iOS `LocationPickerView.saveLocation`.
     */
    suspend fun persistManualCoordinate(picked: LatLng) {
        val previous = snapshot()
        setSnapshot(
            previous?.copy(latitude = picked.latitude, longitude = picked.longitude)
                ?: LocationSettingsSnapshot(
                    latitude = picked.latitude,
                    longitude = picked.longitude,
                    advertLocationPolicyRaw = AdvertLocationPolicy.NONE.rawValue,
                ),
        )
        runLocationSettingsCommand(
            onFailure = { error ->
                setSnapshot(previous)
                throw error
            },
            onCancel = { cancelled ->
                setSnapshot(previous)
                throw cancelled
            },
        ) {
            onLocationPicked(picked)
            setDeviceGps(deviceGps().copy(isEnabled = false))
            val reconciliation = manualCoordinateReconciliation(
                autoUpdateLocation = autoUpdateLocation(),
                gpsSource = gpsSource(),
                advertLocationPolicyRaw = previous?.advertLocationPolicyRaw
                    ?: AdvertLocationPolicy.NONE.rawValue,
            )
            if (reconciliation.disableAutoUpdate) {
                setAutoUpdateLocation(false)
                persistAutoUpdate(false)
            }
            if (reconciliation.demoteAdvertPolicy) applyAdvertPolicy(share = true, onFailure = {})
        }
    }

    private suspend fun persistAutoUpdate(enabled: Boolean) {
        val id = deviceId()
        if (id != null) preferences()?.setAutoUpdateLocationEnabled(enabled, id)
    }

    private suspend fun persistGpsSource(source: GpsSource) {
        val id = deviceId()
        if (id != null) preferences()?.setGpsSource(source, id)
    }
}
