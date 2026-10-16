package one.meshcore.shared.settings.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.settings_public_key_copy
import one.meshcore.shared.settings.KeyGenerationService
import one.meshcore.shared.ui.HoldingClipboard
import org.jetbrains.compose.resources.stringResource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GeneratedIdentityClipboardInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    private val visible = mutableStateOf(true)
    private val clipboard = HoldingClipboard()
    private val feedback = mutableListOf<HapticFeedbackType>()
    private lateinit var copyLabel: String

    @After
    fun releaseWrite() {
        clipboard.release.complete(Unit)
    }

    @Test
    fun publicKeyCopyConfirmsOnlyAfterCompletion() {
        showPreview()
        copyAt(PUBLIC_BUTTON)
        assertPending(PUBLIC_HEX)
        releaseAndAssertConfirmed()
    }

    @Test
    fun privateKeyCopyConfirmsOnlyAfterCompletion() {
        showPreview()
        copyAt(PRIVATE_BUTTON)
        assertPending(PRIVATE_HEX)
        releaseAndAssertConfirmed()
    }

    @Test
    fun removingPreviewCancelsPendingCopyWithoutFeedback() {
        showPreview()
        copyAt(PUBLIC_BUTTON)
        assertPending(PUBLIC_HEX)
        compose.runOnIdle { visible.value = false }
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

    private fun copyAt(index: Int) {
        compose.onAllNodesWithText(copyLabel)[index].performClick()
    }

    private fun assertPending(expected: String) {
        compose.runOnIdle {
            assertEquals(1, clipboard.entries.size)
            assertEquals(
                expected,
                requireNotNull(clipboard.entries.single()).clipData.getItemAt(0).text.toString(),
            )
            assertEquals(0, clipboard.completed)
            assertEquals(0, clipboard.cancelled)
            assertTrue(feedback.isEmpty())
        }
    }

    private fun releaseAndAssertConfirmed() {
        compose.runOnIdle { clipboard.release.complete(Unit) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, clipboard.completed)
            assertEquals(0, clipboard.cancelled)
            assertEquals(listOf(HapticFeedbackType.Confirm), feedback)
        }
    }

    private fun showPreview() {
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
                    if (visible.value) {
                        GeneratedIdentityPreview(
                            KeyGenerationService.GeneratedIdentity(
                                expandedPrivateKey = PRIVATE_KEY,
                                publicKey = PUBLIC_KEY,
                            ),
                        )
                    }
                }
            }
        }
        compose.onNodeWithText(PUBLIC_HEX).assertExists()
        compose.onNodeWithText(PRIVATE_HEX).assertExists()
        compose.onAllNodesWithText(copyLabel).assertCountEquals(COPY_BUTTON_COUNT)
    }

    private companion object {
        const val PUBLIC_BUTTON = 0
        const val PRIVATE_BUTTON = 1
        const val COPY_BUTTON_COUNT = 2
        const val PUBLIC_HEX = "A3FF"
        const val PRIVATE_HEX = "0012"
        val PUBLIC_KEY = byteArrayOf(0xA3.toByte(), 0xFF.toByte())
        val PRIVATE_KEY = byteArrayOf(0x00, 0x12)
    }
}
