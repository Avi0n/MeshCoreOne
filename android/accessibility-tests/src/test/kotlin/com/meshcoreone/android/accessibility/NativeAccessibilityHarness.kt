// AndroidOnly: WP-503 Robolectric native window with actual resource font scaling and unchanged shared accessibility oracles.
package com.meshcoreone.android.accessibility

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.provider.Settings
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController

abstract class NativeAccessibilityHarness : NativeAccessibilityAssertions() {
    @get:Rule public override val compose = createEmptyComposeRule()
    protected lateinit var host: ActivityController<ComponentActivity>
    override val activity: ComponentActivity get() = host.get()

    @Suppress("DEPRECATION")
    @Before fun attachNativeWindowAtSystemFont200() {
        val application = ApplicationProvider.getApplicationContext<Context>()
        assertTrue(Settings.Global.putFloat(application.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f))
        assertEquals(0f, Settings.Global.getFloat(application.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE), 0f)
        application.applicationInfo.flags = application.applicationInfo.flags or ApplicationInfo.FLAG_SUPPORTS_RTL
        val configuration = Configuration(application.resources.configuration).apply {
            fontScale = 2f
            setLayoutDirection(java.util.Locale.forLanguageTag("ar"))
        }
        application.resources.updateConfiguration(configuration, application.resources.displayMetrics)
        host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.get().applicationInfo.flags = host.get().applicationInfo.flags or ApplicationInfo.FLAG_SUPPORTS_RTL
        host.get().resources.updateConfiguration(configuration, host.get().resources.displayMetrics)
        host.get().window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        host.get().actionBar?.hide()
        host.windowFocusChanged(true)
        assertEquals(2f, host.get().resources.configuration.fontScale, 0f)
    }

    @After fun closeNativeWindow() {
        host.windowFocusChanged(false)
        host.pause().stop().destroy()
    }

    override fun content(body: @Composable () -> Unit) {
        host.get().setContent(content = body)
    }
}
