package one.meshcore.shared.chat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Tonal card wrapping read-only message metadata so it does not share ListItem chrome with the
 * tappable actions above it.
 */
@Composable
fun MessageDetailsCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = MaterialTheme.shapes.medium,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = DETAILS_CARD_H_PADDING, vertical = DETAILS_CARD_V_PADDING),
    ) {
        Column(
            modifier = Modifier.padding(DETAILS_CARD_INNER_PADDING),
            verticalArrangement = Arrangement.spacedBy(DETAILS_CARD_ROW_GAP),
            content = content,
        )
    }
}

/**
 * A single read-only metadata row in a message actions sheet: an optional leading icon plus a label.
 * Shared across the chat/channel and room "Details" sections (iOS `ActionInfoRow`). Compact supporting
 * text, not a tappable [androidx.compose.material3.ListItem].
 */
@Composable
fun ActionInfoRow(text: String, icon: ImageVector? = null, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = text },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(ACTION_INFO_ICON_DP.dp),
            )
            Spacer(Modifier.width(ACTION_INFO_ICON_GAP))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val DETAILS_CARD_H_PADDING = 16.dp
private val DETAILS_CARD_V_PADDING = 8.dp
private val DETAILS_CARD_INNER_PADDING = 16.dp
private val DETAILS_CARD_ROW_GAP = 8.dp
internal const val ACTION_INFO_ICON_DP = 18
private val ACTION_INFO_ICON_GAP = 12.dp

/** Medium calendar date + short clock for a "Details" row (iOS abbreviated date + standard time). */
internal fun formatMessageDetailDateTime(epochSeconds: Double, formatter: TimestampFormatter): String {
    val epoch = epochSeconds.toTimestampEpochSecondOrNull() ?: return formatter.unavailable
    val date = formatter.date(epoch) ?: return formatter.unavailable
    val clock = formatter.clock(epoch) ?: return formatter.unavailable
    return "$date, $clock"
}
