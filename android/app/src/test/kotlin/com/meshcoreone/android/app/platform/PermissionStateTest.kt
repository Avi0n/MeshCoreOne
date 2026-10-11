// AndroidOnly: WP-407 API-boundary permission state regressions under Robolectric, not device/API acceptance.
package com.meshcoreone.android.app.platform

import android.Manifest
import android.app.Application
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.feature.settings.device.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 33, 37], application = Application::class)
class PermissionStateTest {
    @Test fun grantedThenRevokedCoarseLocationHasAnHonestDeniedState() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.getSharedPreferences("platform_permission_requests", Context.MODE_PRIVATE).edit().clear().commit()
        val permission = SettingsPermissions(app, { error("No notification prompt expected") }, { error("No location prompt expected") })
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        permission.refresh()
        assertEquals(LocationAuthorization.AUTHORIZED, permission.location.authorization.value)
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        permission.refresh()
        assertEquals(LocationAuthorization.DENIED, permission.location.authorization.value)
        permission.close()
    }

    @Test fun notificationDenialAndRevocationDoNotLaunchLocationOrConnectAnything() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.getSharedPreferences("platform_permission_requests", Context.MODE_PRIVATE).edit().clear().commit()
        var notificationPrompts = 0
        val permission = SettingsPermissions(app, { notificationPrompts++ }, { error("Notification request must not request location") })
        if (Build.VERSION.SDK_INT >= 33) {
            shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            assertEquals(NotificationAuthorization.AUTHORIZED, permission.notifications.status())
            shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
            assertEquals(NotificationAuthorization.DENIED, permission.notifications.status())
        }
        assertEquals(0, notificationPrompts)
        permission.close()
    }
}
