package one.meshcore.shared.channel.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import one.meshcore.shared.model.NotificationLevel

/** Notification-level radio row. Callers own what happens on select. */
@Composable
internal fun NotificationLevelRow(
    level: NotificationLevel,
    selected: Boolean,
    levelHint: String,
    onClick: () -> Unit,
) {
    val spoken = level.notificationAccessibilityDescription()
    ListItem(
        headlineContent = { Text(level.channelLabel()) },
        leadingContent = {
            RadioButton(selected = selected, onClick = null)
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = spoken
                stateDescription = levelHint
            }
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick,
            ),
    )
}
