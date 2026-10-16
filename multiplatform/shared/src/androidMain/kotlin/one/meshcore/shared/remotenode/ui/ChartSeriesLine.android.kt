package one.meshcore.shared.remotenode.ui

import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb

private const val FLOATS_PER_VERTEX = 2

private val seriesPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.FILL
}

// drawVertices copies these buffers before returning, so the next series may reuse them.
private var seriesStrip = FloatArray(0)
private var seriesColors = IntArray(0)

internal actual fun DrawScope.drawPlatformChartLine(xy: FloatArray, pointCount: Int, color: Color) {
    val vertexCount = pointCount * 2
    if (seriesStrip.size < vertexCount * FLOATS_PER_VERTEX) {
        seriesStrip = FloatArray(vertexCount * FLOATS_PER_VERTEX)
    }
    if (seriesColors.size < vertexCount) {
        seriesColors = IntArray(vertexCount)
    }
    writeChartLineStrip(xy, pointCount, CHART_SERIES_STROKE_WIDTH, seriesStrip)
    val argb = color.toArgb()
    for (index in 0 until vertexCount) {
        seriesColors[index] = argb
    }
    seriesPaint.color = argb
    drawContext.canvas.nativeCanvas.drawVertices(
        Canvas.VertexMode.TRIANGLE_STRIP,
        vertexCount * FLOATS_PER_VERTEX,
        seriesStrip,
        0,
        null,
        0,
        seriesColors,
        0,
        null,
        0,
        0,
        seriesPaint,
    )
}
