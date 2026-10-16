package one.meshcore.shared.tools.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import one.meshcore.shared.chat.ui.LocalTimestampFormatter
import one.meshcore.shared.chat.ui.snrQualityLabel
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_snr_badge_unit
import one.meshcore.shared.resources.tools_rx_log_bytes
import one.meshcore.shared.resources.tools_rx_log_direct
import one.meshcore.shared.resources.tools_rx_log_filter_all
import one.meshcore.shared.resources.tools_rx_log_filter_decrypted
import one.meshcore.shared.resources.tools_rx_log_filter_direct_only
import one.meshcore.shared.resources.tools_rx_log_filter_failed
import one.meshcore.shared.resources.tools_rx_log_filter_flood_only
import one.meshcore.shared.resources.tools_rx_log_flood
import one.meshcore.shared.resources.tools_rx_log_hop_plural
import one.meshcore.shared.resources.tools_rx_log_hop_singular
import one.meshcore.shared.resources.tools_rx_log_live
import one.meshcore.shared.resources.tools_rx_log_offline
import one.meshcore.shared.resources.tools_rx_log_path_you
import one.meshcore.shared.resources.tools_rx_log_received_times
import one.meshcore.shared.resources.tools_rx_log_signal_strength
import one.meshcore.shared.tools.RxLogDecryptFilter
import one.meshcore.shared.tools.RxLogEntry
import one.meshcore.shared.tools.RxLogRouteFilter
import one.meshcore.shared.tools.entrySnrQuality
import one.meshcore.shared.tools.hopCount
import one.meshcore.shared.tools.isDirectTextMessage
import one.meshcore.shared.tools.pathHopLabels
import one.meshcore.shared.tools.payloadTypeName
import one.meshcore.shared.tools.recipientPrefix
import one.meshcore.shared.tools.resolveHashLabel
import one.meshcore.shared.tools.rxLogReceivedLabel
import one.meshcore.shared.tools.senderPrefix
import one.meshcore.shared.ui.EMPTY_STATE_ICON_SIZE
import one.meshcore.shared.ui.PathHopChipRow
import one.meshcore.shared.ui.SignalBarMeter
import one.meshcore.shared.ui.barTint
import one.meshcore.shared.ui.filledBarCount
import one.meshcore.shared.ui.formattedStringResource
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.menu.McMenuItem
import one.meshcore.shared.ui.rememberAppHaptics
import org.jetbrains.compose.resources.stringResource

private val PLACEHOLDER_PADDING = 24.dp

private val RX_LOG_DISCLOSURE_ICON_SIZE = 18.dp
private const val RX_LOG_LIVE_DOT_DP = 8

