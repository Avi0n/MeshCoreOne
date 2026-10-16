package one.meshcore.shared.tools.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import one.meshcore.shared.backup.SavedTracePathDto
import one.meshcore.shared.backup.TracePathRunDto
import one.meshcore.shared.chat.ui.LocalTimestampFormatter
import one.meshcore.shared.chat.ui.chatDayLabel
import one.meshcore.shared.chat.ui.chatMonthDayLabel
import one.meshcore.shared.remotenode.ui.formatValue
import one.meshcore.shared.remotenode.ui.metricChartXTickInstants
import one.meshcore.shared.remotenode.ui.metricChartYFraction
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_path_detail_round_trip
import one.meshcore.shared.resources.contacts_results_comparison
import one.meshcore.shared.resources.contacts_results_comparison_decreased
import one.meshcore.shared.resources.contacts_results_comparison_increased
import one.meshcore.shared.resources.contacts_results_view_runs
import one.meshcore.shared.trace.RttTrend
import one.meshcore.shared.trace.previousSuccessfulRun
import one.meshcore.shared.trace.recentRTTs
import one.meshcore.shared.trace.rttComparison
import one.meshcore.shared.trace.runCount
import one.meshcore.shared.trace.savedPathDetailPoints
import one.meshcore.shared.trace.savedPathDetailRttTicks
import one.meshcore.shared.trace.savedPathDetailXTickFractions
import one.meshcore.shared.trace.sparklineDescription
import one.meshcore.shared.trace.sparklinePoints
import one.meshcore.shared.ui.CHART_GRID_ALPHA
import one.meshcore.shared.ui.ChartAxisFrame
import one.meshcore.shared.ui.ChartAxisTick
import one.meshcore.shared.ui.PreferenceHubCardPadding
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.drawChartGrid
import one.meshcore.shared.ui.durationMsLabel
import one.meshcore.shared.ui.formatTemplate
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.tint
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun ComparisonAndSparkline(currentMs: Int, saved: SavedTracePathDto, onOpenDetail: () -> Unit) {
    val previous = saved.previousSuccessfulRun
    PreferenceHubSection(
        title = stringResource(Res.string.contacts_path_detail_round_trip),
        applyHorizontalPadding = false,
    ) {
        if (previous != null) {
            val comparison = rttComparison(currentMs, previous.roundTripMs)
            ListItem(
                headlineContent = {
                    Text(durationMsLabel(currentMs), fontFamily = FontFamily.Monospace)
                },
                supportingContent = {
                    Text(
                        text = traceComparisonCaption(
                            template = stringResource(Res.string.contacts_results_comparison),
                            roundTripMs = previous.roundTripMs,
                            dateLabel = chatDayLabel(previous.date, LocalTimestampFormatter.current).absolute,
                        ),
                    )
                },
                trailingContent = if (comparison.trend != RttTrend.UNCHANGED) {
                    {
                        val color = when (val tint = traceComparisonChangeTint(comparison.trend)) {
                            null -> MaterialTheme.colorScheme.onSurface
                            else -> tint.chromeRole().tint()
                        }
                        val a11y = if (comparison.trend == RttTrend.INCREASED) {
                            stringResource(Res.string.contacts_results_comparison_increased)
                        } else {
                            stringResource(Res.string.contacts_results_comparison_decreased)
                        }
                        val visible = traceComparisonChangeVisibleText(comparison.trend, comparison.percentChange)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.semantics { contentDescription = "$visible, $a11y" },
                        ) {
                            Icon(
                                imageVector = if (traceComparisonChangeIsIncrease(comparison.trend)) {
                                    AppIcons.KeyboardArrowUp
                                } else {
                                    AppIcons.KeyboardArrowDown
                                },
                                contentDescription = null,
                                tint = color,
                                modifier = Modifier.size(TRACE_COMPARISON_TREND_ICON_SIZE),
                            )
                            Text(
                                text = visible,
                                color = color,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                } else {
                    null
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            ListItem(
                headlineContent = {
                    Text(durationMsLabel(currentMs), fontFamily = FontFamily.Monospace)
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        val rtts = saved.recentRTTs
        if (rtts.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PreferenceHubCardPadding, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MiniSparkline(values = rtts, modifier = Modifier.weight(1f).height(SPARKLINE_HEIGHT.dp))
                OutlinedButton(onClick = onOpenDetail) {
                    Text(
                        formatTemplate(
                            stringResource(Res.string.contacts_results_view_runs),
                            listOf(saved.runCount),
                        ),
                    )
                }
            }
        }
    }
}

@Composable
internal fun MiniSparkline(values: List<Int>, modifier: Modifier = Modifier) {
    val strokeColor = MaterialTheme.colorScheme.primary
    // A canvas carries no text, so without this the whole of a path's response history is silent.
    val description = sparklineDescription(values)
    Canvas(modifier = modifier.semantics { contentDescription = description }) {
        val pts = sparklinePoints(values, size.width.toDouble(), size.height.toDouble())
        if (pts.size >= 2) {
            val path = Path()
            path.moveTo(pts.first().x.toFloat(), pts.first().y.toFloat())
            for (i in 1 until pts.size) {
                path.lineTo(pts[i].x.toFloat(), pts[i].y.toFloat())
            }
            drawPath(
                path = path,
                color = strokeColor,
                style = Stroke(width = SPARKLINE_STROKE, cap = StrokeCap.Round),
            )
        } else if (pts.size == 1) {
            drawCircle(
                color = strokeColor,
                radius = SPARKLINE_STROKE,
                center = Offset(pts[0].x.toFloat(), pts[0].y.toFloat()),
            )
        }
    }
}

@Composable
internal fun SavedPathRunChart(runs: List<TracePathRunDto>, modifier: Modifier = Modifier) {
    val formatter = LocalTimestampFormatter.current
    val strokeColor = MaterialTheme.colorScheme.primary
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = axisColor.copy(alpha = CHART_GRID_ALPHA)
    val description = sparklineDescription(runs.map { it.roundTripMs })
    val dates = runs.map { it.date }
    val roundTrips = runs.map { it.roundTripMs }
    val xFractions = savedPathDetailXTickFractions(dates)
    val yValues = savedPathDetailRttTicks(roundTrips)
    val xTicks = if (dates.isEmpty()) {
        emptyList()
    } else {
        xFractions.zip(metricChartXTickInstants(dates.min(), dates.max())) { fraction, instant ->
            ChartAxisTick(fraction = fraction.toFloat(), label = chatMonthDayLabel(instant, formatter))
        }
    }
    val yMin = yValues.firstOrNull() ?: 0.0
    val yMax = yValues.lastOrNull() ?: yMin
    ChartAxisFrame(
        yLabelsTopToBottom = yValues.asReversed().map { formatValue(it) },
        xTicks = xTicks,
        plotHeight = DETAIL_PLOT_HEIGHT.dp,
        axisColor = axisColor,
        modifier = modifier,
    ) {
        Canvas(Modifier.fillMaxSize().semantics { contentDescription = description }) {
            drawChartGrid(
                xFractions = xFractions.map { it.toFloat() },
                yFractions = yValues.map { metricChartYFraction(it, yMin, yMax).toFloat() },
                color = gridColor,
            )
            val pts = savedPathDetailPoints(runs, size.width.toDouble(), size.height.toDouble())
            if (pts.size < 2) return@Canvas
            val path = Path()
            path.moveTo(pts.first().x.toFloat(), pts.first().y.toFloat())
            for (index in 1 until pts.size) {
                path.lineTo(pts[index].x.toFloat(), pts[index].y.toFloat())
            }
            clipRect {
                drawPath(
                    path = path,
                    color = strokeColor,
                    style = Stroke(width = SPARKLINE_STROKE, cap = StrokeCap.Round),
                )
                pts.forEach { point ->
                    drawCircle(
                        color = strokeColor,
                        radius = SAVED_PATH_POINT_RADIUS,
                        center = Offset(point.x.toFloat(), point.y.toFloat()),
                    )
                }
            }
        }
    }
}

private const val SPARKLINE_HEIGHT = 20
private const val SPARKLINE_STROKE = 2.5f
private const val SAVED_PATH_POINT_RADIUS = 3f
private const val DETAIL_PLOT_HEIGHT = 150

/** iOS `ComparisonRowView`: `vs. {ms} ms on {abbreviated date}`. */
internal fun traceComparisonCaption(template: String, roundTripMs: Int, dateLabel: String): String =
    formatTemplate(template, listOf(roundTripMs, dateLabel))

/** Visible percent; the Increased/Decreased word is TalkBack-only, matching iOS `ComparisonRowView`. */
internal fun traceComparisonChangeVisibleText(trend: RttTrend, percentChange: Double): String {
    if (trend == RttTrend.UNCHANGED) return ""
    return "${kotlin.math.abs(percentChange).toInt()}%"
}

internal fun traceComparisonChangeIsIncrease(trend: RttTrend): Boolean = trend == RttTrend.INCREASED
private val TRACE_COMPARISON_TREND_ICON_SIZE = 16.dp
