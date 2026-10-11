// AndroidOnly: WP-407 Unconnected-link diagnostics report optional hardware/permission/power honestly.
package com.meshcoreone.android.app.platform

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import com.meshcoreone.android.core.ble.BluetoothAvailability

internal fun bluetoothAvailability(context: Context): BluetoothAvailability {
    if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) return BluetoothAvailability.Unavailable
    if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
        return BluetoothAvailability.Unauthorized
    }
    return try {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return BluetoothAvailability.Unavailable
        if (adapter.isEnabled) BluetoothAvailability.Ready else BluetoothAvailability.PoweredOff
    } catch (_: SecurityException) { BluetoothAvailability.Unauthorized }
}
