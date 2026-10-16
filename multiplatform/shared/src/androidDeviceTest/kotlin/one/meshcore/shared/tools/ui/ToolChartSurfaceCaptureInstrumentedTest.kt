package one.meshcore.shared.tools.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import one.meshcore.shared.backup.TracePathRunDto
import one.meshcore.shared.chat.ui.TimestampFormatting
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.rf.ProfileSample
import one.meshcore.shared.tools.NoiseFloorReading
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Writes the shipped tool-chart canvases to the test app cache so a host can pull
 * original-resolution pixels. The images are the capture; this test does not decide frame time.
 */
@RunWith(AndroidJUnit4::class)
class ToolChartSurfaceCaptureInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun captureNoiseFloorLineOfSightAndTraceCharts() {
        compose.setContent {
            TimestampFormatting {
                MaterialTheme {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Box(Modifier.testTag(NOISE_TAG).fillMaxWidth()) {
                            NoiseFloorChartCanvas(
                                readings = noiseReadings(),
                                startEpochMs = 0L,
                                label = "Noise floor",
                            )
                        }
                        Box(Modifier.testTag(LOS_TAG).fillMaxWidth().height(220.dp)) {
                            FresnelTerrainChart(
                                samples = terrainSamples(),
                                terrainColor = MaterialTheme.colorScheme.outline,
                                losColor = MaterialTheme.colorScheme.onSurface,
                                fresnelColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                                obstructedColor = MaterialTheme.colorScheme.error,
                            )
                        }
                        MiniSparkline(
                            values = listOf(7),
                            modifier = Modifier.testTag(SINGLE_TAG).width(50.dp).height(16.dp),
                        )
                        MiniSparkline(
                            values = emptyList(),
                            modifier = Modifier.testTag(EMPTY_TAG).width(50.dp).height(16.dp),
                        )
                        MiniSparkline(
                            values = listOf(10, 40, 20),
                            modifier = Modifier.testTag(SERIES_TAG).width(50.dp).height(16.dp),
                        )
                        SavedPathRunChart(
                            runs = savedRuns(),
                            modifier = Modifier.testTag(SAVED_TAG).fillMaxWidth(),
                        )
                    }
                }
            }
        }
        val bitmaps = listOf(NOISE_TAG, LOS_TAG, SINGLE_TAG, EMPTY_TAG, SERIES_TAG, SAVED_TAG).associateWith { tag ->
            compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap().also { bitmap -> writePng(tag, bitmap) }
        }
        assertMidLineSpans(bitmaps.getValue(SINGLE_TAG))
        assertMidLineSpans(bitmaps.getValue(EMPTY_TAG))
    }

    private fun assertMidLineSpans(bitmap: Bitmap) {
        val mid = bitmap.height / 2
        assertTrue("Sparkline has no ink at the left of the mid line", columnHasInk(bitmap, 1, mid))
        assertTrue("Sparkline has no ink at the right of the mid line", columnHasInk(bitmap, bitmap.width - 2, mid))
    }

    private fun columnHasInk(bitmap: Bitmap, x: Int, mid: Int): Boolean {
        val clampedX = x.coerceIn(0, bitmap.width - 1)
        return (mid - MID_LINE_BAND..mid + MID_LINE_BAND).any { y ->
            val pixel = bitmap.getPixel(clampedX, y.coerceIn(0, bitmap.height - 1))
            Color.red(pixel) < PAPER_CHANNEL || Color.green(pixel) < PAPER_CHANNEL || Color.blue(pixel) < PAPER_CHANNEL
        }
    }

    private fun writePng(name: String, bitmap: Bitmap) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "$name.png")
        FileOutputStream(file).use { stream -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream) }
    }

    private fun noiseReadings(): List<NoiseFloorReading> {
        val floors = listOf(-120, -90, -100, -80, -110)
        return floors.mapIndexed { index, floor ->
            NoiseFloorReading(READING_ID, index * MINUTE_MS, floor, -70, 5.0)
        }
    }

    private fun terrainSamples(): List<ProfileSample> = listOf(
        ProfileSample(x = 0.0, yTerrain = 100.0, yLos = 180.0, fresnelRadius = 40.0),
        ProfileSample(x = 400.0, yTerrain = 160.0, yLos = 190.0, fresnelRadius = 55.0),
        ProfileSample(x = 800.0, yTerrain = 120.0, yLos = 200.0, fresnelRadius = 30.0),
    )

    private fun savedRuns(): List<TracePathRunDto> = listOf(
        TracePathRunDto(RUN_ID, date = 0.0, success = true, roundTripMs = 10),
        TracePathRunDto(RUN_ID, date = 25.0 * DAY_SECONDS, success = true, roundTripMs = 30),
        TracePathRunDto(RUN_ID, date = 100.0 * DAY_SECONDS, success = true, roundTripMs = 20),
    )

    private companion object {
        const val NOISE_TAG = "noise-floor"
        const val LOS_TAG = "line-of-sight"
        const val SINGLE_TAG = "sparkline-single"
        const val EMPTY_TAG = "sparkline-empty"
        const val SERIES_TAG = "sparkline-series"
        const val SAVED_TAG = "saved-path"
        const val MINUTE_MS = 60_000L
        const val DAY_SECONDS = 86_400.0
        const val MID_LINE_BAND = 2
        const val PAPER_CHANNEL = 245
        val READING_ID: Uuid = Uuid.normalized("AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA")
        val RUN_ID: Uuid = Uuid.normalized("BBBBBBBB-BBBB-BBBB-BBBB-BBBBBBBBBBBB")
    }
}
