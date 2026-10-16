package one.meshcore.shared.remotenode.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import one.meshcore.shared.chat.ui.LocalTimestampFormatter
import one.meshcore.shared.chat.ui.TimestampFormatter
import one.meshcore.shared.remotenode.TelemetryChartPoint
import one.meshcore.shared.ui.CHART_GRID_ALPHA
import one.meshcore.shared.ui.ChartAxisFrame
import one.meshcore.shared.ui.drawChartGrid
import one.meshcore.shared.ui.rememberAppHaptics
import kotlin.math.max
import kotlin.math.sqrt

/** Telemetry history plot height. A shorter strip hides the shape of a time gap. */
private const val CHART_HEIGHT_DP = 180
internal const val CHART_SERIES_STROKE_WIDTH = 2.5f
private const val POINT_RADIUS = 3f
private const val SCRUB_RULE_ALPHA = 0.4f
private const val SCRUB_RULE_WIDTH = 1f
private const val SCRUB_CHIP_ALPHA = 0.92f
private const val SCRUB_CHIP_CORNER_DP = 6
private const val SCRUB_CHIP_PADDING_H_DP = 8
private const val SCRUB_CHIP_PADDING_V_DP = 4
private const val SCRUB_VALUE_GAP_DP = 6
private const val LINE_DIRECTION_EPSILON = 0.001f
private const val COORDS_PER_POINT = 2
private const val VERTICES_PER_POINT = 2

private data class ScrubChipStyle(
    val primary: Color,
    val secondary: Color,
    val timestamp: Color,
    val chip: Color,
    val textStyle: TextStyle,
)

@Composable
internal fun ChartCanvas(
    title: String,
    labels: ChartAxisLabels,
    model: ChartDrawModel,
    lineColor: Color,
    secondaryLineColor: Color,
) {
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    ChartPlotFrame(
        title = title,
        labels = labels,
        model = model,
        axisColor = axisColor,
        lineColor = lineColor,
        secondaryLineColor = secondaryLineColor,
        gridColor = axisColor.copy(alpha = CHART_GRID_ALPHA),
        scrubRuleColor = axisColor.copy(alpha = SCRUB_RULE_ALPHA),
    )
}

@Composable
private fun ChartPlotFrame(
    title: String,
    labels: ChartAxisLabels,
    model: ChartDrawModel,
    axisColor: Color,
    lineColor: Color,
    secondaryLineColor: Color,
    gridColor: Color,
    scrubRuleColor: Color,
) {
    ChartAxisFrame(
        yLabelsTopToBottom = labels.yLabelsTopToBottom(),
        xTicks = labels.xTicks(),
        plotHeight = CHART_HEIGHT_DP.dp,
        axisColor = axisColor,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .semantics {
                    metricChartAccessibilityLabel(title, hasEnoughData = true)?.let {
                        contentDescription = it
                    }
                },
        ) {
            ChartSeriesLayer(
                model = model,
                lineColor = lineColor,
                secondaryLineColor = secondaryLineColor,
                gridColor = gridColor,
            )
            ChartScrubOverlay(
                model = model,
                primaryColor = lineColor,
                secondaryColor = secondaryLineColor,
                ruleColor = scrubRuleColor,
            )
        }
    }
}

@Composable
private fun ChartSeriesLayer(model: ChartDrawModel, lineColor: Color, secondaryLineColor: Color, gridColor: Color) {
    // The layer records the mesh. A scrub replays it; a new sample redraws into the same layer.
    Canvas(Modifier.fillMaxSize().graphicsLayer()) {
        val scale = metricChartScale(model.primary, model.secondary, model.yDomain) ?: return@Canvas
        val xFractions = metricChartXTicks(scale.timeStart, scale.timeEnd).map { it.toFloat() }
        val yFractions = metricChartYTicks(scale.valueMin, scale.valueMax).map { value ->
            metricChartYFraction(value, scale.valueMin, scale.valueMax).toFloat()
        }
        drawChartGrid(xFractions = xFractions, yFractions = yFractions, color = gridColor)
        drawSeries(model.primary, scale, lineColor)
        if (model.secondary.isNotEmpty()) {
            drawSeries(model.secondary, scale, secondaryLineColor)
        }
    }
}

