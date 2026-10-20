// AndroidOnly: WP-502 Actual API33/34/36 permission/service/public-CDM facts; never reconfigure a radio or remove a bond.
package com.meshcoreone.android.uitests

import android.Manifest
import android.companion.CompanionDeviceManager
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meshcoreone.android.core.connectivity.permissions.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlatformApiBoundaryDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun requireActualTargetedApi() {
        assertTrue("Requires actual API33/34/36, not a lower-API proxy", Build.VERSION.SDK_INT in setOf(33, 34, 36))
    }

    @Test fun actualNotificationDenialIsAdvisoryAndLanRemainsIndependentBeforeApi37() {
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
        val snapshot = AndroidPermissionState(context).snapshot()
        assertEquals(Build.VERSION.SDK_INT, snapshot.sdkInt)
        val notification = ConnectivityPermissionPolicy.evaluate(
            ConnectivityFeature.ConnectionNotification, snapshot, PairingMode.ScanFallback,
        )
        assertEquals(FeatureReadiness.Ready(setOf(Advisory.NotificationsHidden)), notification)
        assertEquals(FeatureReadiness.Ready(), ConnectivityPermissionPolicy.evaluate(
            ConnectivityFeature.LanConnection, snapshot, PairingMode.ScanFallback,
        ))
        assertFalse(snapshot.granted.contains(ConnectivityPermission.ACCESS_LOCAL_NETWORK))
    }

    @Test fun actualConnectedDeviceRegistrationAndPublicBondRemovalBoundaryMatchTheApi() {
        val service = context.packageManager.getServiceInfo(
            ComponentName(context.packageName, "com.meshcoreone.android.core.connectivity.service.ConnectedDeviceService"), 0,
        )
        assertFalse(service.exported)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE, service.foregroundServiceType)
        if (Build.VERSION.SDK_INT >= 34) assertEquals(
            PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission("android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE"),
        )
        assertEquals(Build.VERSION.SDK_INT >= 36, CompanionDeviceManager::class.java.methods.any { it.name == "removeBond" })
    }
}
