package one.meshcore.shared.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

internal data class ChartAxisTick(val fraction: Float, val label: String)

private const val AXIS_SPACING_DP = 8
private const val GRID_STROKE = 1f

internal const val CHART_GRID_ALPHA = 0.35f

/**
 * Y labels sit beside the plot and X labels under it, so a scrub on the plot
 * uses the plot width rather than a canvas that also contains the labels.
 */
@Composable
internal fun ChartAxisFrame(
    yLabelsTopToBottom: List<String>,
    xTicks: List<ChartAxisTick>,
    plotHeight: Dp,
    axisColor: Color,
    modifier: Modifier = Modifier,
    plot: @Composable () -> Unit,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(
            modifier = Modifier.height(plotHeight).padding(end = AXIS_SPACING_DP.dp),
            verticalArrangement = if (yLabelsTopToBottom.size <= 1) {
                Arrangement.Center
            } else {
                Arrangement.SpaceBetween
            },
            horizontalAlignment = Alignment.End,
        ) {
            yLabelsTopToBottom.forEach { label ->
                Text(text = label, style = MaterialTheme.typography.labelSmall, color = axisColor)
            }
        }
        Column(Modifier.weight(1f)) {
            Box(Modifier.fillMaxWidth().height(plotHeight)) {
                plot()
            }
            ChartXTickLabels(ticks = xTicks, axisColor = axisColor)
        }
    }
}

@Composable
private fun ChartXTickLabels(ticks: List<ChartAxisTick>, axisColor: Color) {
    Layout(
        content = {
            ticks.forEach { tick ->
                Text(text = tick.label, style = MaterialTheme.typography.labelSmall, color = axisColor)
            }
        },
        modifier = Modifier.fillMaxWidth().padding(top = AXIS_SPACING_DP.dp),
    ) { measurables, constraints ->
        val labels = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val width = constraints.maxWidth
        val spacing = AXIS_SPACING_DP.dp.roundToPx()
        layout(width, labels.maxOfOrNull { it.height } ?: 0) {
            var lastEnd = -spacing
            labels.forEachIndexed { index, label ->
                val preferred = (ticks[index].fraction * width - label.width / 2f).roundToInt()
                val left = preferred.coerceIn(0, (width - label.width).coerceAtLeast(0))
                val isEnd = index == 0 || index == labels.lastIndex
                if (isEnd || left >= lastEnd + spacing) {
                    label.place(left, 0)
                    lastEnd = left + label.width
                }
            }
        }
    }
}

internal fun DrawScope.drawChartGrid(xFractions: List<Float>, yFractions: List<Float>, color: Color) {
    xFractions.forEach { fraction ->
        val x = fraction * size.width
        drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = GRID_STROKE)
    }
    yFractions.forEach { fraction ->
        val y = size.height - fraction * size.height
        drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = GRID_STROKE)
    }
}
