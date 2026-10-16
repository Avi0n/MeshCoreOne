package one.meshcore.shared.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import one.meshcore.shared.ui.icons.AppIcons

private val NODE_CONFIG_CAPTION_ICON_SIZE = 18.dp
private val NODE_CONFIG_CAPTION_ICON_GAP = 8.dp
private val NODE_CONFIG_ERROR_TOP_PADDING = 8.dp

/** Icon and body-small caption. The row fills the width the caller has left. */
@Composable
internal fun NodeConfigCaption(text: String, icon: ImageVector, color: Color, modifier: Modifier = Modifier) {
    NodeConfigCaptionRow(text, icon, color, modifier.fillMaxWidth())
}

/** Inline fault caption matching iOS form error Labels (`exclamationmark.triangle`). */
@Composable
internal fun NodeConfigErrorLabel(
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = AppIcons.Warning,
) {
    NodeConfigCaptionRow(
        text = message,
        icon = icon,
        color = MaterialTheme.colorScheme.error,
        modifier = modifier.fillMaxWidth().padding(top = NODE_CONFIG_ERROR_TOP_PADDING),
    )
}

@Composable
private fun NodeConfigCaptionRow(text: String, icon: ImageVector, color: Color, modifier: Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NODE_CONFIG_CAPTION_ICON_GAP),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(NODE_CONFIG_CAPTION_ICON_SIZE),
            tint = color,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = color,
        )
    }
}
