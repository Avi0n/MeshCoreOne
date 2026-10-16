package one.meshcore.shared.remotenode.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.remotenodes_remote_nodes_history_a11y_decreased
import one.meshcore.shared.resources.remotenodes_remote_nodes_history_a11y_degraded
import one.meshcore.shared.resources.remotenodes_remote_nodes_history_a11y_delta_description
import one.meshcore.shared.resources.remotenodes_remote_nodes_history_a11y_improved
import one.meshcore.shared.resources.remotenodes_remote_nodes_history_a11y_increased
import one.meshcore.shared.ui.formattedStringResource
import one.meshcore.shared.ui.icons.AppIcons
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.math.roundToInt

/** Improvement vs degradation for a status delta. Paint uses theme primary/tertiary. */
internal enum class StatusDeltaTint { MUTED, GREEN, ORANGE }

internal fun statusDeltaTint(delta: Double, higherIsBetter: Boolean): StatusDeltaTint {
    if (abs(delta) < STATUS_DELTA_MUTED_ABS) return StatusDeltaTint.MUTED
    val isImprovement = if (higherIsBetter) delta > 0 else delta < 0
    return if (isImprovement) StatusDeltaTint.GREEN else StatusDeltaTint.ORANGE
}

@Composable
internal fun StatusDeltaView(delta: Double, higherIsBetter: Boolean, unit: String, fractionDigits: Int) {
    if (!NodeStatusDeltas.isVisible(delta)) return
    val isImprovement = if (higherIsBetter) delta > 0 else delta < 0
    val color = when (statusDeltaTint(delta, higherIsBetter)) {
        StatusDeltaTint.MUTED -> MaterialTheme.colorScheme.onSurfaceVariant
        StatusDeltaTint.GREEN -> MaterialTheme.colorScheme.primary
        StatusDeltaTint.ORANGE -> MaterialTheme.colorScheme.tertiary
    }
    val magnitude = when (fractionDigits) {
        0 -> abs(delta).roundToInt().toString()
        1 -> NodeStatusFormat.oneDecimal(abs(delta))
        else -> NodeStatusFormat.voltage((abs(delta) * 1000).roundToInt())
    }
    // The glyph and the colour are the whole signal here, and a screen reader gets neither: "up arrow
    // 3.5dB" says nothing about whether that is good news. Spoken as "improved, increased by 3.5dB".
    val spoken = formattedStringResource(
        Res.string.remotenodes_remote_nodes_history_a11y_delta_description,
        stringResource(
            if (isImprovement) {
                Res.string.remotenodes_remote_nodes_history_a11y_improved
            } else {
                Res.string.remotenodes_remote_nodes_history_a11y_degraded
            },
        ),
        stringResource(
            if (delta > 0) {
                Res.string.remotenodes_remote_nodes_history_a11y_increased
            } else {
                Res.string.remotenodes_remote_nodes_history_a11y_decreased
            },
        ),
        magnitude,
        unit,
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics { contentDescription = spoken },
    ) {
        Icon(
            imageVector = if (delta > 0) AppIcons.KeyboardArrowUp else AppIcons.KeyboardArrowDown,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(STATUS_DELTA_TREND_ICON_SIZE),
        )
        Text(
            text = "$magnitude$unit",
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

private const val STATUS_DELTA_MUTED_ABS = 0.01

private val STATUS_DELTA_TREND_ICON_SIZE = 16.dp
