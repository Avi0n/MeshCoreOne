package one.meshcore.shared.remotenode.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import one.meshcore.shared.chat.ui.LocalTimestampFormatter
import one.meshcore.shared.chat.ui.TimestampFormatter
import one.meshcore.shared.remotenode.LocationReportFormat
import one.meshcore.shared.remotenode.TelemetryChartPoint

/**
 * Compact Material 3 metric chart: title row, Canvas sparkline, last value + unit.
 * Used by telemetry history radio/sensors/neighbors sections. Prefer high-contrast
 * primary/secondary strokes over translucent fills (safety-readable under outdoor glare).
 *
 * [yAxisDomain] anchors the Y-axis to a caller-supplied range (battery-curve or shared
 * packet-count anchoring; see `RadioMetricCharts.voltageChartDomain`/`sharedDomain`) instead of
 * autoscaling tightly to the visible samples. Pass `null` to autoscale (the default for series
 * with no natural anchor, e.g. SNR/RSSI).
 *
 * A long-press-then-drag over the chart scrubs a readout chip showing the nearest sample's value
 * and timestamp, mirroring iOS's `ChartScrubGesture`.
 */
@Composable
fun MetricChartView(
    title: String,
    points: List<TelemetryChartPoint>,
    unit: String,
    modifier: Modifier = Modifier,
    lineColor: Color = MaterialTheme.colorScheme.primary,
    secondaryPoints: List<TelemetryChartPoint> = emptyList(),
    secondaryLineColor: Color = MaterialTheme.colorScheme.tertiary,
    secondaryLabel: String? = null,
    primaryLabel: String? = null,
    yAxisDomain: ClosedRange<Double>? = null,
) {
    val formatter = LocalTimestampFormatter.current
    val chartModel = remember { ChartDrawModel() }
    // Steady appends read the cached axis snapshot. The scale walk runs only until the first
    // labels exist, so a later sample does not rebuild the plot group.
    val labels = chartModel.axisLabels
        ?: metricChartScale(points, secondaryPoints, yAxisDomain)?.let { chartModel.labelsFor(it, formatter) }
    // Runs after this composition and before draw, so the line updates in the same frame.
    SideEffect {
        chartModel.publish(points, secondaryPoints, unit, yAxisDomain, formatter)
    }
    val lastText = lastValueCaption(
        primary = points.lastOrNull()?.value,
        secondary = secondaryPoints.lastOrNull()?.value,
        unit = unit,
        primaryLabel = primaryLabel,
        secondaryLabel = secondaryLabel,
    )
    val showPrimaryLegend = primaryLabel != null && points.isNotEmpty()
    val showSecondaryLegend = secondaryLabel != null && secondaryPoints.isNotEmpty()
    val hasEnoughData = metricChartHasEnoughData(points.size, secondaryPoints.size)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ChartHeader(title = title, lastText = lastText)
        if (!hasEnoughData) {
            MetricChartEmptyState(
                value = metricChartEmptyStateValue(
                    primaryFirst = points.firstOrNull()?.value,
                    secondaryFirst = secondaryPoints.firstOrNull()?.value,
                ),
                unit = unit,
            )
        } else if (labels != null) {
            ChartCanvas(
                title = title,
                labels = labels,
                model = chartModel,
                lineColor = lineColor,
                secondaryLineColor = secondaryLineColor,
            )
            if (showPrimaryLegend || showSecondaryLegend) {
                ChartLegend(
                    primaryLabel = if (showPrimaryLegend) primaryLabel else null,
                    secondaryLabel = if (showSecondaryLegend) secondaryLabel else null,
                    lineColor = lineColor,
                    secondaryLineColor = secondaryLineColor,
                )
            }
        }
    }
}

/** Local month, day, and short clock — same stamp as location history and iOS chart scrub. */
internal fun formatScrubTimestamp(epochSeconds: Double, formatter: TimestampFormatter): String =
    LocationReportFormat.absoluteTime(epochSeconds, formatter)

/** iOS `hasEnoughData`: a line needs two samples on at least one series. */
internal fun metricChartHasEnoughData(primaryCount: Int, secondaryCount: Int): Boolean =
    primaryCount >= 2 || secondaryCount >= 2

/** iOS `MetricChartView` labels the scrubbable chart with the section title. */
internal fun metricChartAccessibilityLabel(title: String, hasEnoughData: Boolean): String? =
    title.takeIf { hasEnoughData }

/**
 * iOS `emptyStateValue`: first samples across drawn series, summed so a Direct+Flood overlay
 * reports the total instead of only Direct.
 */
internal fun metricChartEmptyStateValue(primaryFirst: Double?, secondaryFirst: Double?): Double? {
    val values = listOfNotNull(primaryFirst, secondaryFirst)
    return if (values.isEmpty()) null else values.sum()
}
