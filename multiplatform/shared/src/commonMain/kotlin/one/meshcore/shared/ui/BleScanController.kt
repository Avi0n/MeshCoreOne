package one.meshcore.shared.ui

import kotlinx.coroutines.flow.StateFlow

/**
 * Drives the in-app BLE scan picker (the Android analogue of the macOS `DeviceScannerSheet`): start/stop
 * scanning and observe the discovered devices, sorted by the UI for a stable list.
 */
interface BleScanController {
    val availability: StateFlow<BleAvailability>
    val devices: StateFlow<List<ScannedDevice>>

    fun startScan()

    fun stopScan()
}
