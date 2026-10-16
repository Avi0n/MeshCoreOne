package one.meshcore.shared.remotenode.ui

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import one.meshcore.shared.chat.ui.TimestampFormatter
import one.meshcore.shared.chat.ui.chartLocalEpochDay
import one.meshcore.shared.chat.ui.chatMonthDayLabel
import one.meshcore.shared.remotenode.TelemetryChartPoint
import one.meshcore.shared.ui.ChartAxisTick

private const val LABEL_SLOT_0 = 0
private const val LABEL_SLOT_1 = 1
private const val LABEL_SLOT_2 = 2
private const val LABEL_SLOT_3 = 3
private const val Y_LABEL_SLOTS = 3
private const val X_LABEL_SLOTS = 4
private const val SINGLE_LABEL = 1
private const val X_FRACTION_DIVISOR = 3f
private const val X_FRACTION_1 = 1f / X_FRACTION_DIVISOR
private const val X_FRACTION_2 = 2f / X_FRACTION_DIVISOR

/**
 * Point lists are snapshot state so the plot draw observes them.
 * [publish] writes [axisLabels] only when [labelsFor] returns a new instance, so a sample
 * that keeps the same tick text does not restart composition.
 */
@Stable
internal class ChartDrawModel {
    var primary by mutableStateOf<List<TelemetryChartPoint>>(emptyList())
    var secondary by mutableStateOf<List<TelemetryChartPoint>>(emptyList())
    var unit by mutableStateOf("")
    var yDomain by mutableStateOf<ClosedRange<Double>?>(null)
    var axisLabels by mutableStateOf<ChartAxisLabels?>(null)

    private var cachedLabels: ChartAxisLabels? = null
    private var labeledValueMin = Double.NaN
    private var labeledValueMax = Double.NaN
    private var labelDays = arrayOfNulls<Long>(0)
    private var labeledFormatter: TimestampFormatter? = null

    fun publish(
        primaryPoints: List<TelemetryChartPoint>,
        secondaryPoints: List<TelemetryChartPoint>,
        unitText: String,
        domain: ClosedRange<Double>?,
        formatter: TimestampFormatter,
    ) {
        val scale = metricChartScale(primaryPoints, secondaryPoints, domain)
        if (scale != null) {
            val next = labelsFor(scale, formatter)
            if (axisLabels !== next) axisLabels = next
        }
        primary = primaryPoints
        secondary = secondaryPoints
        unit = unitText
        yDomain = domain
    }

    fun labelsFor(scale: MetricChartScale, formatter: TimestampFormatter): ChartAxisLabels {
        val current = cachedLabels
        val reusable = current?.takeIf { sameLabelKey(scale, formatter) }
        if (reusable != null) return reusable
        val next = chartAxisLabels(scale, formatter)
        rememberLabelKey(scale, formatter)
        if (current != next) cachedLabels = next
        return next
    }

    private fun sameLabelKey(scale: MetricChartScale, formatter: TimestampFormatter): Boolean {
        if (labeledFormatter !== formatter) return false
        val instants = metricChartXTickInstants(scale.timeStart, scale.timeEnd)
        val valuesMatch = scale.valueMin == labeledValueMin && scale.valueMax == labeledValueMax
        val daysMatch = instants.size == labelDays.size &&
            instants.indices.all { index -> chartLocalEpochDay(instants[index], formatter) == labelDays[index] }
        return valuesMatch && daysMatch
    }

    private fun rememberLabelKey(scale: MetricChartScale, formatter: TimestampFormatter) {
        labeledFormatter = formatter
        labeledValueMin = scale.valueMin
        labeledValueMax = scale.valueMax
        val instants = metricChartXTickInstants(scale.timeStart, scale.timeEnd)
        if (labelDays.size != instants.size) labelDays = arrayOfNulls(instants.size)
        for (index in instants.indices) {
            labelDays[index] = chartLocalEpochDay(instants[index], formatter)
        }
    }
}

@Immutable
internal data class ChartAxisLabels(
    val y0: String,
    val y1: String,
    val y2: String,
    val yCount: Int,
    val x0: String,
    val x1: String,
    val x2: String,
    val x3: String,
    val xCount: Int,
)

internal fun chartAxisLabels(scale: MetricChartScale, formatter: TimestampFormatter): ChartAxisLabels {
    val yTicks = metricChartYTicks(scale.valueMin, scale.valueMax).asReversed()
    val xInstants = metricChartXTickInstants(scale.timeStart, scale.timeEnd)
    val yLabels = labelSlots(yTicks.map { formatValue(it) }, Y_LABEL_SLOTS)
    val xLabels = labelSlots(xInstants.map { chatMonthDayLabel(it, formatter) }, X_LABEL_SLOTS)
    return ChartAxisLabels(
        y0 = yLabels[LABEL_SLOT_0],
        y1 = yLabels[LABEL_SLOT_1],
        y2 = yLabels[LABEL_SLOT_2],
        yCount = yTicks.size,
        x0 = xLabels[LABEL_SLOT_0],
        x1 = xLabels[LABEL_SLOT_1],
        x2 = xLabels[LABEL_SLOT_2],
        x3 = xLabels[LABEL_SLOT_3],
        xCount = xInstants.size,
    )
}

internal fun ChartAxisLabels.yLabelsTopToBottom(): List<String> {
    if (yCount == SINGLE_LABEL) return listOf(y0)
    return listOf(y0, y1, y2)
}

internal fun ChartAxisLabels.xTicks(): List<ChartAxisTick> {
    if (xCount == SINGLE_LABEL) return listOf(ChartAxisTick(fraction = 0f, label = x0))
    return listOf(
        ChartAxisTick(fraction = 0f, label = x0),
        ChartAxisTick(fraction = X_FRACTION_1, label = x1),
        ChartAxisTick(fraction = X_FRACTION_2, label = x2),
        ChartAxisTick(fraction = 1f, label = x3),
    )
}

/** A scrub snapped to a plotted time. [x] is that time, not the finger. */
internal data class ChartScrub(
    val x: Float,
    val snappedTimestamp: Double,
    val primary: TelemetryChartPoint?,
    val secondary: TelemetryChartPoint?,
)

internal fun scrubSelection(x: Float, width: Float, model: ChartDrawModel): ChartScrub? {
    val selection = if (width <= 0f) {
        null
    } else {
        metricChartScrub(x.toDouble() / width.toDouble(), model.primary, model.secondary)
    }
    return selection?.let { picked ->
        ChartScrub(
            x = (picked.xFraction * width).toFloat(),
            snappedTimestamp = picked.snappedTimestamp,
            primary = picked.primary,
            secondary = picked.secondary,
        )
    }
}

private fun labelSlots(labels: List<String>, slots: Int): Array<String> {
    val out = Array(slots) { "" }
    for (index in labels.indices) {
        if (index >= slots) break
        out[index] = labels[index]
    }
    return out
}