@Composable
internal fun RxLogPlaceholder(title: String, description: String, icon: ImageVector, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(PLACEHOLDER_PADDING),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(bottom = 12.dp)
                    .size(EMPTY_STATE_ICON_SIZE),
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
internal fun LiveStatusHeader(isLive: Boolean, packetsLabel: String) {
    val statusLabel = stringResource(
        if (isLive) Res.string.tools_rx_log_live else Res.string.tools_rx_log_offline,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = rxLogLiveStatusAnnouncement(statusLabel, packetsLabel)
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatusPill(isLive = isLive)
        Text(
            packetsLabel,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatusPill(isLive: Boolean) {
    val pillColor = if (isLive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(RX_LOG_LIVE_DOT_DP.dp)
                .clip(CircleShape)
                .background(pillColor),
        )
        Text(
            text = stringResource(
                if (isLive) Res.string.tools_rx_log_live else Res.string.tools_rx_log_offline,
            ),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
internal fun RouteFilterItem(
    value: RxLogRouteFilter,
    selected: RxLogRouteFilter,
    onSelect: (RxLogRouteFilter) -> Unit,
) {
    val label = when (value) {
        RxLogRouteFilter.ALL -> stringResource(Res.string.tools_rx_log_filter_all)
        RxLogRouteFilter.FLOOD_ONLY -> stringResource(Res.string.tools_rx_log_filter_flood_only)
        RxLogRouteFilter.DIRECT_ONLY -> stringResource(Res.string.tools_rx_log_filter_direct_only)
    }
    McMenuItem(
        text = label,
        selected = value == selected,
        onClick = { onSelect(value) },
    )
}

@Composable
internal fun DecryptFilterItem(
    value: RxLogDecryptFilter,
    selected: RxLogDecryptFilter,
    onSelect: (RxLogDecryptFilter) -> Unit,
) {
    val label = when (value) {
        RxLogDecryptFilter.ALL -> stringResource(Res.string.tools_rx_log_filter_all)
        RxLogDecryptFilter.DECRYPTED -> stringResource(Res.string.tools_rx_log_filter_decrypted)
        RxLogDecryptFilter.FAILED -> stringResource(Res.string.tools_rx_log_filter_failed)
    }
    McMenuItem(
        text = label,
        selected = value == selected,
        onClick = { onSelect(value) },
    )
}

@Composable
internal fun RxLogExpandableRow(
    entry: RxLogEntry,
    expanded: Boolean,
    groupCount: Int,
    localPublicKeyPrefix: ByteArray?,
    nodeNames: Map<String, String>,
    onToggle: () -> Unit,
) {
    val haptics = rememberAppHaptics()
    val youLabel = stringResource(Res.string.tools_rx_log_path_you)
    val directLabel = stringResource(Res.string.tools_rx_log_direct)
    val floodLabel = stringResource(Res.string.tools_rx_log_flood)
    val hopSingular = stringResource(Res.string.tools_rx_log_hop_singular)
    val hopPlural = stringResource(Res.string.tools_rx_log_hop_plural)
    val quality = entry.entrySnrQuality
    val routeColor = rxLogRouteTint(entry.isFlood)
    val pathLabels = entry.pathHopLabels(localPublicKeyPrefix, youLabel)
    val pathFacts = RxLogPathFacts(
        pathNodesEmpty = entry.pathNodes.isEmpty(),
        hopCount = entry.hopCount,
        pathLabels = pathLabels,
        directLabel = directLabel,
        hopSingular = hopSingular,
        hopPlural = hopPlural,
    )
    val collapsed = rxLogCollapsedPath(pathFacts)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = {
                haptics.selection()
                onToggle()
            })
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        // Line 1: route + signal bars
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (entry.isFlood) floodLabel else directLabel,
                style = MaterialTheme.typography.labelMedium,
                color = routeColor,
            )
            Text(
                text = rxLogReceivedLabel(entry.receivedAtEpochMs, formatter = LocalTimestampFormatter.current),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
            Spacer(Modifier.weight(1f))
            if (entry.snr != null) {
                val signalLabel = formattedStringResource(
                    Res.string.tools_rx_log_signal_strength,
                    snrQualityLabel(quality),
                )
                SignalBarMeter(
                    filledBars = quality.filledBarCount,
                    tint = quality.barTint(),
                    modifier = Modifier.semantics { contentDescription = signalLabel },
                )
            }
            Icon(
                imageVector = if (expanded) AppIcons.KeyboardArrowUp else AppIcons.ExpandMore,
                contentDescription = null,
                modifier = Modifier.padding(start = 8.dp).size(RX_LOG_DISCLOSURE_ICON_SIZE),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Line 2: path + optional DM from→to
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(top = 2.dp),
        ) {
            if (collapsed.chips.isNotEmpty()) {
                PathHopChipRow(
                    hops = collapsed.chips,
                    modifier = Modifier.weight(1f, fill = false).semantics {
                        contentDescription = collapsed.spoken
                    },
                )
            } else {
                Text(
                    text = collapsed.spoken,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            if (entry.isDirectTextMessage) {
                val sender = entry.senderPrefix
                val recipient = entry.recipientPrefix
                if (sender != null && recipient != null) {
                    PathHopChipRow(
                        hops = listOf(
                            resolveHashLabel(sender, localPublicKeyPrefix, nodeNames, youLabel),
                            resolveHashLabel(recipient, localPublicKeyPrefix, nodeNames, youLabel),
                        ),
                    )
                }
            }
        }
        // Line 3: preview + SNR + group count
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val preview = rxLogRowPreview(
                decodedText = entry.decodedText,
                typeName = entry.payloadTypeName,
                version = entry.payloadVersion,
                size = entry.rawPayload.size,
                bytesLabel = stringResource(Res.string.tools_rx_log_bytes),
            )
            Text(
                text = preview,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            entry.snr?.let {
                val snrUnit = stringResource(Res.string.remotenodes_remote_nodes_status_snr_badge_unit)
                Text(
                    text = formatSnr(it, snrUnit),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            if (groupCount > 1) {
                val repeatsLabel = formattedStringResource(Res.string.tools_rx_log_received_times, groupCount)
                Text(
                    text = "×$groupCount",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .semantics { contentDescription = repeatsLabel },
                )
            }
        }

        AnimatedVisibility(visible = expanded) {
            RxLogExpandedDetail(
                entry = entry,
                pathFacts = pathFacts,
                localPublicKeyPrefix = localPublicKeyPrefix,
                nodeNames = nodeNames,
                youLabel = youLabel,
            )
        }
    }
}
