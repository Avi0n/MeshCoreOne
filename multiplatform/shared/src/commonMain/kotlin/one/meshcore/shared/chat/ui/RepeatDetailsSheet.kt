package one.meshcore.shared.chat.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import one.meshcore.shared.backup.MessageRepeatDto
import one.meshcore.shared.chat.NodeNameMatchKind
import one.meshcore.shared.chat.NodeNameResolution
import one.meshcore.shared.chat.pathdetail.hopCount
import one.meshcore.shared.chat.pathdetail.repeatRowTitle
import one.meshcore.shared.chat.pathdetail.repeaterHash
import one.meshcore.shared.chat.pathdetail.repeaterHashFormatted
import one.meshcore.shared.chat.pathdetail.rssiFormatted
import one.meshcore.shared.chat.pathdetail.snrFormatted
import one.meshcore.shared.chat.pathdetail.snrQuality
import one.meshcore.shared.remotenode.ui.PossibleMatchMarker
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_message_action_repeat_details
import one.meshcore.shared.resources.chats_repeats_empty_state_description
import one.meshcore.shared.resources.chats_repeats_empty_state_title
import one.meshcore.shared.resources.chats_repeats_hop_plural
import one.meshcore.shared.resources.chats_repeats_hop_singular
import one.meshcore.shared.resources.chats_repeats_row_accessibility
import one.meshcore.shared.resources.chats_repeats_row_accessibility_value
import one.meshcore.shared.resources.chats_repeats_unknown_repeater
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_snr_badge_unit
import one.meshcore.shared.resources.tools_noise_floor_d_bm
import one.meshcore.shared.resources.tools_rx_log_rssi_label
import one.meshcore.shared.resources.tools_rx_log_snr_label
import one.meshcore.shared.ui.FullScreenOverlay
import one.meshcore.shared.ui.PreferenceHubTopPadding
import one.meshcore.shared.ui.SignalBarMeter
import one.meshcore.shared.ui.barTint
import one.meshcore.shared.ui.filledBarCount
import one.meshcore.shared.ui.formatTemplate
import one.meshcore.shared.ui.icons.AppIcons
import org.jetbrains.compose.resources.stringResource

/**
 * Heard-repeat rows for an outgoing message (iOS RepeatDetailsSheet). Nested destination Up matches
 * Who Reacted; [repeats] null means still loading, empty list is the empty state.
 * [resolveRepeater] names the last-hop hash and reports whether that name is exact or a colliding
 * prefix fallback. A fallback row shows the same "?" the path sheet already uses.
 */
@Composable
fun RepeatDetailsSheet(
    repeats: List<MessageRepeatDto>?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    resolveRepeater: (ByteArray) -> NodeNameResolution = {
        NodeNameResolution(displayName = "", matchKind = NodeNameMatchKind.UNRESOLVED)
    },
) {
    FullScreenOverlay(
        onDismiss = onDismiss,
        title = stringResource(Res.string.chats_message_action_repeat_details),
        modifier = modifier,
    ) { padding ->
        RepeatDetailsBody(
            repeats = repeats,
            resolveRepeater = resolveRepeater,
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(top = PreferenceHubTopPadding, bottom = 24.dp),
        )
    }
}

@Composable
internal fun RepeatDetailsBody(
    repeats: List<MessageRepeatDto>?,
    resolveRepeater: (ByteArray) -> NodeNameResolution,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        when {
            repeats == null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            repeats.isEmpty() -> {
                ChatDetailEmptyPane(
                    title = stringResource(Res.string.chats_repeats_empty_state_title),
                    description = stringResource(Res.string.chats_repeats_empty_state_description),
                    icon = AppIcons.Repeat,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            else -> {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    repeats.forEach { row ->
                        RepeatRow(row, resolveRepeater)
                    }
                }
            }
        }
    }
}

/** The readings a repeat row's signal column draws, top to bottom. Mirrors the iOS `RepeatRowView` column. */
internal enum class RepeatSignalLine { BARS, SNR, RSSI }

/**
 * The signal column of every heard-repeat row. All three readings are always present — a missing SNR or
 * RSSI shows its placeholder rather than dropping the line — because the row exists to tell a responder
 * which repeater heard them and how well.
 */
internal val repeatSignalLines: List<RepeatSignalLine> =
    listOf(RepeatSignalLine.BARS, RepeatSignalLine.SNR, RepeatSignalLine.RSSI)

@Composable
private fun RepeatRow(repeat: MessageRepeatDto, resolveRepeater: (ByteArray) -> NodeNameResolution) {
    val hashHex = repeat.repeaterHashFormatted
    val unknown = stringResource(Res.string.chats_repeats_unknown_repeater)
    val resolution = repeat.repeaterHash?.let(resolveRepeater)
        ?: NodeNameResolution(displayName = unknown, matchKind = NodeNameMatchKind.UNRESOLVED)
    val title = repeatRowTitle(resolution, unknown)
    val snrUnit = stringResource(Res.string.remotenodes_remote_nodes_status_snr_badge_unit)
    val rssiUnit = stringResource(Res.string.tools_noise_floor_d_bm)
    val snrText = repeat.snrFormatted(snrUnit)
    val rssiText = repeat.rssiFormatted(rssiUnit)
    val rowLabel = formatTemplate(stringResource(Res.string.chats_repeats_row_accessibility), listOf(title))
    val rowValue = formatTemplate(
        stringResource(Res.string.chats_repeats_row_accessibility_value),
        listOf(snrQualityLabel(repeat.snrQuality), snrText, rssiText),
    )
    val hops = repeat.hopCount
    val hopLabel = if (hops == 1) {
        stringResource(Res.string.chats_repeats_hop_singular)
    } else {
        formatTemplate(stringResource(Res.string.chats_repeats_hop_plural), listOf(hops))
    }
    ListItem(
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title)
                if (resolution.isFallback) {
                    PossibleMatchMarker(Modifier.padding(start = REPEAT_FALLBACK_MARKER_START_DP.dp))
                }
            }
        },
        supportingContent = {
            Column {
                if (hashHex.isNotEmpty() && hashHex != title) {
                    Text(hashHex)
                }
                Text(hopLabel)
            }
        },
        trailingContent = { RepeatSignalColumn(repeat, snrText = snrText, rssiText = rssiText) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = repeatRowMergesDescendants(resolution.isFallback)) {
                contentDescription = "$rowLabel. $rowValue"
            },
    )
}

@Composable
private fun RepeatSignalColumn(repeat: MessageRepeatDto, snrText: String, rssiText: String) {
    Column(horizontalAlignment = Alignment.End) {
        repeatSignalLines.forEach { line ->
            when (line) {
                RepeatSignalLine.BARS -> SignalBarMeter(
                    filledBars = repeat.snrQuality.filledBarCount,
                    tint = repeat.snrQuality.barTint(),
                )

                RepeatSignalLine.SNR -> SignalReading(
                    label = stringResource(Res.string.tools_rx_log_snr_label),
                    value = snrText,
                )

                RepeatSignalLine.RSSI -> SignalReading(
                    label = stringResource(Res.string.tools_rx_log_rssi_label),
                    value = rssiText,
                )
            }
        }
    }
}

@Composable
private fun SignalReading(label: String, value: String) {
    Text(
        text = "$label $value",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Space between the repeater name and the fallback "?" — iOS RepeatRowView HStack spacing. */
private const val REPEAT_FALLBACK_MARKER_START_DP = 6

/** iOS `.contain` when a fallback “?” must stay its own rotor stop; `.combine` otherwise. */
internal fun repeatRowMergesDescendants(isFallback: Boolean): Boolean = !isFallback
