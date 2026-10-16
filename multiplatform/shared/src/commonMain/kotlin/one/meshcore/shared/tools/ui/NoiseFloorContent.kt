package one.meshcore.shared.tools.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.unit.dp
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.tools_noise_floor_average
import one.meshcore.shared.resources.tools_noise_floor_chart_accessibility
import one.meshcore.shared.resources.tools_noise_floor_chart_accessibility_empty
import one.meshcore.shared.resources.tools_noise_floor_d_b
import one.meshcore.shared.resources.tools_noise_floor_d_bm
import one.meshcore.shared.resources.tools_noise_floor_last_rssi
import one.meshcore.shared.resources.tools_noise_floor_last_snr
import one.meshcore.shared.resources.tools_noise_floor_maximum
import one.meshcore.shared.resources.tools_noise_floor_minimum
import one.meshcore.shared.resources.tools_noise_floor_no_reading
import one.meshcore.shared.resources.tools_noise_floor_quality_excellent
import one.meshcore.shared.resources.tools_noise_floor_quality_fair
import one.meshcore.shared.resources.tools_noise_floor_quality_good
import one.meshcore.shared.resources.tools_noise_floor_quality_poor
import one.meshcore.shared.resources.tools_noise_floor_quality_unknown
import one.meshcore.shared.resources.tools_noise_floor_statistics
import one.meshcore.shared.resources.tools_noise_floor_trend_decreasing
import one.meshcore.shared.resources.tools_noise_floor_trend_increasing
import one.meshcore.shared.resources.tools_noise_floor_trend_stable
import one.meshcore.shared.tools.NoiseFloorQuality
import one.meshcore.shared.tools.NoiseFloorReading
import one.meshcore.shared.tools.NoiseFloorTrend
import one.meshcore.shared.tools.currentReadingAccessibilityLabel
import one.meshcore.shared.tools.noiseFloorChartSummary
import one.meshcore.shared.tools.noiseFloorStatistics
import one.meshcore.shared.tools.noiseFloorTrend
import one.meshcore.shared.ui.EMPTY_STATE_ICON_SIZE
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.PreferenceHubStatusRow
import one.meshcore.shared.ui.PreferenceHubValueRow
import one.meshcore.shared.ui.formattedStringResource
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.preferenceHubEdgeFill
import one.meshcore.shared.ui.preferenceHubEdgeFillScroll
import one.meshcore.shared.ui.tint
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun NoiseFloorContent(
    readings: List<NoiseFloorReading>,
    chartStartEpochMs: Long,
    reading: NoiseFloorReading,
    errorMessage: String?,
    modifier: Modifier,
) {
    val stats = noiseFloorStatistics(readings)
    val quality = NoiseFloorQuality.from(reading.noiseFloor)
    val trend = noiseFloorTrend(readings)
    val dbm = stringResource(Res.string.tools_noise_floor_d_bm)
    val db = stringResource(Res.string.tools_noise_floor_d_b)
    Column(
        modifier = modifier.preferenceHubEdgeFillScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val qualityText = qualityLabel(quality)
        val readingLabel = currentReadingAccessibilityLabel(
            noiseFloor = reading.noiseFloor,
            dbm = dbm,
            qualityLabel = qualityText,
            noReading = stringResource(Res.string.tools_noise_floor_no_reading),
        )
        Column(
            modifier = Modifier.clearAndSetSemantics {
                contentDescription = readingLabel
                liveRegion = LiveRegionMode.Polite
            },
        ) {
            Text(
                text = noiseFloorCurrentValueText(reading.noiseFloor),
                style = MaterialTheme.typography.displaySmall,
            )
            // The unit sits under the number as a secondary subtitle.
            Text(
                text = dbm,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (noiseFloorQualityShowsBadge(quality)) {
                val tint = noiseFloorBadgeColor(quality)
                Text(
                    text = qualityText,
                    style = MaterialTheme.typography.labelLarge,
                    color = tint,
                )
            }
        }
        if (readings.size >= 2) {
            NoiseFloorChartCanvas(readings, chartStartEpochMs, chartAccessibilityLabel(readings))
        }
        PreferenceHubSection(
            title = stringResource(Res.string.tools_noise_floor_statistics),
            applyHorizontalPadding = false,
        ) {
            stats?.let { s ->
                PreferenceHubValueRow(stringResource(Res.string.tools_noise_floor_minimum), "${s.min} $dbm")
                PreferenceHubValueRow(
                    stringResource(Res.string.tools_noise_floor_average),
                    "${formatOneDecimalSigned(s.average)} $dbm",
                )
                PreferenceHubValueRow(stringResource(Res.string.tools_noise_floor_maximum), "${s.max} $dbm")
            }
            PreferenceHubValueRow(stringResource(Res.string.tools_noise_floor_last_rssi), "${reading.lastRssi} $dbm")
            PreferenceHubValueRow(
                stringResource(Res.string.tools_noise_floor_last_snr),
                "${formatOneDecimalSigned(reading.lastSnr)} $db",
            )
            PreferenceHubStatusRow(trendLabel(trend))
        }
        errorMessage?.let { message ->
            PreferenceHubSection(applyHorizontalPadding = false) {
                ListItem(
                    headlineContent = {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                    leadingContent = {
                        Icon(
                            imageVector = AppIcons.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
internal fun NoiseFloorEmptyPane(icon: ImageVector, title: String, description: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.preferenceHubEdgeFill(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(EMPTY_STATE_ICON_SIZE),
            )
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun qualityLabel(quality: NoiseFloorQuality): String = when (quality) {
    NoiseFloorQuality.EXCELLENT -> stringResource(Res.string.tools_noise_floor_quality_excellent)
    NoiseFloorQuality.GOOD -> stringResource(Res.string.tools_noise_floor_quality_good)
    NoiseFloorQuality.FAIR -> stringResource(Res.string.tools_noise_floor_quality_fair)
    NoiseFloorQuality.POOR -> stringResource(Res.string.tools_noise_floor_quality_poor)
    NoiseFloorQuality.UNKNOWN -> stringResource(Res.string.tools_noise_floor_quality_unknown)
}

/**
 * The sparkline's spoken description: a drawing carries no text, so the series is summarized the way the
 * iOS chart label reads it out (count, min, max, average, trend).
 */
@Composable
internal fun chartAccessibilityLabel(readings: List<NoiseFloorReading>): String {
    val summary = noiseFloorChartSummary(readings)
        ?: return stringResource(Res.string.tools_noise_floor_chart_accessibility_empty)
    return formattedStringResource(
        Res.string.tools_noise_floor_chart_accessibility,
        summary.count,
        summary.min,
        summary.max,
        summary.average,
        trendLabel(summary.trend),
    )
}

@Composable
private fun trendLabel(trend: NoiseFloorTrend): String = when (trend) {
    NoiseFloorTrend.STABLE -> stringResource(Res.string.tools_noise_floor_trend_stable)
    NoiseFloorTrend.INCREASING -> stringResource(Res.string.tools_noise_floor_trend_increasing)
    NoiseFloorTrend.DECREASING -> stringResource(Res.string.tools_noise_floor_trend_decreasing)
}

/** iOS `CurrentReadingSection`: the live number is its own line, not `"N dBm"`. */
internal fun noiseFloorCurrentValueText(noiseFloor: Int): String = noiseFloor.toString()

@Composable
private fun noiseFloorBadgeColor(quality: NoiseFloorQuality): Color = noiseFloorQualityBadgeTint(quality).tint()
