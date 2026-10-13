// AndroidOnly: WP-503 Execute runtime-dependent assertions on selected real Android APIs, not substituted JVM JNI/windows.
package com.meshcoreone.android.accessibility

import android.os.Build
import android.provider.Settings
import android.view.View
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.meshcoreone.android.MainActivity
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule

abstract class DeviceAccessibilityHarness : NativeAccessibilityAssertions() {
    @OptIn(ExperimentalTestApi::class)
    @get:Rule public override val compose = createAndroidComposeRule<MainActivity>(
        effectContext = StandardTestDispatcher(),
    )
    override val activity: MainActivity get() = compose.activity

    @Before fun requireActualSystemFont200AndSelectedApi() {
        val selected = requireNotNull(InstrumentationRegistry.getArguments().getString("expectedApi")) {
            "Pass the actually selected API with -e expectedApi 31 or 37"
        }.toInt()
        assertTrue(selected == 31 || selected == 37)
        assertEquals(selected, Build.VERSION.SDK_INT)
        assertEquals(2f, Settings.System.getFloat(activity.contentResolver, Settings.System.FONT_SCALE), 0f)
        assertEquals(2f, activity.resources.configuration.fontScale, 0f)
        assertEquals(0f, Settings.Global.getFloat(activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE), 0f)
        compose.runOnUiThread { activity.window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL }
        println("WP503_DEVICE_PROFILE|sdk=${Build.VERSION.SDK_INT}|fontScale=2.0|density=${activity.resources.displayMetrics.density}")
    }

    override fun content(body: @Composable () -> Unit) {
        compose.runOnUiThread { activity.setContent(content = body) }
    }

    @Composable
    override fun AuditSurface(body: @Composable () -> Unit) {
        Surface(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).testTag("audit-root"),
            color = MaterialTheme.colorScheme.background) {
            body()
        }
    }
}
