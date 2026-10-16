package one.meshcore.shared.ui

/** A radio the BLE picker discovered while scanning. */
data class ScannedDevice(val id: String, val name: String?, val rssi: Int?)
