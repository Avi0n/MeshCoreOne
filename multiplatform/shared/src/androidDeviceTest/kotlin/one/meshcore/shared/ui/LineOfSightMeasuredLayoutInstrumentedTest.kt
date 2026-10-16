package one.meshcore.shared.ui

import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import one.meshcore.shared.map.ui.LocalMapLibreHostRenderer
import one.meshcore.shared.tools.LineOfSightViewModel
import one.meshcore.shared.tools.ui.LineOfSightScreen
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LineOfSightMeasuredLayoutInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val renderer = LosRecordingRenderer()
    private val model = LineOfSightViewModel(scope)
    private val width = mutableStateOf(641f)
    private val height = mutableStateOf(420f)
    private val scale = 1f

    @After fun tearDown() {
        scope.cancel()
    }

    @Test
    fun pairBoundaryIncludesDividerAndKeepsMapMinimum() {
        show()
        compose.waitForIdle()
        val paired = compose.onNodeWithTag(LosRecordingRenderer.MAP_TAG).fetchSemanticsNode().boundsInRoot
        assertEquals(321f * scale, paired.left, PIXEL_TOLERANCE)
        assertEquals(320f * scale, paired.width, PIXEL_TOLERANCE)
        compose.runOnIdle { width.value = 640f }
        compose.waitForIdle()
        val sheet = compose.onNodeWithTag(LosRecordingRenderer.MAP_TAG).fetchSemanticsNode().boundsInRoot
        assertEquals(0f, sheet.left, PIXEL_TOLERANCE)
        assertEquals(640f * scale, sheet.width, PIXEL_TOLERANCE)
    }

    @Test
    fun wideButShortWorkspaceUsesSheetUntilAnalysisHeightFits() {
        width.value = 1000f
        height.value = 419f
        show()
        compose.waitForIdle()
        val short = compose.onNodeWithTag(LosRecordingRenderer.MAP_TAG).fetchSemanticsNode().boundsInRoot
        assertEquals(0f, short.left, PIXEL_TOLERANCE)
        compose.runOnIdle { height.value = 420f }
        compose.waitForIdle()
        val paired = compose.onNodeWithTag(LosRecordingRenderer.MAP_TAG).fetchSemanticsNode().boundsInRoot
        assertEquals(401f * scale, paired.left, PIXEL_TOLERANCE)
        assertTrue(paired.width >= 320f * scale - PIXEL_TOLERANCE)
        capturePayloadFrame("los-measured-pair.png")
    }

    private fun show() {
        compose.runOnIdle { compose.activity.enableEdgeToEdge() }
        compose.setContent {
            val native = LocalDensity.current
            CompositionLocalProvider(
                // Integral pixels keep exact minimum-dp boundaries independent of density rounding.
                LocalDensity provides Density(scale, native.fontScale),
                LocalMapLibreHostRenderer provides renderer,
            ) {
                MaterialTheme {
                    Box(Modifier.width(width.value.dp).height(height.value.dp)) {
                        LineOfSightScreen(workspace = model, isOffline = true)
                    }
                }
            }
        }
    }

    private companion object {
        const val PIXEL_TOLERANCE = 2f
    }
}
