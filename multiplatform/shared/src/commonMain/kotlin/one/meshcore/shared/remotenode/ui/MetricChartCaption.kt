package one.meshcore.shared.remotenode.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.remotenodes_remote_nodes_history_check_back
import org.jetbrains.compose.resources.stringResource
import kotlin.math.round

private const val VALUE_SCALE = 100.0
private const val VALUE_DECIMALS = 2
private const val EMPTY_STATE_HEIGHT_DP = 80

@Composable
internal fun ChartHeader(title: String, lastText: String) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = if (lastText.isNotEmpty()) {
            {
                Text(
                    text = lastText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            null
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun ChartLegend(primaryLabel: String?, secondaryLabel: String?, lineColor: Color, secondaryLineColor: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (primaryLabel != null) {
            Text(primaryLabel, style = MaterialTheme.typography.labelSmall, color = lineColor)
        }
        if (secondaryLabel != null) {
            Text(secondaryLabel, style = MaterialTheme.typography.labelSmall, color = secondaryLineColor)
        }
    }
}

internal fun lastValueCaption(
    primary: Double?,
    secondary: Double?,
    unit: String,
    primaryLabel: String?,
    secondaryLabel: String?,
): String = when {
    primary != null && secondary != null && primaryLabel != null && secondaryLabel != null ->
        "$primaryLabel ${formatValue(primary)}, $secondaryLabel ${formatValue(secondary)}" +
            unitSuffix(unit)
    primary != null -> formatValue(primary) + unitSuffix(unit)
    secondary != null -> formatValue(secondary) + unitSuffix(unit)
    else -> ""
}

internal fun formatValue(value: Double): String {
    val rounded = round(value * VALUE_SCALE) / VALUE_SCALE
    return if (rounded == rounded.toLong().toDouble()) {
        rounded.toLong().toString()
    } else {
        val s = rounded.toString()
        val dot = s.indexOf('.')
        if (dot < 0) s else s.take(minOf(s.length, dot + 1 + VALUE_DECIMALS)).trimEnd('0').trimEnd('.')
    }
}

internal fun unitSuffix(unit: String): String = if (unit.isEmpty()) "" else " $unit"

@Composable
internal fun MetricChartEmptyState(value: Double?, unit: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = EMPTY_STATE_HEIGHT_DP.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (value != null) {
            Text(
                text = formatValue(value) + unitSuffix(unit),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Text(
            text = stringResource(Res.string.remotenodes_remote_nodes_history_check_back),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
