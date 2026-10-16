package one.meshcore.shared.remotenode.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChartSeriesExtentInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun hardwareLineIncludesTheFinalSegment() = assertLineExtent(POINT_COUNT)

    @Test
    fun hardwareTwoPointLineIsVisible() = assertLineExtent(TWO_POINTS)

    private fun assertLineExtent(pointCount: Int) {
        compose.setContent {
            Canvas(Modifier.size(CHART_SIZE.dp).testTag(CHART_TAG)) {
                assertTrue(
                    "Production canvas must be hardware accelerated",
                    drawContext.canvas.nativeCanvas.isHardwareAccelerated,
                )
                drawRect(Color.White)
                val points = List(pointCount) { index ->
                    Offset(size.width * index / (pointCount - 1), size.height / 2)
                }
                drawChartSeriesLine(points, Color.Magenta)
            }
        }
        val pixels = compose.onNodeWithTag(CHART_TAG).captureToImage().toPixelMap()
        val centerY = pixels.height / 2
        assertEquals("The first segment is visible", Color.Magenta, pixels[pixels.width / 4, centerY])
        assertEquals("The final segment is visible", Color.Magenta, pixels[pixels.width * 3 / 4, centerY])
    }

    private companion object {
        const val CHART_SIZE = 100
        const val POINT_COUNT = 5
        const val TWO_POINTS = 2
        const val CHART_TAG = "series-extent"
    }
}
