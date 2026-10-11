// AndroidOnly: WP-407 Native scanner recovery/disposal with a deterministic camera effect; not physical camera evidence.
package com.meshcoreone.android.app.platform

import android.Manifest
import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.meshcoreone.android.app.navigation.AppQrScanner
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.designsystem.ThemeRegistry
import com.meshcoreone.android.core.l10n.generated.AppContactsStrings
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], qualifiers = "w360dp-h800dp")
class QrRecoveryTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun failedCameraRemountsOnExplicitGrantedRetryAndDeliversOnlyOnce() {
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val app = host.get().application as Application
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        var mounted = 0
        var active = 0
        val decoded = mutableListOf<String>()
        host.get().setContent {
            MeshCoreTheme(ThemeRegistry.default, motionScale = 0f) {
                AppQrScanner({}, { decoded += it }, camera = { deliver, fail ->
                    DisposableEffect(Unit) {
                        mounted++
                        active++
                        onDispose { active-- }
                    }
                    Button(onClick = fail) { Text("Synthetic camera failure") }
                    Button(onClick = { deliver("synthetic-channel"); deliver("synthetic-channel") }) {
                        Text("Synthetic camera decode")
                    }
                })
            }
        }
        compose.waitForIdle()
        assertEquals(1, mounted)
        compose.onNodeWithText("Synthetic camera failure").performClick()
        compose.waitForIdle()
        assertEquals(0, active)
        compose.onNodeWithText(app.getString(AppContactsStrings.contactsAddScanQR)).performClick()
        compose.waitForIdle()
        assertEquals(2, mounted)
        assertEquals(1, active)
        compose.onNodeWithText("Synthetic camera decode").performClick()
        assertEquals(listOf("synthetic-channel"), decoded)
        host.get().setContent {}
        compose.waitForIdle()
        assertEquals(0, active)
        host.pause().stop().destroy()
    }
}
