package one.meshcore.shared.settings.ui

import android.os.Build
import android.view.View
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import one.meshcore.shared.ui.PreferenceHubSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RadioOptionPickerInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    private val selected = mutableStateOf(INITIAL)
    private var selectionCalls = 0
    private var density = 0f
    private lateinit var composeView: View

    @Test
    fun selectedValueSitsBelowHeadlineInFullWidthSharedCardRow() {
        setPicker()
        val row = compose.onNodeWithContentDescription(rowDescription(INITIAL))
        val bounds = row.fetchSemanticsNode().boundsInRoot
        val root = compose.onNodeWithTag(ROOT).fetchSemanticsNode().boundsInRoot
        assertEquals(root.left + INSET * density, bounds.left, HALF_PIXEL)
        assertEquals(root.right - INSET * density, bounds.right, HALF_PIXEL)
        val headline = compose.onNodeWithText(LABEL, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val value = compose.onNodeWithText(INITIAL.toString(), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("Selected value must sit below the headline", value.top >= headline.bottom)
        assertEquals(headline.left, value.left, HALF_PIXEL)
        val pixels = row.captureToImage().toPixelMap()
        assertEquals(Color.Magenta, pixels[pixels.width - EDGE_SAMPLE, pixels.height / 2])
        row.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, HINT))
        val id = row.fetchSemanticsNode().id
        compose.runOnIdle {
            val provider = requireNotNull(composeView.accessibilityNodeProvider)
            val info = requireNotNull(provider.createAccessibilityNodeInfo(id))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                assertEquals(HINT, info.stateDescription.toString())
            }
            assertTrue(info.isEnabled)
            assertTrue(info.isClickable)
        }
        compose.waitUntil(NATIVE_TIMEOUT) { nativeDescriptionCount(rowDescription(INITIAL)) == 1 }
    }

    @Test
    fun menuSelectionUpdatesValueOnceAndDismissesPopup() {
        setPicker()
        compose.onNodeWithContentDescription(rowDescription(INITIAL)).performClick()
        compose.onAllNodes(isPopup()).assertCountEquals(1)
        OPTIONS.forEach { option ->
            compose.onNode(hasContentDescription(spoken(option)) and hasAnyAncestor(isPopup())).assertExists()
        }
        compose.onNode(hasContentDescription(spoken(NEXT)) and hasAnyAncestor(isPopup())).performClick()
        compose.onAllNodes(isPopup()).assertCountEquals(0)
        compose.onNodeWithContentDescription(rowDescription(NEXT)).assertExists()
        compose.runOnIdle {
            assertEquals(NEXT, selected.value)
            assertEquals(1, selectionCalls)
        }
    }

    @Test
    fun disabledRowRejectsTouchesWithoutOpeningMenu() {
        setPicker(enabled = false)
        compose.onNodeWithContentDescription(rowDescription(INITIAL))
            .assertIsNotEnabled().performTouchInput { click() }
        compose.onAllNodes(isPopup()).assertCountEquals(0)
        compose.runOnIdle {
            assertEquals(INITIAL, selected.value)
            assertEquals(0, selectionCalls)
        }
    }

    private fun setPicker(enabled: Boolean = true) {
        compose.setContent {
            composeView = LocalView.current
            density = LocalDensity.current.density
            MaterialTheme(colorScheme = lightColorScheme(surfaceContainerLow = Color.Magenta)) {
                Column(Modifier.fillMaxWidth().testTag(ROOT)) {
                    PreferenceHubSection {
                        RadioOptionPicker(
                            label = LABEL,
                            selectedLabel = selected.value.toString(),
                            selectedAccessibilityLabel = spoken(selected.value),
                            accessibilityHint = HINT,
                            options = OPTIONS,
                            optionLabel = { it.toString() },
                            optionAccessibilityLabel = ::spoken,
                            enabled = enabled,
                            onSelect = {
                                selectionCalls++
                                selected.value = it
                            },
                        )
                    }
                }
            }
        }
    }

    private fun nativeDescriptionCount(description: String): Int {
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow ?: return 0
        val pending = ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
        pending.add(root)
        var count = 0
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            if (node.contentDescription?.toString() == description) count++
            repeat(node.childCount) { index -> node.getChild(index)?.let(pending::add) }
        }
        return count
    }

    private fun spoken(value: Int): String = "$value kilohertz"
    private fun rowDescription(value: Int): String = "$LABEL, ${spoken(value)}"

    private companion object {
        const val INITIAL = 250
        const val NEXT = 500
        val OPTIONS = listOf(125, INITIAL, NEXT)
        const val LABEL = "Bandwidth"
        const val HINT = "Choose the radio bandwidth"
        const val ROOT = "Picker fixture"
        const val INSET = 16
        const val HALF_PIXEL = 0.5f
        const val EDGE_SAMPLE = 2
        const val NATIVE_TIMEOUT = 5000L
    }
}
