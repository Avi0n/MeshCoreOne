package one.meshcore.shared.remotenode.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.VertexMode
import androidx.compose.ui.graphics.Vertices
import androidx.compose.ui.graphics.drawscope.DrawScope

internal actual fun DrawScope.drawPlatformChartLine(xy: FloatArray, pointCount: Int, color: Color) {
    val points = List(pointCount) { index ->
        Offset(xy[index * 2], xy[index * 2 + 1])
    }
    val strip = chartLineStrip(points, CHART_SERIES_STROKE_WIDTH)
    val paint = Paint().apply {
        this.color = Color.White
        isAntiAlias = true
    }
    drawContext.canvas.drawVertices(
        Vertices(
            vertexMode = VertexMode.TriangleStrip,
            positions = strip,
            textureCoordinates = List(strip.size) { Offset.Zero },
            colors = List(strip.size) { color },
            indices = emptyList(),
        ),
        BlendMode.Modulate,
        paint,
    )
}
