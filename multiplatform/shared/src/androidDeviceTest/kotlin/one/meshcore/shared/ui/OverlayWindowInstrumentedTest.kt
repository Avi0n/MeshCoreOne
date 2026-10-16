package one.meshcore.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class OverlayWindowInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun overlayWindowCoversTheParentScaffoldToolbar() {
        val shown = mutableStateOf(false)
        val parentLocation = IntArray(2)
        compose.setContent {
            LocalView.current.getLocationOnScreen(parentLocation)
            MaterialTheme {
                Scaffold(
                    topBar = {
                        Box(
                            Modifier.fillMaxWidth().height(TOOLBAR_HEIGHT)
                                .background(Color.Green).testTag(TOOLBAR),
                        )
                    },
                ) { padding ->
                    Box(Modifier.padding(padding).fillMaxSize()) {
                        if (shown.value) {
                            FullScreenOverlay(onDismiss = { shown.value = false }) {
                                Box(Modifier.fillMaxSize().background(Color.Magenta).testTag(OVERLAY))
                            }
                        }
                    }
                }
            }
        }
        val toolbar = compose.onNodeWithTag(TOOLBAR).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val x = parentLocation[0] + toolbar.center.x.roundToInt()
        val y = parentLocation[1] + toolbar.center.y.roundToInt()
        assertScreenColor("Parent toolbar positive control", Color.Green, x, y)

        compose.runOnIdle { shown.value = true }
        compose.onNodeWithTag(OVERLAY).assertIsDisplayed()
        assertScreenColor("Overlay must cover parent chrome", Color.Magenta, x, y)
    }

    private fun assertScreenColor(description: String, color: Color, x: Int, y: Int) {
        val expected = color.toArgb()
        compose.waitUntil(conditionDescription = description, timeoutMillis = WINDOW_TIMEOUT_MS) {
            matchesColor(screenPixel(x, y), expected)
        }
        val actual = screenPixel(x, y)
        assertTrue(
            "$description: expected ${expected.toUInt().toString(HEX_RADIX)}, " +
                "actual ${actual.toUInt().toString(HEX_RADIX)}",
            matchesColor(actual, expected),
        )
    }

    private fun matchesColor(actual: Int, expected: Int): Boolean = ARGB_CHANNEL_SHIFTS.all { shift ->
        abs(((actual ushr shift) and CHANNEL_MASK) - ((expected ushr shift) and CHANNEL_MASK)) <= COLOR_TOLERANCE
    }

    private fun screenPixel(x: Int, y: Int): Int {
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        return try {
            bitmap.getPixel(x, y)
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val TOOLBAR = "parent toolbar"
        const val OVERLAY = "overlay body"
        const val WINDOW_TIMEOUT_MS = 5_000L
        const val CHANNEL_MASK = 0xFF
        const val HEX_RADIX = 16
        val ARGB_CHANNEL_SHIFTS = listOf(24, 16, 8, 0)
        const val COLOR_TOLERANCE = 3
        val TOOLBAR_HEIGHT = 64.dp
    }
}