/**
 * The chip is drawn here, not composed. A finger move then invalidates this canvas only,
 * and the series layer keeps the mesh it already recorded.
 */
@Composable
private fun ChartScrubOverlay(model: ChartDrawModel, primaryColor: Color, secondaryColor: Color, ruleColor: Color) {
    val formatter = LocalTimestampFormatter.current
    var scrub by remember { mutableStateOf<ChartScrub?>(null) }
    val haptics = rememberAppHaptics()
    val textMeasurer = rememberTextMeasurer()
    val chipStyle = ScrubChipStyle(
        primary = primaryColor,
        secondary = secondaryColor,
        timestamp = MaterialTheme.colorScheme.onSurfaceVariant,
        chip = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = SCRUB_CHIP_ALPHA),
        textStyle = MaterialTheme.typography.labelSmall,
    )
    Canvas(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset ->
                        // The chart looks the same the instant a scrub takes hold, so the grab is
                        // confirmed by touch (iOS fires an impact on the same edge).
                        haptics.tap()
                        scrub = scrubSelection(offset.x, size.width.toFloat(), model)
                    },
                    onDrag = { change, _ ->
                        scrub = scrubSelection(change.position.x, size.width.toFloat(), model)
                    },
                    onDragEnd = { scrub = null },
                    onDragCancel = { scrub = null },
                )
            },
    ) {
        val active = scrub ?: return@Canvas
        drawLine(
            color = ruleColor,
            start = Offset(active.x, 0f),
            end = Offset(active.x, size.height),
            strokeWidth = SCRUB_RULE_WIDTH,
        )
        drawScrubChip(active, model.unit, chipStyle, textMeasurer, formatter)
    }
}

private fun DrawScope.drawScrubChip(
    scrub: ChartScrub,
    unit: String,
    style: ScrubChipStyle,
    textMeasurer: TextMeasurer,
    formatter: TimestampFormatter,
) {
    val values = ArrayList<Pair<TextLayoutResult, Color>>()
    scrub.primary?.let { point ->
        val layout = textMeasurer.measure(formatValue(point.value) + unitSuffix(unit), style.textStyle)
        values.add(layout to style.primary)
    }
    scrub.secondary?.let { point ->
        val layout = textMeasurer.measure(formatValue(point.value) + unitSuffix(unit), style.textStyle)
        values.add(layout to style.secondary)
    }
    if (values.isEmpty()) return
    val timeLayout = textMeasurer.measure(formatScrubTimestamp(scrub.snappedTimestamp, formatter), style.textStyle)
    val padH = SCRUB_CHIP_PADDING_H_DP.dp.toPx()
    val padV = SCRUB_CHIP_PADDING_V_DP.dp.toPx()
    val gap = SCRUB_VALUE_GAP_DP.dp.toPx()
    var rowWidth = 0f
    var rowHeight = 0f
    for (entry in values) {
        rowWidth += entry.first.size.width.toFloat()
        rowHeight = max(rowHeight, entry.first.size.height.toFloat())
    }
    if (values.size > 1) rowWidth += gap
    val chipWidth = max(rowWidth, timeLayout.size.width.toFloat()) + padH * 2f
    val chipHeight = rowHeight + timeLayout.size.height.toFloat() + padV * 2f
    drawRoundRect(
        color = style.chip,
        size = Size(chipWidth, chipHeight),
        cornerRadius = CornerRadius(SCRUB_CHIP_CORNER_DP.dp.toPx()),
    )
    var textX = padH
    val textY = padV
    for (entry in values) {
        drawText(entry.first, color = entry.second, topLeft = Offset(textX, textY))
        textX += entry.first.size.width + gap
    }
    drawText(timeLayout, color = style.timestamp, topLeft = Offset(padH, textY + rowHeight))
}

private var plottedXy = FloatArray(0)

