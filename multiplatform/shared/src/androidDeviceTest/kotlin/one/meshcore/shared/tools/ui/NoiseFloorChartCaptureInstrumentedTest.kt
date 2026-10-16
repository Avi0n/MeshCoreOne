package one.meshcore.shared.tools.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.tools.NoiseFloorReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NoiseFloorChartCaptureInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun rollbackAreaHasUniformOpacityBelowTheSeries() {
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color.Magenta, surface = Color.White)) {
                Surface(Modifier.width(FILL_TEST_WIDTH.dp).testTag(CAPTURE_TAG)) {
                    NoiseFloorChartCanvas(SCENARIOS[ROLLBACK_SCENARIO].readings, START_EPOCH_MS, "Rollback samples")
                }
            }
        }
        val pixels = compose.onNodeWithTag(CAPTURE_TAG).captureToImage().toPixelMap()
        val row = (pixels.height * FILL_SAMPLE_Y).toInt()
        val before = pixels[(pixels.width * FILL_BEFORE_X).toInt(), row]
        val overlapping = pixels[(pixels.width * FILL_OVERLAP_X).toInt(), row]
        val after = pixels[(pixels.width * FILL_AFTER_X).toInt(), row]
        assertNotEquals("The sampled region must contain the area", Color.White, before)
        assertEquals("Rollback does not composite the area repeatedly", before, overlapping)
        assertEquals("The final segment has the same area opacity", before, after)
    }

    @Test
    fun captureDomainsAndTimestampEdgesAcrossPresentationChanges() {
        var scenario by mutableStateOf(SCENARIOS.first())
        var dark by mutableStateOf(false)
        var fontScale by mutableStateOf(NORMAL_FONT_SCALE)
        var width by mutableStateOf(NORMAL_WIDTH)
        var layoutDirection by mutableStateOf(LayoutDirection.Ltr)
        compose.setContent {
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                CompositionLocalProvider(
                    LocalDensity provides Density(LocalDensity.current.density, fontScale),
                    LocalLayoutDirection provides layoutDirection,
                ) {
                    Surface(Modifier.width(width.dp).testTag(CAPTURE_TAG)) {
                        Box(Modifier.padding(PADDING.dp).testTag(CHART_TAG)) {
                            NoiseFloorChartCanvas(scenario.readings, START_EPOCH_MS, scenario.label)
                        }
                    }
                }
            }
        }
        SCENARIOS.forEach { next ->
            compose.runOnIdle { scenario = next }
            compose.waitForIdle()
            capture(next.name, next.label)
        }
        compose.runOnIdle {
            scenario = SCENARIOS.first()
            dark = true
        }
        capture("dark", SCENARIOS.first().label)
        compose.runOnIdle {
            dark = false
            fontScale = LARGE_FONT_SCALE
            width = NARROW_WIDTH
        }
        capture("narrow-large-text", SCENARIOS.first().label)
        compose.runOnIdle { scenario = SCENARIOS.last() }
        capture("narrow-long-session", SCENARIOS.last().label)
        compose.runOnIdle { layoutDirection = LayoutDirection.Rtl }
        capture("rtl-narrow-long-session", SCENARIOS.last().label)
    }

    private fun capture(name: String, label: String) {
        compose.waitForIdle()
        compose.onNodeWithContentDescription(label).assertExists()
        val bitmap = compose.onNodeWithTag(CAPTURE_TAG).captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "$FILE_PREFIX$name.png")
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it)) }
    }

    private data class Scenario(val name: String, val readings: List<NoiseFloorReading>) {
        val label: String = "Noise floor ${readings.size} samples"
    }

    private companion object {
        const val START_EPOCH_MS = 1_000_000L
        const val NORMAL_WIDTH = 320
        const val NARROW_WIDTH = 180
        const val PADDING = 16
        const val NORMAL_FONT_SCALE = 1f
        const val LARGE_FONT_SCALE = 2f
        const val PNG_QUALITY = 100
        const val CAPTURE_TAG = "noise-capture"
        const val CHART_TAG = "noise-chart"
        const val FILL_TEST_WIDTH = 500
        const val ROLLBACK_SCENARIO = 3
        const val FILL_SAMPLE_Y = 0.75f
        const val FILL_BEFORE_X = 0.2f
        const val FILL_OVERLAP_X = 0.37f
        const val FILL_AFTER_X = 0.5f
        const val FILE_PREFIX = "noise-library-"
        val READING_ID = Uuid.normalized("AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA")
        val SCENARIOS = listOf(
            scenario("ordinary", listOf(0L, 60_000, 120_000, 180_000, 240_000), listOf(-120, -90, -100, -80, -110)),
            scenario("rolling-half-minute", listOf(0L, 60_000, 120_000, 330_000), listOf(-120, -90, -100, -80)),
            scenario("duplicate-time", listOf(0L, 60_000, 60_000, 180_000), listOf(-120, -110, -80, -100)),
            scenario("visible-rollback", listOf(0L, 120_000, 60_000, 180_000), listOf(-120, -80, -110, -100)),
            scenario("clipped-extremes", listOf(0L, 60_000, 120_000, 330_000), listOf(-140, -100, -50, -80)),
            scenario("single", listOf(60_000L), listOf(-100)),
            scenario("empty", emptyList(), emptyList()),
            scenario(
                "long-session",
                listOf(900_000L, 960_000, 1_020_000, 1_080_000, 1_140_000),
                listOf(-120, -90, -100, -80, -110),
            ),
        )

        fun scenario(name: String, elapsed: List<Long>, values: List<Int>) = Scenario(
            name,
            elapsed.zip(values) { time, noise ->
                NoiseFloorReading(READING_ID, START_EPOCH_MS + time, noise, -70, 5.0)
            },
        )
    }
}
