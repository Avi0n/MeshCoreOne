package one.meshcore.shared.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import one.meshcore.shared.ui.icons.AppIcons
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PreferenceHubChoiceRowsInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun radioSelectionUsesOneRowTargetAndDisabledChoicesRejectTouches() {
        var calls = 0
        compose.setContent {
            var selected by remember { mutableStateOf(FIRST) }
            MaterialTheme {
                PreferenceHubSection {
                    listOf(FIRST, SECOND, DISABLED).forEach { label ->
                        PreferenceHubRadioRow(label, selected == label, enabled = label != DISABLED) {
                            calls++
                            selected = label
                        }
                    }
                }
            }
        }
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton), true)
            .assertCountEquals(CHOICES)
        compose.onAllNodes(hasClickAction(), useUnmergedTree = true).assertCountEquals(CHOICES)
        compose.onNodeWithText(FIRST).assertIsSelected()
        compose.onNodeWithText(SECOND).assertIsNotSelected().performClick().assertIsSelected()
        compose.onNodeWithText(FIRST).assertIsNotSelected()
        compose.onNodeWithText(DISABLED).assertIsNotEnabled().performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, calls) }
    }

    @Test
    fun clickingTheRadioGlyphInvokesTheRowExactlyOnce() {
        var calls = 0
        var density = 0f
        compose.setContent {
            density = LocalDensity.current.density
            MaterialTheme { PreferenceHubSection { PreferenceHubRadioRow(FIRST, false) { calls++ } } }
        }
        compose.onNodeWithText(FIRST).performTouchInput {
            click(Offset(RADIO_CENTER * density, center.y))
        }
        compose.runOnIdle { assertEquals(1, calls) }
    }

    @Test
    fun radioAndLinkRowsFillTheCardAndRetainItsBackground() {
        var density = 0f
        var opened = 0
        compose.setContent {
            density = LocalDensity.current.density
            MaterialTheme(colorScheme = lightColorScheme(surfaceContainerLow = Color.Magenta)) {
                Column(Modifier.fillMaxWidth().testTag(ROOT)) {
                    PreferenceHubSection {
                        PreferenceHubRadioRow(FIRST, false) {}
                        PreferenceHubLinkRow(LINK, AppIcons.Link) { opened++ }
                    }
                }
            }
        }
        val root = compose.onNodeWithTag(ROOT).fetchSemanticsNode().boundsInRoot
        listOf(FIRST, LINK).forEach { label ->
            val row = compose.onNodeWithText(label)
            val bounds = row.fetchSemanticsNode().boundsInRoot
            assertEquals(root.left + INSET * density, bounds.left, PIXEL_TOLERANCE)
            assertEquals(root.right - INSET * density, bounds.right, PIXEL_TOLERANCE)
            val pixels = row.captureToImage().toPixelMap()
            assertEquals(Color.Magenta, pixels[pixels.width / 2, pixels.height / 2])
        }
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription), true)
            .assertCountEquals(0)
        compose.onNodeWithText(LINK).performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    private companion object {
        const val FIRST = "First"
        const val SECOND = "Second"
        const val DISABLED = "Disabled"
        const val LINK = "External"
        const val ROOT = "Rows"
        const val CHOICES = 3
        const val INSET = 16f
        const val RADIO_CENTER = 28f
        const val PIXEL_TOLERANCE = 1f
    }
}
