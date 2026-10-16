package one.meshcore.shared.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import one.meshcore.shared.ui.icons.AppIcons

/**
 * Horizontal hop capsules with auto-mirrored chevrons. Use this instead of joining hops with a
 * Unicode arrow so path chrome matches Trace Path on every surface.
 */
@Composable
fun PathHopChipRow(hops: List<String>, modifier: Modifier = Modifier) {
    if (hops.isEmpty()) return
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PATH_CHIP_GAP),
    ) {
        hops.forEachIndexed { index, label ->
            if (index > 0) {
                Icon(
                    imageVector = AppIcons.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(PATH_CHIP_ARROW_SIZE),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = PATH_CHIP_H_PADDING, vertical = PATH_CHIP_V_PADDING),
            )
        }
    }
}

internal fun pathHopChipLabels(pathDisplay: String): List<String> =
    pathDisplay.split(PATH_HOP_ARROW).map { it.trim() }.filter { it.isNotEmpty() }

internal fun truncatedPathHopLabels(labels: List<String>, threshold: Int, ends: Int): List<String> {
    if (labels.size <= threshold) return labels
    return labels.take(ends) + "…" + labels.takeLast(ends)
}

private val PATH_CHIP_GAP = 4.dp
private val PATH_CHIP_H_PADDING = 8.dp
private val PATH_CHIP_V_PADDING = 4.dp
private val PATH_CHIP_ARROW_SIZE = 12.dp
private const val PATH_HOP_ARROW = '\u2192'
