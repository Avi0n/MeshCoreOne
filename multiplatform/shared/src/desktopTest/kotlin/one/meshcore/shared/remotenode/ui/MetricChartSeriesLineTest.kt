package one.meshcore.shared.remotenode.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals

class MetricChartSeriesLineTest {
    @Test
    fun seriesLineCoversTheCenterPixelWithItsColor() {
        val bitmap = ImageBitmap(BITMAP_WIDTH, BITMAP_HEIGHT)
        CanvasDrawScope().draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(bitmap),
            size = Size(BITMAP_WIDTH.toFloat(), BITMAP_HEIGHT.toFloat()),
        ) {
            drawChartSeriesLine(
                points = listOf(Offset(0f, LINE_Y), Offset(BITMAP_WIDTH.toFloat(), LINE_Y)),
                color = LINE_COLOR,
            )
        }
        val pixels = IntArray(BITMAP_WIDTH * BITMAP_HEIGHT)
        bitmap.readPixels(pixels)
        val center = pixels[CENTER_Y * BITMAP_WIDTH + BITMAP_WIDTH / 2]
        assertEquals(LINE_COLOR.toArgb(), center)
    }

    private companion object {
        const val BITMAP_WIDTH = 32
        const val BITMAP_HEIGHT = 16
        const val LINE_Y = 8f
        const val CENTER_Y = 8
        val LINE_COLOR = Color(0xFF2266CC)
    }
}
