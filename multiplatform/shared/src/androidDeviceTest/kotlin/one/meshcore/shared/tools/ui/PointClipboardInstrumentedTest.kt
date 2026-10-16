package one.meshcore.shared.tools.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.android_overflow_menu
import one.meshcore.shared.resources.tools_line_of_sight_copy_coordinates
import one.meshcore.shared.ui.HoldingClipboard
import org.jetbrains.compose.resources.stringResource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PointClipboardInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    private val visible = mutableStateOf(true)
    private val clipboard = HoldingClipboard()
    private val feedback = mutableListOf<HapticFeedbackType>()
    private val otherActions = mutableListOf<String>()
    private lateinit var overflowLabel: String
    private lateinit var copyLabel: String

    @After
    fun releaseWrite() {
        clipboard.release.complete(Unit)
    }

    @Test
    fun coordinateCopySurvivesPopupDismissalAndConfirmsAfterCompletion() {
        showPoint()
        copyCoordinates()
        compose.runOnIdle {
            assertEquals(1, clipboard.entries.size)
            assertEquals(
                "-1.23456, 2.34567",
                requireNotNull(clipboard.entries.single()).clipData.getItemAt(0).text.toString(),
            )
            assertEquals(0, clipboard.cancelled)
            assertEquals(0, clipboard.completed)
            assertTrue(feedback.isEmpty())
            assertTrue(otherActions.isEmpty())
            clipboard.release.complete(Unit)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, clipboard.completed)
            assertEquals(listOf(HapticFeedbackType.Confirm), feedback)
            assertTrue(otherActions.isEmpty())
        }
    }

    @Test
    fun removingPointCancelsCopyWithoutFeedbackOrOtherActions() {
        showPoint()
        copyCoordinates()
        compose.runOnIdle {
            assertEquals(1, clipboard.entries.size)
            assertEquals(0, clipboard.cancelled)
            assertTrue(feedback.isEmpty())
            visible.value = false
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, clipboard.cancelled)
            assertEquals(0, clipboard.completed)
            assertTrue(feedback.isEmpty())
            assertTrue(otherActions.isEmpty())
            clipboard.release.complete(Unit)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(0, clipboard.completed)
            assertTrue(feedback.isEmpty())
            assertTrue(otherActions.isEmpty())
        }
    }

    private fun showPoint() {
        compose.setContent {
            overflowLabel = stringResource(Res.string.android_overflow_menu)
            copyLabel = stringResource(Res.string.tools_line_of_sight_copy_coordinates)
            CompositionLocalProvider(
                LocalClipboard provides clipboard,
                LocalHapticFeedback provides object : HapticFeedback {
                    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                        feedback += hapticFeedbackType
                    }
                },
                LocalUriHandler provides object : UriHandler {
                    override fun openUri(uri: String) {
                        otherActions += "uri:$uri"
                    }
                },
            ) {
                MaterialTheme {
                    if (visible.value) {
                        PointActionButtons(
                            pointLabel = "Clipboard fixture",
                            coordinate = COORDINATE,
                            shareText = { otherActions += "share:$it" },
                            isEditing = false,
                            relocateEnabled = true,
                            onRelocate = { otherActions += "relocate" },
                            onToggleEdit = { otherActions += "edit" },
                            onClear = { otherActions += "clear" },
                        )
                    }
                }
            }
        }
    }

    private fun copyCoordinates() {
        compose.onNodeWithContentDescription(overflowLabel).performClick()
        compose.onAllNodes(isPopup()).assertCountEquals(1)
        compose.onNodeWithText(copyLabel).performClick()
        compose.onAllNodes(isPopup()).assertCountEquals(0)
    }

    private companion object {
        val COORDINATE = LatLng(-1.234567, 2.345678)
    }
}
