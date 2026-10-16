package one.meshcore.shared.tools.ui

import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import io.github.koalaplot.core.line.AreaBaseline
import io.github.koalaplot.core.line.AreaPlot2
import io.github.koalaplot.core.style.AreaStyle
import io.github.koalaplot.core.style.LineStyle
import io.github.koalaplot.core.util.ExperimentalKoalaPlotApi
import io.github.koalaplot.core.xygraph.DoubleLinearAxisModel
import io.github.koalaplot.core.xygraph.XYGraph
import io.github.koalaplot.core.xygraph.rememberAxisStyle
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_min
import one.meshcore.shared.tools.NOISE_CHART_Y_MAX_DBM
import one.meshcore.shared.tools.NOISE_CHART_Y_MIN_DBM
import one.meshcore.shared.tools.NoiseFloorReading
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@OptIn(ExperimentalKoalaPlotApi::class)
@Composable
internal fun NoiseFloorChartCanvas(readings: List<NoiseFloorReading>, startEpochMs: Long, label: String) {
    val points = remember(readings, startEpochMs) { noiseFloorChartSeries(readings, startEpochMs) }
    val domain = noiseFloorChartMinuteDomain(readings, startEpochMs)
    val xAxis = remember(domain) { noiseFloorChartTimeAxis(domain) }
    val seriesColor = MaterialTheme.colorScheme.primary
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val grid = LineStyle(SolidColor(MaterialTheme.colorScheme.outlineVariant), GRID_WIDTH.dp)
    val axisStyle = rememberAxisStyle(
        color = axisColor,
        majorTickSize = ZERO_TICK_SIZE.dp,
        minorTickSize = ZERO_TICK_SIZE.dp,
    )
    val labelStyle = MaterialTheme.typography.labelSmall
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val minuteUnit = stringResource(Res.string.remotenodes_remote_nodes_settings_min)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val ticks = remember(xAxis, maxWidth) { xAxis.computeTickValues(maxWidth).majorTickValues }
        val tickLabels = remember(ticks) { ticks.map { it.roundToLong().toString() } }
        val labelSizes = remember(tickLabels, labelStyle, textMeasurer) {
            tickLabels.map { textMeasurer.measure(it, labelStyle).size }
        }
        val titleSize = remember(minuteUnit, labelStyle, textMeasurer) {
            textMeasurer.measure(minuteUnit, labelStyle).size
        }
        val labelHeight = with(density) { labelSizes.maxOf { it.height }.toDp() }
        val titleHeight = with(density) { titleSize.height.toDp() }
        val widestLabel = labelSizes.maxOf { it.width }
        var plotWidth by remember { mutableIntStateOf(0) }
        val requiredWidth = widestLabel + with(density) { LABEL_GAP.dp.toPx() }
        val stride = noiseFloorChartLabelStride(xAxis, ticks, requiredWidth, plotWidth)
        val yAxis = remember(labelHeight) {
            DoubleLinearAxisModel(
                NOISE_CHART_Y_MIN_DBM..NOISE_CHART_Y_MAX_DBM,
                minimumMajorTickIncrement = DBM_TICK_INCREMENT,
                minimumMajorTickSpacing = maxOf(DBM_TICK_SPACING.dp, labelHeight * Y_LABEL_SPACING_FACTOR),
                minorTickCount = NO_MINOR_TICKS,
            )
        }
        val timeLabel: @Composable (Double) -> Unit = { value ->
            val index = ticks.indexOf(value)
            if (index >= 0 && index % stride == 0) {
                Text(
                    tickLabels[index],
                    modifier = Modifier.wrapContentWidth(unbounded = true),
                    color = axisColor,
                    style = labelStyle,
                    maxLines = 1,
                )
            }
        }
        val readingLabel: @Composable (Double) -> Unit = {
            Text(it.roundToInt().toString(), color = axisColor, style = labelStyle, maxLines = 1)
        }
        XYGraph(
            xAxisModel = xAxis,
            yAxisModel = yAxis,
            modifier = Modifier
                .fillMaxWidth()
                .absolutePadding(right = with(density) { widestLabel.toDp() } / LABEL_EDGE_DIVISOR)
                .height(CHART_HEIGHT.dp + labelHeight + titleHeight)
                .clearAndSetSemantics { contentDescription = label },
            xAxisStyle = axisStyle,
            yAxisStyle = axisStyle,
            xAxisLabels = timeLabel,
            xAxisTitle = {
                Text(
                    minuteUnit,
                    color = axisColor,
                    style = labelStyle,
                )
            },
            yAxisLabels = readingLabel,
            horizontalMajorGridLineStyle = grid,
            verticalMajorGridLineStyle = grid,
            horizontalMinorGridLineStyle = null,
            verticalMinorGridLineStyle = null,
        ) {
            Box(Modifier.fillMaxSize().onSizeChanged { plotWidth = it.width }) {
                AreaPlot2(
                    data = points,
                    areaBaseline = AreaBaseline.ConstantLine(NOISE_CHART_Y_MIN_DBM),
                    areaStyle = AreaStyle(SolidColor(seriesColor), alpha = AREA_ALPHA),
                    lineStyle = LineStyle(SolidColor(seriesColor), SERIES_WIDTH.dp),
                    animationSpec = snap(),
                    symbol = {
                        Box(Modifier.size(POINT_DIAMETER.dp).background(seriesColor, CircleShape))
                    },
                )
            }
        }
    }
}

private const val CHART_HEIGHT = 180
private const val LABEL_GAP = 4
private const val LABEL_EDGE_DIVISOR = 2f
private const val Y_LABEL_SPACING_FACTOR = 2
private const val ZERO_TICK_SIZE = 0
private const val DBM_TICK_INCREMENT = 10.0
private const val DBM_TICK_SPACING = 30
private const val NO_MINOR_TICKS = 0
private const val GRID_WIDTH = 0.5f
private const val SERIES_WIDTH = 1.5f
private const val POINT_DIAMETER = 5
private const val AREA_ALPHA = 0.1f
