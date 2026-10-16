package one.meshcore.shared.remotenode.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import one.meshcore.shared.ui.icons.AppIcons

private val RADIO_RESTART_WARNING_CORNER = 8.dp
private val RADIO_RESTART_WARNING_PADDING = 12.dp
private val RADIO_RESTART_WARNING_ICON_SIZE = 20.dp
private val RADIO_RESTART_WARNING_ICON_SPACING = 8.dp
private const val RADIO_RESTART_WARNING_BACKGROUND_ALPHA = 0.1f

/** Warns that applying radio params restarts the remote radio, matching iOS's yellow triangle banner. */
@Composable
internal fun RadioRestartWarning(text: String, modifier: Modifier = Modifier) {
    val tint = MaterialTheme.colorScheme.tertiary
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(RADIO_RESTART_WARNING_CORNER))
            .background(tint.copy(alpha = RADIO_RESTART_WARNING_BACKGROUND_ALPHA))
            .padding(RADIO_RESTART_WARNING_PADDING),
        horizontalArrangement = Arrangement.spacedBy(RADIO_RESTART_WARNING_ICON_SPACING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = AppIcons.Warning,
            contentDescription = null,
            modifier = Modifier.size(RADIO_RESTART_WARNING_ICON_SIZE),
            tint = tint,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
