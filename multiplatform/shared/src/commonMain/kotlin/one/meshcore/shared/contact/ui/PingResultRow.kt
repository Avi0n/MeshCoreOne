package one.meshcore.shared.contact.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import one.meshcore.shared.contact.ContactPingResult
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_detail_ping_failure_label
import one.meshcore.shared.resources.contacts_detail_ping_no_response
import one.meshcore.shared.resources.contacts_detail_ping_success_label
import one.meshcore.shared.resources.remotenodes_remote_nodes_history_snr
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_snr_badge_unit
import one.meshcore.shared.ui.durationMsLabel
import one.meshcore.shared.ui.formattedStringResource
import one.meshcore.shared.ui.icons.AppIcons
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.math.round

/**
 * Compact ping outcome with a status icon. Visual text is latency plus SNR with Material direction
 * arrows and spaced facts (no middot); TalkBack uses the sentence-form success/failure labels.
 */
@Composable
internal fun PingResultRow(result: ContactPingResult, modifier: Modifier = Modifier) {
    when (result) {
        is ContactPingResult.Success -> PingSuccessLine(
            latencyMs = result.latencyMs,
            snrThere = result.snrThere,
            snrBack = result.snrBack,
            spoken = formattedStringResource(
                Res.string.contacts_detail_ping_success_label,
                result.latencyMs,
                result.snrThere.toInt(),
                result.snrBack.toInt(),
            ),
            modifier = modifier,
        )
        is ContactPingResult.Failure -> PingResultLine(
            text = result.message,
            spoken = formattedStringResource(Res.string.contacts_detail_ping_failure_label, result.message),
            icon = AppIcons.Error,
            iconTint = MaterialTheme.colorScheme.error,
            textColor = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
        ContactPingResult.NoResponse -> {
            val message = stringResource(Res.string.contacts_detail_ping_no_response)
            PingResultLine(
                text = message,
                spoken = formattedStringResource(Res.string.contacts_detail_ping_failure_label, message),
                icon = AppIcons.Error,
                iconTint = MaterialTheme.colorScheme.error,
                textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun PingSuccessLine(
    latencyMs: Int,
    snrThere: Double,
    snrBack: Double,
    spoken: String,
    modifier: Modifier = Modifier,
) {
    val parts = pingSuccessVisibleParts(latencyMs, snrThere, snrBack)
    val lineColor = MaterialTheme.colorScheme.onSurface
    val lineStyle = MaterialTheme.typography.bodyMedium
    Row(
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PING_RESULT_ICON_GAP),
    ) {
        Icon(
            imageVector = AppIcons.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(PING_RESULT_ICON_SIZE),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PING_SNR_CLUSTER_GAP),
        ) {
            Text(text = durationMsLabel(parts.latencyMs), style = lineStyle, color = lineColor)
            Text(
                text = stringResource(Res.string.remotenodes_remote_nodes_history_snr),
                style = lineStyle,
                color = lineColor,
            )
            Icon(
                imageVector = AppIcons.KeyboardArrowUp,
                contentDescription = null,
                tint = lineColor,
                modifier = Modifier.size(PING_SNR_ARROW_SIZE),
            )
            Text(
                text = pingSnrReadingVisibleText(
                    parts.snrThere,
                    stringResource(Res.string.remotenodes_remote_nodes_status_snr_badge_unit),
                ),
                style = lineStyle,
                color = lineColor,
            )
            Icon(
                imageVector = AppIcons.KeyboardArrowDown,
                contentDescription = null,
                tint = lineColor,
                modifier = Modifier.size(PING_SNR_ARROW_SIZE),
            )
            Text(
                text = pingSnrReadingVisibleText(
                    parts.snrBack,
                    stringResource(Res.string.remotenodes_remote_nodes_status_snr_badge_unit),
                ),
                style = lineStyle,
                color = lineColor,
            )
        }
    }
}

@Composable
private fun PingResultLine(
    text: String,
    spoken: String,
    icon: ImageVector,
    iconTint: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PING_RESULT_ICON_GAP),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(PING_RESULT_ICON_SIZE),
        )
        Text(text = text, style = MaterialTheme.typography.bodyMedium, color = textColor)
    }
}

/** Latency plus two-decimal SNR values for the success line. Direction is Material arrows, not glyphs. */
internal data class PingSuccessVisibleParts(val latencyMs: Int, val snrThere: String, val snrBack: String)

internal fun pingSuccessVisibleParts(latencyMs: Int, snrThere: Double, snrBack: Double): PingSuccessVisibleParts =
    PingSuccessVisibleParts(
        latencyMs = latencyMs,
        snrThere = formatTwoDecimal(snrThere),
        snrBack = formatTwoDecimal(snrBack),
    )

internal fun pingSnrReadingVisibleText(snrFormatted: String, unit: String): String = "$snrFormatted $unit"

private fun formatTwoDecimal(value: Double): String {
    val hundredths = round(value * TWO_DECIMAL_SCALE).toInt()
    val magnitude = abs(hundredths)
    val sign = if (hundredths < 0) "-" else ""
    val whole = magnitude / TWO_DECIMAL_SCALE_INT
    val frac = magnitude % TWO_DECIMAL_SCALE_INT
    return "$sign$whole.${frac.toString().padStart(2, '0')}"
}

private val PING_RESULT_ICON_SIZE = 18.dp
private val PING_SNR_ARROW_SIZE = 16.dp
private val PING_RESULT_ICON_GAP = 8.dp
private val PING_SNR_CLUSTER_GAP = 2.dp
private const val TWO_DECIMAL_SCALE = 100.0
private const val TWO_DECIMAL_SCALE_INT = 100
