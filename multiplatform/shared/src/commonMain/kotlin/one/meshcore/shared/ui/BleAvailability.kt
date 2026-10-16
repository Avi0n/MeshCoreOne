package one.meshcore.shared.ui

/** BLE adapter readiness, mirroring the iOS `bluetoothAvailability` the macOS scan picker branches on. */
enum class BleAvailability {
    READY,
    POWERED_OFF,
    UNAUTHORIZED,
}
