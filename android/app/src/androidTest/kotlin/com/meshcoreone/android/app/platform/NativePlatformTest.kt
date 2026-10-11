// AndroidOnly: WP-407 Device-run native registration and manifest assertions; compilation alone is not execution.
package com.meshcoreone.android.app.platform

import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.meshcoreone.android.MainActivity
import com.meshcoreone.android.app.MeshCoreApplication
import com.meshcoreone.android.app.NavigationHostViewModel
import com.meshcoreone.android.core.contracts.AppTab
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings as S
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativePlatformTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun productionApplicationBuildsOneProcessGraphOnSupportedApi() = runBlocking {
        assertTrue("Unsupported test API", Build.VERSION.SDK_INT in 31..37)
        val app = compose.activity.application as MeshCoreApplication
        val first = withTimeout(30_000) { app.container.await() }
        assertSame(first, app.container.await())
        assertNotNull(first.settingsFeature)
        assertNotNull(first.chatsFeature)
        assertNotNull(first.themeService)
        val activity = compose.activity
        val info = activity.packageManager.getApplicationInfo(activity.packageName, 0)
        assertEquals(0, info.flags and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP)
        assertFalse("Precise location must not be required", activity.packageManager.getPackageInfo(
            activity.packageName, android.content.pm.PackageManager.GET_PERMISSIONS,
        ).requestedPermissions.orEmpty().contains(android.Manifest.permission.ACCESS_FINE_LOCATION))
    }
}
