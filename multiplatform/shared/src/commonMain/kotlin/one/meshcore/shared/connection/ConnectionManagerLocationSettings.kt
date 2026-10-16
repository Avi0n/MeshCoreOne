package one.meshcore.shared.connection

import one.meshcore.shared.battery.BatteryOcv
import one.meshcore.shared.battery.OcvEditorState
import one.meshcore.shared.settings.AdvertLocationPolicy
import one.meshcore.shared.settings.LocationSettingsSnapshot

/**
 * Sets share-location-publicly via setOtherParams with no GPS context, so it can only express
 * PREFS or NONE. A caller that knows the location settings writes the policy itself through
 * [setAdvertLocationPolicy]; a node fed by its own GPS needs [AdvertLocationPolicy.SHARE].
 */
suspend fun ConnectionManager.setShareLocationPublicly(enabled: Boolean) =
    setAdvertLocationPolicy(AdvertLocationPolicy.fromSharePublicly(enabled))

/**
 * Writes the advertisement location policy via setOtherParams, preserving the other other-params
 * fields from the Device row / live SelfInfo, then persists it on the Device row.
 */
suspend fun ConnectionManager.setAdvertLocationPolicy(policy: AdvertLocationPolicy) {
    writeOtherParams { existing, info ->
        ConnectionManager.OtherParamsWrite(
            params = OtherParams.live(existing, info).copy(advertisementLocationPolicy = policy.rawValue),
            owned = OtherParams::describeAdvertLocationPolicy,
            persist = { it.copy(advertLocationPolicy = policy.rawValue) },
        )
    }
}

/**
 * Local battery-curve (OCV) editor state for the last/connected device row. App-side only —
 * does not talk to the radio. `null` when no device row is available.
 */
suspend fun ConnectionManager.deviceOcvEditorState(): OcvEditorState? {
    val deviceId = lastConnectionStore?.deviceId() ?: return null
    val device = database.deviceDao().getById(deviceId) ?: return null
    return BatteryOcv.loadEditorState(device.ocvPreset, device.customOCVArrayString)
}

/**
 * Persists OCV preset/custom array on the Device row (iOS `DeviceService.updateOCVSettings`).
 * Local-only; no MeshCore command.
 */
suspend fun ConnectionManager.updateOCVSettings(preset: String, customArray: String?) {
    val deviceId = lastConnectionStore?.deviceId() ?: error("No device row")
    val existing = database.deviceDao().getById(deviceId) ?: error("No device row")
    database.deviceDao().upsert(
        existing.copy(ocvPreset = preset, customOCVArrayString = customArray),
    )
}

/**
 * Location fields for Settings → Location. `null` when disconnected or the Device row is missing.
 */
suspend fun ConnectionManager.locationSettingsSnapshot(): LocationSettingsSnapshot? {
    val deviceId = lastConnectionStore?.deviceId() ?: return null
    val device = database.deviceDao().getById(deviceId) ?: return null
    return LocationSettingsSnapshot(
        latitude = device.latitude,
        longitude = device.longitude,
        advertLocationPolicyRaw = device.advertLocationPolicy,
    )
}

/**
 * Writes a picker-, CLI-, or self-advert position onto the last-connection Device row so Settings →
 * Location, the map self-pin, and backup export see the same coordinates the radio just accepted.
 * iOS `ConnectionManager.updateDevice(from:)` does this after `setLocationVerified`.
 */
suspend fun ConnectionManager.persistDeviceCoordinates(latitude: Double, longitude: Double) {
    val existing = deviceRow() ?: return
    persistDeviceSettingsAfterRadioAck(logger, FAILED_PERSIST_DEVICE_COORDINATES) {
        database.deviceDao().upsert(existing.copy(latitude = latitude, longitude = longitude))
    }
}
