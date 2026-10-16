package one.meshcore.shared.connection

/**
 * Read model for Settings → Connection: live transport, BLE PIN, and WiFi reachability from the
 * Device row.
 */
data class ConnectionSettingsSnapshot(
    val transportType: TransportType?,
    val blePin: Long,
    val wifiHost: String?,
    val wifiPort: Int?,
    val hasBluetoothMethod: Boolean,
    val hasWifiMethod: Boolean,
) {
    /** Firmware treats factory default and zero as "no custom PIN configured". */
    val isCustomBlePin: Boolean
        get() = blePin != BLE_PIN_UNSET && blePin != BLE_PIN_FIRMWARE_DEFAULT

    companion object {
        const val BLE_PIN_FIRMWARE_DEFAULT = 123_456L
        const val BLE_PIN_UNSET = 0L
    }
}

/**
 * iOS `ConnectionSettingsView` shows WiFi only while the live transport is WiFi; otherwise it
 * shows Bluetooth (including while disconnected). BLE-only radios must not see a host/port editor.
 */
fun showsWifiConnectionSettings(transportType: TransportType?): Boolean = transportType == TransportType.WIFI