private fun DrawScope.drawSeries(points: List<TelemetryChartPoint>, scale: MetricChartScale, color: Color) {
    if (points.isEmpty()) return
    if (points.size == 1) {
        val only = points[0]
        val x = metricChartXFraction(only.timestampSeconds, scale.timeStart, scale.timeEnd) * size.width
        val yFraction = metricChartYFraction(only.value, scale.valueMin, scale.valueMax)
        drawCircle(
            color = color,
            radius = POINT_RADIUS,
            center = Offset(x.toFloat(), (size.height - yFraction * size.height).toFloat()),
        )
        return
    }
    val needed = points.size * COORDS_PER_POINT
    if (plottedXy.size < needed) {
        plottedXy = FloatArray(needed)
    }
    val xy = plottedXy
    val width = size.width
    val height = size.height
    for (index in points.indices) {
        val point = points[index]
        val x = metricChartXFraction(point.timestampSeconds, scale.timeStart, scale.timeEnd) * width
        val yFraction = metricChartYFraction(point.value, scale.valueMin, scale.valueMax)
        xy[index * COORDS_PER_POINT] = x.toFloat()
        xy[index * COORDS_PER_POINT + 1] = (height - yFraction * height).toFloat()
    }
    drawPlatformChartLine(xy, points.size, color)
}

/**
 * One filled strip. A stroked path of a few hundred points is software-rasterized on Android,
 * and each segment as its own line is just as slow to issue.
 */
internal fun DrawScope.drawChartSeriesLine(points: List<Offset>, color: Color) {
    if (points.size < 2) {
        if (points.size == 1) {
            drawCircle(color = color, radius = POINT_RADIUS, center = points[0])
        }
        return
    }
    val needed = points.size * COORDS_PER_POINT
    if (plottedXy.size < needed) {
        plottedXy = FloatArray(needed)
    }
    val xy = plottedXy
    for (index in points.indices) {
        xy[index * COORDS_PER_POINT] = points[index].x
        xy[index * COORDS_PER_POINT + 1] = points[index].y
    }
    drawPlatformChartLine(xy, points.size, color)
}

internal expect fun DrawScope.drawPlatformChartLine(xy: FloatArray, pointCount: Int, color: Color)

/** A triangle strip of [strokeWidth] centered on [points]. Empty when a line needs two samples. */
internal fun chartLineStrip(points: List<Offset>, strokeWidth: Float): List<Offset> {
    if (points.size < 2) return emptyList()
    val xy = FloatArray(points.size * COORDS_PER_POINT)
    for (index in points.indices) {
        xy[index * COORDS_PER_POINT] = points[index].x
        xy[index * COORDS_PER_POINT + 1] = points[index].y
    }
    val out = FloatArray(points.size * VERTICES_PER_POINT * COORDS_PER_POINT)
    writeChartLineStrip(xy, points.size, strokeWidth, out)
    return List(points.size * VERTICES_PER_POINT) { index ->
        Offset(out[index * COORDS_PER_POINT], out[index * COORDS_PER_POINT + 1])
    }
}

internal fun writeChartLineStrip(xy: FloatArray, pointCount: Int, strokeWidth: Float, out: FloatArray) {
    val half = strokeWidth / 2f
    var lastNormalX = 0f
    var lastNormalY = -half
    var outIndex = 0
    for (index in 0 until pointCount) {
        val currentX = xy[index * COORDS_PER_POINT]
        val currentY = xy[index * COORDS_PER_POINT + 1]
        val directionX: Float
        val directionY: Float
        if (index == 0) {
            directionX = xy[COORDS_PER_POINT] - currentX
            directionY = xy[COORDS_PER_POINT + 1] - currentY
        } else if (index == pointCount - 1) {
            val previous = (index - 1) * COORDS_PER_POINT
            directionX = currentX - xy[previous]
            directionY = currentY - xy[previous + 1]
        } else {
            val previous = (index - 1) * COORDS_PER_POINT
            val next = (index + 1) * COORDS_PER_POINT
            directionX = xy[next] - xy[previous]
            directionY = xy[next + 1] - xy[previous + 1]
        }
        val length = sqrt(directionX * directionX + directionY * directionY)
        val normalX: Float
        val normalY: Float
        if (length < LINE_DIRECTION_EPSILON) {
            normalX = lastNormalX
            normalY = lastNormalY
        } else {
            normalX = -directionY / length * half
            normalY = directionX / length * half
            lastNormalX = normalX
            lastNormalY = normalY
        }
        out[outIndex++] = currentX + normalX
        out[outIndex++] = currentY + normalY
        out[outIndex++] = currentX - normalX
        out[outIndex++] = currentY - normalY
    }
}
