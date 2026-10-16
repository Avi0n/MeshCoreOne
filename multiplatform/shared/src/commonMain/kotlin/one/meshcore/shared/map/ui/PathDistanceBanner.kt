package one.meshcore.shared.map.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import one.meshcore.shared.map.MetricSnrBadgeFormatter
import one.meshcore.shared.map.SnrBadgeFormatter
import one.meshcore.shared.remotenode.LPPDisplay
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_path_distance_incomplete
import one.meshcore.shared.resources.chats_path_distance_incomplete_explanation
import one.meshcore.shared.resources.chats_path_distance_incomplete_title
import one.meshcore.shared.resources.contacts_trace_map_hops
import one.meshcore.shared.resources.localizable_common_ok
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_snr_badge_unit
import one.meshcore.shared.ui.AcknowledgedAlertDialog
import one.meshcore.shared.ui.formattedStringResource
import one.meshcore.shared.ui.icons.AppIcons
import org.jetbrains.compose.resources.stringResource

/**
 * Path summary shown over a message-path or trace-path map: hop count and, when at least two nodes
 * have coordinates, the drawn-path distance. One read-only surface, not tappable chips.
 */
@Composable
fun PathDistanceBanner(
    hopCount: Int,
    totalPathDistanceMeters: Double?,
    modifier: Modifier = Modifier,
    isDistanceIncomplete: Boolean = false,
    showsHopCount: Boolean = true,
    formatter: SnrBadgeFormatter = rememberSnrBadgeFormatter(),
) {
    val hopsLabel = if (showsHopCount) {
        formattedStringResource(Res.string.contacts_trace_map_hops, hopCount)
    } else {
        null
    }
    val distanceLabel = totalPathDistanceMeters?.let(formatter::formatDistance)
    val incompleteLabel = if (isDistanceIncomplete) {
        stringResource(Res.string.chats_path_distance_incomplete)
    } else {
        null
    }
    val spoken = joinPathDistanceBanner(hopsLabel, distanceLabel, incompleteLabel)
    var showExplanation by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier
            .padding(horizontal = HORIZONTAL_PADDING, vertical = VERTICAL_PADDING)
            .semantics(mergeDescendants = true) { contentDescription = spoken },
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.extraSmall,
    ) {
        Row {
            Text(
                text = joinPathDistanceBanner(hopsLabel, distanceLabel),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = CHIP_H_PADDING, vertical = CHIP_V_PADDING),
            )
            if (isDistanceIncomplete) {
                IconButton(onClick = { showExplanation = true }) {
                    Icon(imageVector = AppIcons.Info, contentDescription = incompleteLabel)
                }
            }
        }
    }
    if (showExplanation) {
        AcknowledgedAlertDialog(
            title = stringResource(Res.string.chats_path_distance_incomplete_title),
            message = stringResource(Res.string.chats_path_distance_incomplete_explanation),
            confirmLabel = stringResource(Res.string.localizable_common_ok),
            onDismiss = { showExplanation = false },
        )
    }
}

/** SNR badge unit iOS `MapLine.snrBadgeText` reads from `RemoteNodes.Status.snrBadgeUnit`. */
internal fun snrBadgeUnitResource() = Res.string.remotenodes_remote_nodes_status_snr_badge_unit

/** Metric badge formatter using the localized SNR unit, so map badges stay on the same string as iOS. */
@Composable
fun rememberSnrBadgeFormatter(): SnrBadgeFormatter {
    val unit = stringResource(snrBadgeUnitResource())
    val system = LPPDisplay.currentMeasurementSystem()
    val symbols = LPPDisplay.currentLengthSymbols()
    return remember(unit, system, symbols) {
        MetricSnrBadgeFormatter(snrUnit = unit, system = system, symbols = symbols)
    }
}

/**
 * Intermediate-hop count for a trace banner: start and end nodes are not hops. Mirrors
 * `result.hops.count - 2` on iOS `TracePathMapView`.
 */
fun tracePathBannerHopCount(hopCountIncludingEndpoints: Int): Int =
    hopCountIncludingEndpoints - TRACE_BANNER_ENDPOINT_COUNT

/** Joins the hops label and optional road distance for TalkBack; the banner draws one line. */
fun joinPathDistanceBanner(hopsLabel: String?, formattedDistance: String?, incompleteLabel: String? = null): String =
    listOfNotNull(hopsLabel, formattedDistance, incompleteLabel).joinToString(", ")

private val HORIZONTAL_PADDING = 16.dp
private val VERTICAL_PADDING = 8.dp
private val CHIP_H_PADDING = 12.dp
private val CHIP_V_PADDING = 6.dp
private const val TRACE_BANNER_ENDPOINT_COUNT = 2
