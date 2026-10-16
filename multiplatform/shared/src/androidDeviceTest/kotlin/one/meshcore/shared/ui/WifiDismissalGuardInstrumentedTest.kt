package one.meshcore.shared.ui

import android.view.KeyEvent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_thread_back_accessibility
import one.meshcore.shared.resources.onboarding_wifi_connection_title
import one.meshcore.shared.resources.settings_wifi_edit_title
import org.jetbrains.compose.resources.stringResource
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WifiDismissalGuardInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun nestedBackRespectsConnectingInCreateAndEditModes() = assertGuard(systemBack = false)

    @Test
    fun androidBackRespectsConnectingInCreateAndEditModes() = assertGuard(systemBack = true)

    private fun assertGuard(systemBack: Boolean) {
        val shown = mutableStateOf(false)
        val editing = mutableStateOf(false)
        val connecting = mutableStateOf(true)
        var dismissals = 0
        var connects = 0
        lateinit var back: String
        lateinit var title: String
        compose.setContent {
            back = stringResource(Res.string.chats_thread_back_accessibility)
            title = stringResource(
                if (editing.value) Res.string.settings_wifi_edit_title else Res.string.onboarding_wifi_connection_title,
            )
            MaterialTheme {
                Text(PARENT)
                if (shown.value) {
                    WiFiConnectDialog(
                        onDismiss = {
                            dismissals++
                            shown.value = false
                        },
                        connecting = connecting.value,
                        onConnect = { _, _ -> connects++ },
                        initialHost = HOST,
                        initialPort = PORT,
                        isEditing = editing.value,
                        error = if (editing.value) ERROR else null,
                    )
                }
            }
        }
        listOf(false, true).forEachIndexed { index, edit ->
            compose.runOnIdle {
                editing.value = edit
                connecting.value = true
                shown.value = true
            }
            compose.waitForIdle()
            compose.onNodeWithText(title).assertIsDisplayed()
            pressBack(back, systemBack)
            compose.onAllNodes(isDialog()).assertCountEquals(1)
            compose.runOnIdle {
                assertEquals(index, dismissals)
                assertEquals(0, connects)
            }
            compose.runOnIdle { connecting.value = false }
            compose.onNodeWithText(title).assertIsDisplayed()
            pressBack(back, systemBack)
            compose.onAllNodes(isDialog()).assertCountEquals(0)
            compose.onNodeWithText(PARENT).assertIsDisplayed()
            compose.runOnIdle {
                assertEquals(index + 1, dismissals)
                assertEquals(0, connects)
            }
        }
    }

    private fun pressBack(description: String, systemBack: Boolean) {
        val button = compose.onNodeWithContentDescription(description).assertIsDisplayed()
        if (systemBack) {
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        } else {
            button.performClick()
        }
        compose.waitForIdle()
    }

    private companion object {
        const val PARENT = "Parent content"
        const val HOST = "192.168.1.20"
        const val PORT = "5000"
        const val ERROR = "Connection attempt failed"
    }
}
