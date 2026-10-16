package one.meshcore.shared.transport

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import one.meshcore.shared.ui.BleAvailability

/**
 * What a BLE scan needs from the user right now, derived from the two live inputs.
 *
 * Mirrors the Swift `ConnectionManager.bluetoothAvailability(for:)`, which maps the current
 * `CBManagerState` on every `centralManagerDidUpdateState` callback. The value is derived on each read
 * and never stored on failure, so a permission granted or an adapter switched on takes effect the next
 * time it is read. A missing permission outranks a powered-off adapter, matching CoreBluetooth, which
 * reports `.unauthorized` whatever the radio's power state.
 */
internal fun bleScanAvailability(scanPermissionGranted: Boolean, adapterEnabled: Boolean): BleAvailability = when {
    !scanPermissionGranted -> BleAvailability.UNAUTHORIZED
    !adapterEnabled -> BleAvailability.POWERED_OFF
    else -> BleAvailability.READY
}

/**
 * Whether the runtime permission a BLE scan needs on this API level is granted. API 31+ gates scanning
 * on `BLUETOOTH_SCAN`; below that the scan needs a location grant, and `BLUETOOTH`/`BLUETOOTH_ADMIN` are
 * install-time.
 */
internal fun Context.hasBleScanPermission(): Boolean {
    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Manifest.permission.BLUETOOTH_SCAN
    } else {
        Manifest.permission.ACCESS_FINE_LOCATION
    }
    return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}

/** API 31+ `BLUETOOTH_CONNECT`; install-time `BLUETOOTH` below that. */
fun Context.hasBleConnectPermission(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
    return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
}

/**
 * Handles (MACs) currently connected on this phone’s GATT stack — iOS
 * `systemConnectedPeripheralIDs`. Empty when the connect permission is missing.
 */
fun Context.systemConnectedBleHandles(): Set<String> {
    if (!hasBleConnectPermission()) return emptySet()
    return try {
        getSystemService(BluetoothManager::class.java)
            ?.getConnectedDevices(BluetoothProfile.GATT)
            ?.mapNotNull { it.address?.takeIf(String::isNotBlank) }
            ?.toSet()
            .orEmpty()
    } catch (_: SecurityException) {
        emptySet()
    }
}

/** Whether this device has a Bluetooth adapter and it is switched on. */
fun Context.bluetoothAdapterEnabled(): Boolean =
    getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true

/**
 * Calls [onChanged] whenever the Bluetooth adapter is switched on or off — the Android analogue of
 * `centralManagerDidUpdateState`. Closing the returned handle unregisters the receiver.
 */
fun Context.observeBluetoothAdapterState(onChanged: () -> Unit): AutoCloseable {
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = onChanged()
    }
    val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    } else {
        registerReceiver(receiver, filter)
    }
    return AutoCloseable { unregisterReceiver(receiver) }
}
