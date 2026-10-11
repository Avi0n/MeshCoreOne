// AndroidOnly: WP-302 Real OS IME/Back/inset assertions; not executed or credited by local native-host tests.
package com.meshcoreone.android.app.navigation

import android.os.Build
import android.view.WindowInsets
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.meshcoreone.android.MainActivity
import com.meshcoreone.android.app.NavigationHostViewModel
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationKeyboardBackTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun realImeBackDismissesKeyboardBeforePoppingDetailAndInsetsAreAppliedOnce() {
        assertTrue("Run on the admitted API31 or API37 test device", Build.VERSION.SDK_INT == 31 || Build.VERSION.SDK_INT == 37)
        lateinit var navigation: NavigationCoordinator
        compose.runOnUiThread {
            navigation = ViewModelProvider(compose.activity)[NavigationHostViewModel::class.java].navigation
            navigation.navigateToSetting(SettingsDetail.LANGUAGE)
            compose.activity.setContent {
                MeshCoreTheme(motionScale = 0f) {
                    NativeNavigationShell(navigation) { destination, _ ->
                        val paneModifier = if (destination is NavigationDestination.Setting) {
                            Modifier.fillMaxSize().testTag("os-pane")
                        } else Modifier.fillMaxSize()
                        Box(paneModifier) {
                            if (destination is NavigationDestination.Setting) {
                                var draft by remember { mutableStateOf("") }
                                OutlinedTextField(draft, { draft = it }, Modifier.testTag("os-editor"))
                            }
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("os-editor").performClick().performTextInput("Synthetic keyboard draft")
        compose.waitUntil(10_000) {
            compose.activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
        }
        compose.onNodeWithTag("os-editor").assertIsFocused().assertIsDisplayed()
        val pane = compose.onNodeWithTag("os-pane").fetchSemanticsNode().boundsInWindow
        val decor = compose.activity.window.decorView
        val insets = requireNotNull(decor.rootWindowInsets)
        val ime = insets.getInsets(WindowInsets.Type.ime()).bottom
        assertTrue("Actual IME must occupy screen space", ime > 0)
        assertEquals("IME inset must be applied once, not once per nested pane", decor.height - ime.toFloat(), pane.bottom, 2f)
        pressBack()
        compose.waitUntil(10_000) { decor.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == false }
        assertEquals(SettingsDetail.LANGUAGE, navigation.state.value.selectedSetting)
        assertEquals(2, navigation.state.value.activeStack.size)
        pressBack()
        compose.waitForIdle()
        assertNull(navigation.state.value.selectedSetting); assertEquals(1, navigation.state.value.activeStack.size)
    }
}
