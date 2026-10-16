package one.meshcore.shared.settings.ui

import android.content.ClipDescription
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.settings_public_key_copy
import one.meshcore.shared.ui.HoldingClipboard
import org.jetbrains.compose.resources.stringResource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PublicKeyClipboardInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    private val visible = mutableStateOf(true)
    private val clipboard = HoldingClipboard()
    private val feedback = mutableListOf<HapticFeedbackType>()
    private lateinit var copyLabel: String

    @Test
    fun copyWritesUnspacedUppercaseKeyAndConfirmsOnlyAfterCompletion() {
        showKey()
        compose.onNodeWithText("A3 FF 00 12").assertExists()
        compose.onNodeWithText(copyLabel).performClick()
        compose.runOnIdle {
            assertEquals(1, clipboard.entries.size)
            val data = requireNotNull(clipboard.entries.single()).clipData
            assertEquals(1, data.itemCount)
            assertEquals("A3FF0012", data.getItemAt(0).text.toString())
            assertEquals("plain text", data.description.label.toString())
            assertTrue(data.description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN))
            assertEquals(0, clipboard.completed)
            assertTrue(feedback.isEmpty())
            clipboard.release.complete(Unit)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, clipboard.completed)
            assertEquals(listOf(HapticFeedbackType.Confirm), feedback)
        }
    }

    @Test
    fun removingScreenCancelsPendingWriteWithoutSuccessFeedback() {
        showKey()
        compose.onNodeWithText(copyLabel).performClick()
        compose.runOnIdle {
            assertEquals(1, clipboard.entries.size)
            assertTrue(feedback.isEmpty())
            visible.value = false
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, clipboard.cancelled)
            assertEquals(0, clipboard.completed)
            assertTrue(feedback.isEmpty())
            clipboard.release.complete(Unit)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(0, clipboard.completed)
            assertTrue(feedback.isEmpty())
        }
    }

    private fun showKey() {
        compose.setContent {
            copyLabel = stringResource(Res.string.settings_public_key_copy)
            CompositionLocalProvider(
                LocalClipboard provides clipboard,
                LocalHapticFeedback provides object : HapticFeedback {
                    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                        feedback += hapticFeedbackType
                    }
                },
            ) {
                MaterialTheme {
                    if (visible.value) PublicKeyScreen(PUBLIC_KEY)
                }
            }
        }
    }

    private companion object {
        val PUBLIC_KEY = byteArrayOf(0xA3.toByte(), 0xFF.toByte(), 0x00, 0x12)
    }
}
