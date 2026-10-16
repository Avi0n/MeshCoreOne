package one.meshcore.android

import one.meshcore.shared.battery.BatteryOcv
import one.meshcore.shared.battery.OCVPreset
import one.meshcore.shared.connection.setAdvertLocationPolicy
import one.meshcore.shared.connection.setBlePin
import one.meshcore.shared.connection.setMultiAcks
import one.meshcore.shared.connection.setPathHashMode
import one.meshcore.shared.connection.setShareLocationPublicly
import one.meshcore.shared.connection.setTelemetryModes
import one.meshcore.shared.connection.updateOCVSettings
import one.meshcore.shared.connection.updateWifiEndpoint
import one.meshcore.shared.settings.AdvertLocationPolicy

/** Writes telemetry-sharing modes (base/location/environment) to the connected radio. */
suspend fun AppContainer.setTelemetryModes(base: Int, location: Int, environment: Int) {
    connectionManager.setTelemetryModes(base, location, environment)
    refreshSettingsSnapshotCache(connected = true)
}

/** Writes the direct-message acknowledgment count (wire value 0 or 1) to the connected radio. */
suspend fun AppContainer.setMultiAcks(value: Int) {
    connectionManager.setMultiAcks(value)
    refreshSettingsSnapshotCache(connected = true)
}

/** Writes the routing key-prefix hash size (firmware v10+) to the connected radio. */
suspend fun AppContainer.setPathHashMode(mode: Int) {
    connectedPathHashMode.applyVerifiedWrite(mode) { connectionManager.setPathHashMode(it) }
    refreshSettingsSnapshotCache(connected = true)
}

/** Writes a custom BLE pairing PIN (live radio); radio typically reboots. */
suspend fun AppContainer.setBlePin(pin: Long) {
    connectionManager.setBlePin(pin)
    refreshSettingsSnapshotCache(connected = true)
}

/** Updates stored WiFi host/port for the last device row. */
suspend fun AppContainer.updateWifiEndpoint(host: String, port: Int) {
    connectionManager.updateWifiEndpoint(host, port)
    refreshSettingsSnapshotCache(connected = true)
}

/** Persists device OCV preset/values (app-side only). */
suspend fun AppContainer.updateDeviceOcv(preset: OCVPreset, values: List<Int>) {
    if (preset == OCVPreset.CUSTOM) {
        connectionManager.updateOCVSettings(
            preset = OCVPreset.CUSTOM.rawValue,
            customArray = BatteryOcv.encodeCustomArray(values),
        )
    } else {
        connectionManager.updateOCVSettings(preset = preset.rawValue, customArray = null)
    }
    refreshSettingsSnapshotCache(connected = true)
}

/** Share-location-publicly toggle over the live session. */
suspend fun AppContainer.setShareLocationPublicly(enabled: Boolean) {
    connectionManager.setShareLocationPublicly(enabled)
    refreshSettingsSnapshotCache(connected = true)
}

/** Writes the advertisement location policy the Location screen resolved (including "live GPS fix"). */
suspend fun AppContainer.setAdvertLocationPolicy(policy: AdvertLocationPolicy) {
    connectionManager.setAdvertLocationPolicy(policy)
    refreshSettingsSnapshotCache(connected = true)
}
