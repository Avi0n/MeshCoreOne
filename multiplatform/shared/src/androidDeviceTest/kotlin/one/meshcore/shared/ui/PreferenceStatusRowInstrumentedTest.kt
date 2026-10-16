package one.meshcore.shared.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class PreferenceStatusRowInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun statusRowsFillTheCardWithThemeTypographyAndTransparentBackgrounds() {
        var density = 0f
        compose.setContent {
            density = LocalDensity.current.density
            MaterialTheme(
                colorScheme = lightColorScheme(onSurfaceVariant = Color.Cyan, surfaceContainerLow = Color.Magenta),
                typography = Typography(bodySmall = TextStyle(fontSize = FONT_SIZE.sp, lineHeight = LINE_HEIGHT.sp)),
            ) {
                Column(Modifier.fillMaxWidth().testTag(ROOT)) {
                    PreferenceHubSection {
                        PreferenceHubStatusRow(DEFAULT, Modifier.testTag(DEFAULT))
                        PreferenceHubStatusRow(OVERRIDE, Modifier.testTag(OVERRIDE), color = Color.Green)
                    }
                }
            }
        }
        val root = compose.onNodeWithTag(ROOT).fetchSemanticsNode().boundsInRoot
        listOf(DEFAULT to Color.Cyan, OVERRIDE to Color.Green).forEach { (label, color) ->
            val row = compose.onNodeWithTag(label)
            val bounds = row.fetchSemanticsNode().boundsInRoot
            val text = compose.onNodeWithText(label, useUnmergedTree = true)
            val textBounds = text.fetchSemanticsNode().boundsInRoot
            assertEquals("Row starts at card edge", root.left + INSET * density, bounds.left, PIXEL_TOLERANCE)
            assertEquals("Row ends at card edge", root.right - INSET * density, bounds.right, PIXEL_TOLERANCE)
            assertEquals("Single-line row height", ROW_HEIGHT * density, bounds.height, PIXEL_TOLERANCE)
            assertEquals("Text inset", bounds.left + INSET * density, textBounds.left, PIXEL_TOLERANCE)
            val layouts = mutableListOf<TextLayoutResult>()
            text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val style = layouts.single().layoutInput.style
            assertEquals(FONT_SIZE.sp, style.fontSize)
            assertEquals(LINE_HEIGHT.sp, style.lineHeight)
            val pixels = row.captureToImage().toPixelMap()
            assertTrue(
                "Transparent row shows the card",
                matches(pixels[pixels.width / 2, pixels.height / 2], Color.Magenta),
            )
            assertTrue(
                "Text paints its requested color",
                (0 until pixels.width).any { x -> (0 until pixels.height).any { y -> matches(pixels[x, y], color) } },
            )
        }
    }

    private fun matches(actual: Color, expected: Color): Boolean = abs(actual.red - expected.red) < COLOR_TOLERANCE &&
        abs(actual.green - expected.green) < COLOR_TOLERANCE &&
        abs(actual.blue - expected.blue) < COLOR_TOLERANCE

    private companion object {
        const val ROOT = "status rows"
        const val DEFAULT = "Status"
        const val OVERRIDE = "Override"
        const val INSET = 16f
        const val ROW_HEIGHT = 56f
        const val FONT_SIZE = 19
        const val LINE_HEIGHT = 23
        const val PIXEL_TOLERANCE = 1f
        const val COLOR_TOLERANCE = 0.02f
    }
}
