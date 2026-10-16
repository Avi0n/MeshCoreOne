package one.meshcore.shared.chat.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import one.meshcore.shared.chat.pathdetail.MessagePathDisplay
import one.meshcore.shared.chat.pathdetail.PathHopRow
import one.meshcore.shared.chat.pathdetail.PathHopType
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_path_copy_accessibility
import one.meshcore.shared.resources.chats_path_copy_hint
import one.meshcore.shared.ui.HoldingClipboard
import org.jetbrains.compose.resources.stringResource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MessagePathClipboardInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    private val visible = mutableStateOf(true)
    private val path = mutableStateOf(DISPLAY)
    private val clipboard = HoldingClipboard()
    private val feedback = mutableListOf<HapticFeedbackType>()
    private lateinit var copyDescription: String

    @After
    fun releaseWrite() {
        clipboard.release.complete(Unit)
    }

    @Test
    fun copyUsesClipboardPathAndConfirmsAfterWriteCompletes() {
        showPath()
        compose.onNodeWithContentDescription(copyDescription).performClick()
        compose.runOnIdle {
            assertEquals(1, clipboard.entries.size)
            assertEquals(COPIED_PATH, requireNotNull(clipboard.entries.single()).clipData.getItemAt(0).text.toString())
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
    fun removingHopListCancelsPendingCopyWithoutFeedback() {
        showPath()
        compose.onNodeWithContentDescription(copyDescription).performClick()
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

    @Test
    fun hidingCopyRowKeepsPendingWriteOwnedByHopList() {
        showPath()
        compose.onNodeWithContentDescription(copyDescription).performClick()
        compose.runOnIdle {
            assertEquals(1, clipboard.entries.size)
            path.value = DIRECT_DISPLAY
        }
        compose.onNodeWithContentDescription(copyDescription).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(0, clipboard.cancelled)
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
    fun directPathHasNoCopyAction() {
        showPath(DIRECT_DISPLAY)
        compose.onNodeWithContentDescription(copyDescription).assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(clipboard.entries.isEmpty())
            assertTrue(feedback.isEmpty())
        }
    }

    private fun showPath(display: MessagePathDisplay = DISPLAY) {
        path.value = display
        compose.setContent {
            val label = stringResource(Res.string.chats_path_copy_accessibility)
            val hint = stringResource(Res.string.chats_path_copy_hint)
            copyDescription = "$label. $hint"
            CompositionLocalProvider(
                LocalClipboard provides clipboard,
                LocalHapticFeedback provides object : HapticFeedback {
                    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                        feedback += hapticFeedbackType
                    }
                },
            ) {
                MaterialTheme {
                    if (visible.value) MessagePathHopList(path.value)
                }
            }
        }
    }

    private companion object {
        const val COPIED_PATH = "A3,FF"
        val DISPLAY = MessagePathDisplay(
            available = true,
            rows = listOf(
                PathHopRow(PathHopType.Sender, "Sender", null, null),
                PathHopRow(PathHopType.Intermediate(1), "First hop", "A3", null),
                PathHopRow(PathHopType.Intermediate(2), "Second hop", "FF", null),
                PathHopRow(PathHopType.Receiver, "Receiver", null, null),
            ),
            hasIntermediateHops = true,
            pathString = "A3 → FF",
            pathStringForClipboard = COPIED_PATH,
        )
        val DIRECT_DISPLAY = DISPLAY.copy(
            rows = DISPLAY.rows.filterNot { it.type is PathHopType.Intermediate },
            hasIntermediateHops = false,
            pathString = "",
            pathStringForClipboard = "",
        )
    }
}
