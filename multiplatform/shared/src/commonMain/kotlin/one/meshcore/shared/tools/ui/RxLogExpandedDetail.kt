package one.meshcore.shared.tools.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import one.meshcore.shared.model.toLowercaseHex
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_snr_badge_unit
import one.meshcore.shared.resources.tools_noise_floor_d_bm
import one.meshcore.shared.resources.tools_rx_log_bytes
import one.meshcore.shared.resources.tools_rx_log_channel_hash_label
import one.meshcore.shared.resources.tools_rx_log_channel_name_label
import one.meshcore.shared.resources.tools_rx_log_copy
import one.meshcore.shared.resources.tools_rx_log_from_label
import one.meshcore.shared.resources.tools_rx_log_hash_label
import one.meshcore.shared.resources.tools_rx_log_path_label
import one.meshcore.shared.resources.tools_rx_log_raw_payload
import one.meshcore.shared.resources.tools_rx_log_region_label
import one.meshcore.shared.resources.tools_rx_log_region_unresolved
import one.meshcore.shared.resources.tools_rx_log_rssi_label
import one.meshcore.shared.resources.tools_rx_log_size_label
import one.meshcore.shared.resources.tools_rx_log_snr_label
import one.meshcore.shared.resources.tools_rx_log_text_label
import one.meshcore.shared.resources.tools_rx_log_to_label
import one.meshcore.shared.resources.tools_rx_log_trace_route_label
import one.meshcore.shared.resources.tools_rx_log_type_label
import one.meshcore.shared.tools.DecryptStatus
import one.meshcore.shared.tools.RxLogEntry
import one.meshcore.shared.tools.RxLogPayloadType
import one.meshcore.shared.tools.channelHashByte
import one.meshcore.shared.tools.isDirectTextMessage
import one.meshcore.shared.tools.payloadTypeName
import one.meshcore.shared.tools.rawPayloadHex
import one.meshcore.shared.tools.recipientPrefix
import one.meshcore.shared.tools.resolveHashLabel
import one.meshcore.shared.tools.rxLogRegionLabel
import one.meshcore.shared.tools.senderPrefix
import one.meshcore.shared.tools.traceTargetHashes
import one.meshcore.shared.ui.PathHopChipRow
import one.meshcore.shared.ui.icons.AppIconButton
import one.meshcore.shared.ui.plainTextClipEntry
import one.meshcore.shared.ui.rememberAppHaptics
import org.jetbrains.compose.resources.stringResource

/** How long the raw-payload copy confirmation stays up before the Copy button returns. */
private const val COPY_CONFIRMATION_MILLIS = 2_000L

@Composable
internal fun RxLogExpandedDetail(
    entry: RxLogEntry,
    pathFacts: RxLogPathFacts,
    localPublicKeyPrefix: ByteArray?,
    nodeNames: Map<String, String>,
    youLabel: String,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val haptics = rememberAppHaptics()
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        // The confirmation is a flash, not a state: leaving the checkmark up hides the Copy button.
        if (copied) {
            delay(COPY_CONFIRMATION_MILLIS)
            copied = false
        }
    }
    val pathDetail = rxLogPathDetail(pathFacts)

    Column(
        modifier = Modifier.padding(top = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        entry.rssi?.let {
            val rssiUnit = stringResource(Res.string.tools_noise_floor_d_bm)
            DetailRow(stringResource(Res.string.tools_rx_log_rssi_label), rxLogRssiVisibleText(it, rssiUnit))
        }
        entry.snr?.let {
            val snrUnit = stringResource(Res.string.remotenodes_remote_nodes_status_snr_badge_unit)
            DetailRow(stringResource(Res.string.tools_rx_log_snr_label), formatSnr(it, snrUnit))
        }
        DetailRow(stringResource(Res.string.tools_rx_log_type_label), entry.payloadTypeName)
        DetailRow(
            stringResource(Res.string.tools_rx_log_size_label),
            "${entry.rawPayload.size} ${stringResource(Res.string.tools_rx_log_bytes)}",
        )
        if (entry.payloadType == RxLogPayloadType.TRACE && entry.traceTargetHashes() != null) {
            Column {
                Text(
                    stringResource(Res.string.tools_rx_log_trace_route_label),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PathHopChipRow(pathFacts.pathLabels)
            }
        } else {
            DetailRow(stringResource(Res.string.tools_rx_log_path_label), pathDetail)
        }
        DetailRow(stringResource(Res.string.tools_rx_log_hash_label), entry.packetHash)
        if (entry.isDirectTextMessage) {
            entry.senderPrefix?.let { sender ->
                DetailRow(
                    stringResource(Res.string.tools_rx_log_from_label),
                    resolveHashLabel(sender, localPublicKeyPrefix, nodeNames, youLabel),
                )
            }
            entry.recipientPrefix?.let { recipient ->
                DetailRow(
                    stringResource(Res.string.tools_rx_log_to_label),
                    resolveHashLabel(recipient, localPublicKeyPrefix, nodeNames, youLabel),
                )
            }
        }
        RxLogDecryptedRows(entry)

        // Raw payload + copy
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.tools_rx_log_raw_payload),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            AppIconButton(
                imageVector = rxLogCopyActionIcon(copied),
                contentDescription = stringResource(Res.string.tools_rx_log_copy),
                onClick = {
                    scope.launch {
                        clipboard.setClipEntry(plainTextClipEntry(entry.rawPayloadHex()))
                        copied = true
                        haptics.success()
                    }
                },
                tint = if (copied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = entry.rawPayloadHex().ifEmpty { "—" },
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RxLogDecryptedRows(entry: RxLogEntry) {
    if (entry.decryptStatus != DecryptStatus.SUCCESS) return
    entry.channelHashByte?.let { hash ->
        DetailRow(
            stringResource(Res.string.tools_rx_log_channel_hash_label),
            byteArrayOf(hash.toByte()).toLowercaseHex(),
        )
    }
    entry.channelName?.let { name ->
        DetailRow(stringResource(Res.string.tools_rx_log_channel_name_label), name)
    }
    // Only a transport-coded packet carries the code the region is resolved from, so a plain
    // flood packet gets no row instead of a permanently unresolved one.
    if (entry.transportCode?.isNotEmpty() == true) {
        DetailRow(
            stringResource(Res.string.tools_rx_log_region_label),
            rxLogRegionLabel(
                entry.regionScope,
                entry.regionScopeMatches,
                stringResource(Res.string.tools_rx_log_region_unresolved),
            ),
        )
    }
    entry.decodedText?.let { text ->
        DetailRow(stringResource(Res.string.tools_rx_log_text_label), text)
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
