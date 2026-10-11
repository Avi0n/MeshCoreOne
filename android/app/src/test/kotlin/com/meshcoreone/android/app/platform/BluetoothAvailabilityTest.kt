// AndroidOnly: WP-407 Optional BLE hardware and revoked connect permission under actual API-specific SDK sandboxes.
package com.meshcoreone.android.app.platform

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.ble.BluetoothAvailability
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 33, 37], application = Application::class)
class BluetoothAvailabilityTest {
    @Test fun missingHardwareAndRevokedPermissionAreNotReady() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val packages = shadowOf(application.packageManager)
        packages.setSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE, false)
        assertEquals(BluetoothAvailability.Unavailable, bluetoothAvailability(application))
        packages.setSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE, true)
        shadowOf(application).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        assertEquals(BluetoothAvailability.Unauthorized, bluetoothAvailability(application))
    }
}
