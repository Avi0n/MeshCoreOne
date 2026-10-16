package one.meshcore.shared.battery.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import one.meshcore.shared.battery.OCVPreset
import one.meshcore.shared.battery.batteryCurveVertices
import one.meshcore.shared.battery.batteryCurveXTicks
import one.meshcore.shared.battery.batteryCurveYDomain
import one.meshcore.shared.battery.batteryCurveYFraction
import one.meshcore.shared.battery.batteryCurveYTickHundredths
import one.meshcore.shared.battery.batteryPercentLabel
import one.meshcore.shared.battery.batteryVoltLabel
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.settings_chart_percent
import one.meshcore.shared.resources.settings_chart_voltage
import one.meshcore.shared.ui.CHART_GRID_ALPHA
import one.meshcore.shared.ui.ChartAxisFrame
import one.meshcore.shared.ui.ChartAxisTick
import one.meshcore.shared.ui.drawChartGrid
import org.jetbrains.compose.resources.stringResource

private const val CHART_HEIGHT_DP = 150
private const val STROKE_WIDTH = 3f
private const val POINT_RADIUS = 3.5f
private const val FILL_ALPHA = 0.18f
private const val HUNDREDTHS_PER_VOLT = 100.0

/**
 * Visual OCV discharge curve (percent on X, voltage on Y). Straight segments between the
 * stored samples, on a Y domain rounded to 0.1 V. Theme primary, not a system hue.
 */
@Composable
fun BatteryCurveChart(ocvArray: List<Int>, accessibilityLabel: String, modifier: Modifier = Modifier) {
    if (ocvArray.size != OCVPreset.POINT_COUNT) return
    val lineColor = MaterialTheme.colorScheme.primary
    val fillColor = lineColor.copy(alpha = FILL_ALPHA)
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = axisColor.copy(alpha = CHART_GRID_ALPHA)
    val domain = batteryCurveYDomain(ocvArray)
    val hundredths = batteryCurveYTickHundredths(ocvArray)
    val xTicks = batteryCurveXTicks().map { fraction ->
        ChartAxisTick(fraction = fraction.toFloat(), label = batteryPercentLabel(fraction))
    }
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(Res.string.settings_chart_voltage),
            style = MaterialTheme.typography.labelSmall,
            color = axisColor,
        )
        ChartAxisFrame(
            yLabelsTopToBottom = hundredths.asReversed().map { batteryVoltLabel(it) },
            xTicks = xTicks,
            plotHeight = CHART_HEIGHT_DP.dp,
            axisColor = axisColor,
        ) {
            Canvas(Modifier.fillMaxSize().semantics { contentDescription = accessibilityLabel }) {
                val vertices = batteryCurveVertices(ocvArray)
                drawChartGrid(
                    xFractions = xTicks.map { it.fraction },
                    yFractions = hundredths.map {
                        batteryCurveYFraction(it / HUNDREDTHS_PER_VOLT, domain).toFloat()
                    },
                    color = gridColor,
                )
                if (vertices.size < 2) return@Canvas
                val points = vertices.map { vertex ->
                    Offset(
                        x = (vertex.xFraction * size.width).toFloat(),
                        y = (size.height - vertex.yFraction * size.height).toFloat(),
                    )
                }
                val linePath = Path().apply {
                    moveTo(points[0].x, points[0].y)
                    for (index in 1 until points.size) {
                        lineTo(points[index].x, points[index].y)
                    }
                }
                val areaPath = Path().apply {
                    addPath(linePath)
                    lineTo(points.last().x, size.height)
                    lineTo(points.first().x, size.height)
                    close()
                }
                clipRect {
                    drawPath(areaPath, color = fillColor)
                    drawPath(
                        path = linePath,
                        color = lineColor,
                        style = Stroke(width = STROKE_WIDTH, cap = StrokeCap.Round),
                    )
                    points.forEach { point ->
                        drawCircle(color = lineColor, radius = POINT_RADIUS, center = point)
                    }
                }
            }
        }
        Text(
            text = stringResource(Res.string.settings_chart_percent),
            style = MaterialTheme.typography.labelSmall,
            color = axisColor,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
