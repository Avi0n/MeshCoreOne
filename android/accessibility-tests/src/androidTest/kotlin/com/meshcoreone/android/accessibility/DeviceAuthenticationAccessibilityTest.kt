// AndroidOnly: WP-503 Independent real-Android secure-dialog calibration for the reproduced SDK31 JVM idling failure.
package com.meshcoreone.android.accessibility

import android.os.Build
import android.provider.Settings
import android.view.View
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meshcoreone.android.MainActivity
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceAuthenticationAccessibilityTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Before fun requireActualSystemFont200AndSelectedApi() {
        val selected = requireNotNull(InstrumentationRegistry.getArguments().getString("expectedApi")) {
            "Pass the actually selected API with -e expectedApi 31 or 37"
        }.toInt()
        assertTrue(selected == 31 || selected == 37)
        assertEquals(selected, Build.VERSION.SDK_INT)
        assertEquals(2f, Settings.System.getFloat(compose.activity.contentResolver, Settings.System.FONT_SCALE), 0f)
        assertEquals(2f, compose.activity.resources.configuration.fontScale, 0f)
        assertEquals(0f, Settings.Global.getFloat(compose.activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE), 0f)
        compose.runOnUiThread { compose.activity.window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL }
    }

    @Test fun frameworkSecureDialogPrefillIsMaskedAndIdleAtSystemFont200() {
        val password = mutableStateOf("")
        compose.runOnUiThread {
            compose.activity.setContent {
                MeshCoreTheme(motionScale = 0f) {
                    Surface(Modifier.fillMaxSize()) {
                        LaunchedEffect(Unit) { password.value = "cached-audit" }
                        AlertDialog(
                            onDismissRequest = {},
                            title = { Text("Secure field control") },
                            text = {
                                OutlinedTextField(password.value, { password.value = it },
                                    modifier = Modifier.testTag("secure-field-control"),
                                    label = { Text("Password") },
                                    visualTransformation = PasswordVisualTransformation())
                            },
                            confirmButton = { TextButton({}) { Text("Control") } },
                        )
                    }
                }
            }
        }
        val field = compose.onNodeWithTag("secure-field-control").assertIsDisplayed().fetchSemanticsNode()
        assertEquals("\u2022".repeat(12), field.config[SemanticsProperties.EditableText].text)
        assertTrue(field.config.contains(SemanticsProperties.Password))
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("Secure field control").performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
            assertTrue(it(layouts))
        }
        assertEquals(1, layouts.size)
        assertEquals(2f, layouts.single().layoutInput.density.fontScale, 0f)
        assertEquals(LayoutDirection.Rtl, layouts.single().layoutInput.layoutDirection)
        println("WP503_DEVICE_SECURE_CONTROL|sdk=${Build.VERSION.SDK_INT}|fontScale=2.0|rtl=true|masked=12|bounds=${field.boundsInRoot}")
    }
}
