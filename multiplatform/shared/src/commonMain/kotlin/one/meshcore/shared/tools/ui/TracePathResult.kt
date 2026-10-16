package one.meshcore.shared.tools.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import one.meshcore.shared.map.snrQuality
import one.meshcore.shared.map.ui.rememberSnrBadgeFormatter
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_path_detail_round_trip
import one.meshcore.shared.resources.contacts_results_distance_info_hint
import one.meshcore.shared.resources.contacts_results_distance_unavailable_label
import one.meshcore.shared.resources.contacts_results_hop_avg_snr
import one.meshcore.shared.resources.contacts_results_hop_avg_snr_label
import one.meshcore.shared.resources.contacts_results_hop_my_device
import one.meshcore.shared.resources.contacts_results_hop_snr
import one.meshcore.shared.resources.contacts_results_partial_distance_hint
import one.meshcore.shared.resources.contacts_results_partial_distance_label
import one.meshcore.shared.resources.contacts_results_title
import one.meshcore.shared.resources.contacts_results_total_distance
import one.meshcore.shared.resources.contacts_results_unavailable
import one.meshcore.shared.resources.contacts_trace_failed
import one.meshcore.shared.trace.HopSnrStats
import one.meshcore.shared.trace.TraceHop
import one.meshcore.shared.trace.TraceResult
import one.meshcore.shared.trace.hopStats
import one.meshcore.shared.trace.isDistanceUsingDeviceLocationFallback
import one.meshcore.shared.trace.latestHopSnr
import one.meshcore.shared.trace.totalPathDistanceMeters
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.SignalBarMeter
import one.meshcore.shared.ui.barTint
import one.meshcore.shared.ui.durationMsLabel
import one.meshcore.shared.ui.filledBarCount
import one.meshcore.shared.ui.formatTemplate
import one.meshcore.shared.ui.icons.AppIconButton
import one.meshcore.shared.ui.icons.AppIcons
import org.jetbrains.compose.resources.stringResource

/**
 * The SNR the hop row's signal bars read, mirroring the Swift `TraceResultHopRow.displaySNR`: the latest
 * run's value while a batch is still going, the batch average once it completes, otherwise this run's own
 * SNR. Reading the average mid-batch would hide the swing the operator is running the batch to see.
 */
internal fun traceHopSignalSnr(
    hop: TraceHop,
    index: Int,
    batchResults: List<TraceResult>,
    isBatchInProgress: Boolean,
): Double = when {
    isBatchInProgress -> latestHopSnr(batchResults, index) ?: hop.snr
    batchResults.size >= MIN_RESULTS_FOR_HOP_STATS -> hopStats(batchResults, index)?.avg ?: hop.snr
    else -> hop.snr
}

