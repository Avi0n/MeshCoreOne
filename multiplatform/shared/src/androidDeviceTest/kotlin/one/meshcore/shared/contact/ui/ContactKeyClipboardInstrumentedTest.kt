package one.meshcore.shared.contact.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_qr_copied
import one.meshcore.shared.resources.contacts_qr_copy
import one.meshcore.shared.ui.HoldingClipboard
import org.jetbrains.compose.resources.stringResource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContactKeyClipboardInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    private val visible = mutableStateOf(true)
    private val clipboard = HoldingClipboard()
    private val feedback = mutableListOf<HapticFeedbackType>()
    private lateinit var copyLabel: String
    private lateinit var copiedLabel: String

    @After
    fun releaseWrite() {
        compose.mainClock.autoAdvance = true
        clipboard.release.complete(Unit)
    }

    @Test
    fun fullKeyCopyConfirmsAfterCompletionAndRestoresEnabledAction() {
        showKey()
        val copy = compose.onNodeWithText(copyLabel).performScrollTo().assertIsEnabled()
        compose.mainClock.autoAdvance = false
        copy.performClick()
        compose.mainClock.advanceTimeBy(PENDING_INTERVAL_MILLIS)
        compose.onNodeWithText(copyLabel).assertIsEnabled()
        compose.onNodeWithText(copiedLabel).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, clipboard.entries.size)
            assertEquals(
                ContactClipboardFixtures.COPIED_KEY,
                requireNotNull(clipboard.entries.single()).clipData.getItemAt(0).text.toString(),
            )
            assertEquals(0, clipboard.completed)
            assertTrue(feedback.isEmpty())
            clipboard.release.complete(Unit)
        }
        compose.waitUntil(timeoutMillis = WRITE_SETTLE_TIMEOUT_MILLIS) {
            compose.runOnIdle { clipboard.completed == 1 }
        }
        compose.mainClock.advanceTimeBy(COMPLETION_FRAMES_MILLIS)
        compose.onNodeWithText(copiedLabel).assertIsNotEnabled()
        compose.onNodeWithText(copyLabel).assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(HapticFeedbackType.Confirm), feedback) }
        compose.mainClock.advanceTimeBy(CONFIRMATION_VISIBLE_MILLIS)
        compose.onNodeWithText(copiedLabel).assertIsNotEnabled()
        compose.mainClock.advanceTimeBy(CONFIRMATION_SETTLE_MILLIS)
        compose.onNodeWithText(copyLabel).assertIsEnabled()
        compose.onNodeWithText(copiedLabel).assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(HapticFeedbackType.Confirm), feedback) }
    }

    @Test
    fun removingTechnicalSectionCancelsCopyWithoutConfirmation() {
        showKey()
        compose.onNodeWithText(copyLabel).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, clipboard.entries.size)
            assertEquals(0, clipboard.completed)
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
            copyLabel = stringResource(Res.string.contacts_qr_copy)
            copiedLabel = stringResource(Res.string.contacts_qr_copied)
            CompositionLocalProvider(
                LocalClipboard provides clipboard,
                LocalHapticFeedback provides object : HapticFeedback {
                    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                        feedback += hapticFeedbackType
                    }
                },
            ) {
                MaterialTheme {
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                        if (visible.value) ContactDetailTechnicalSection(ContactClipboardFixtures.contact, "Chat")
                    }
                }
            }
        }
    }

    private companion object {
        const val WRITE_SETTLE_TIMEOUT_MILLIS = 5_000L
        const val PENDING_INTERVAL_MILLIS = 1_000L
        const val COMPLETION_FRAMES_MILLIS = 32L
        const val CONFIRMATION_VISIBLE_MILLIS = 1_500L
        const val CONFIRMATION_SETTLE_MILLIS = 1_000L
    }
}