@Composable
internal fun TraceResultSection(
    result: TraceResult?,
    batchResults: List<TraceResult>,
    isBatchInProgress: Boolean,
    onShowDistanceInfo: () -> Unit,
    wrapInCard: Boolean = true,
) {
    val current = result ?: return
    val body: @Composable () -> Unit = {
        Column {
            if (!current.success) {
                val error = current.errorMessage ?: stringResource(Res.string.contacts_trace_failed)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = TRACE_RESULT_ERROR_VERTICAL_PADDING),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(TRACE_RESULT_ERROR_ICON_GAP),
                ) {
                    Icon(
                        imageVector = TRACE_RESULT_ERROR_ICON,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(TRACE_RESULT_ERROR_ICON_SIZE),
                    )
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                // A completed batch of 2+ runs shows avg/min/max SNR per hop (understates instability
                // less than the last run's SNR alone); a single run falls back to that run's plain SNR.
                val isBatch = batchResults.size >= MIN_RESULTS_FOR_HOP_STATS
                current.hops.forEachIndexed { index, hop ->
                    val stats = if (isBatch && !isBatchInProgress) hopStats(batchResults, index) else null
                    TraceResultHopRow(
                        hop = hop,
                        index = index,
                        stats = stats,
                        batchResults = batchResults,
                        isBatchInProgress = isBatchInProgress,
                    )
                }
                if (!isBatch) {
                    ListItem(
                        headlineContent = {
                            Text(stringResource(Res.string.contacts_path_detail_round_trip))
                        },
                        supportingContent = {
                            Text(durationMsLabel(current.durationMs), fontFamily = FontFamily.Monospace)
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                TotalDistanceRow(result = current, onShowInfo = onShowDistanceInfo)
            }
        }
    }
    if (wrapInCard) {
        PreferenceHubSection(
            title = stringResource(Res.string.contacts_results_title),
            applyHorizontalPadding = false,
        ) {
            body()
        }
    } else {
        body()
    }
}

@Composable
private fun TraceResultHopRow(
    hop: TraceHop,
    index: Int,
    stats: HopSnrStats?,
    batchResults: List<TraceResult>,
    isBatchInProgress: Boolean,
) {
    val myDevice = stringResource(Res.string.contacts_results_hop_my_device)
    val name = hop.resolvedName ?: myDevice
    val snrCaption = hopSnrCaption(
        isStartNode = hop.isStartNode,
        hopSnr = hop.snr,
        stats = stats,
        snrTemplate = stringResource(Res.string.contacts_results_hop_snr),
        avgTemplate = stringResource(Res.string.contacts_results_hop_avg_snr),
    )
    val avgSpoken = stats?.let {
        formatTemplate(
            stringResource(Res.string.contacts_results_hop_avg_snr_label),
            listOf(
                formatOneDecimalSigned(it.avg),
                formatOneDecimalSigned(it.min),
                formatOneDecimalSigned(it.max),
            ),
        )
    }
    val roleLabel = traceHopRoleLabel(hop)
    ListItem(
        headlineContent = { TraceResultHopHeadline(hop, name) },
        supportingContent = {
            Column {
                Text(roleLabel)
                if (snrCaption != null) {
                    Text(
                        text = snrCaption,
                        modifier = avgSpoken?.let { spoken ->
                            Modifier.semantics { contentDescription = spoken }
                        } ?: Modifier,
                    )
                }
            }
        },
        trailingContent = if (!hop.isStartNode) {
            {
                val quality = snrQuality(traceHopSignalSnr(hop, index, batchResults, isBatchInProgress))
                SignalBarMeter(
                    filledBars = quality.filledBarCount,
                    tint = quality.barTint(),
                )
            }
        } else {
            null
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Total path distance, or "Unavailable" with an explanation, for a successful [result]. Mirrors `TotalDistanceRow`. */
@Composable
private fun TotalDistanceRow(result: TraceResult, onShowInfo: () -> Unit) {
    val distance = remember(result) { totalPathDistanceMeters(result.hops) }
    val isFallback = remember(result) { isDistanceUsingDeviceLocationFallback(result.hops) }
    ListItem(
        headlineContent = { Text(stringResource(Res.string.contacts_results_total_distance)) },
        supportingContent = {
            if (distance != null) {
                Text(text = rememberSnrBadgeFormatter().formatDistance(distance))
            } else {
                Text(
                    text = stringResource(Res.string.contacts_results_unavailable),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailingContent = if (distance == null || isFallback) {
            {
                val infoLabel = if (distance == null) {
                    stringResource(Res.string.contacts_results_distance_unavailable_label)
                } else {
                    stringResource(Res.string.contacts_results_partial_distance_label)
                }
                val infoHint = if (distance == null) {
                    stringResource(Res.string.contacts_results_distance_info_hint)
                } else {
                    stringResource(Res.string.contacts_results_partial_distance_hint)
                }
                AppIconButton(
                    imageVector = AppIcons.Info,
                    contentDescription = infoLabel,
                    onClick = onShowInfo,
                    modifier = Modifier
                        .size(DISTANCE_INFO_BUTTON_SIZE.dp)
                        .semantics {
                            stateDescription = infoHint
                        },
                )
            }
        } else {
            null
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** One-decimal SNR for a hop row, sign preserved. [unit] is the localized dB suffix. */
internal fun formatSnr(snr: Double, unit: String): String = "${formatOneDecimalSigned(snr)} $unit"
internal const val MIN_RESULTS_FOR_HOP_STATS = 2
private const val DISTANCE_INFO_BUTTON_SIZE = 20

/** iOS `TraceResultsSectionView` failed-trace `exclamationmark.triangle` (system orange). */
internal val TRACE_RESULT_ERROR_ICON get() = AppIcons.Warning
private val TRACE_RESULT_ERROR_ICON_SIZE = 18.dp
private val TRACE_RESULT_ERROR_ICON_GAP = 8.dp
private val TRACE_RESULT_ERROR_VERTICAL_PADDING = 4.dp
