package one.meshcore.shared.remotenode.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_message_action_collapsed
import one.meshcore.shared.resources.chats_message_action_expanded
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_refresh
import one.meshcore.shared.ui.icons.AppIconButton
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.radioConnectionHint
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun RemoteSectionHeader(
    title: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    isLoading: Boolean,
    onReload: (() -> Unit)?,
    reloadAccessibilityLabel: String? = null,
    reloadEnabled: Boolean = true,
    isRadioReady: Boolean = true,
) {
    val description = stringResource(
        if (expanded) Res.string.chats_message_action_expanded else Res.string.chats_message_action_collapsed,
    )
    ListItem(
        headlineContent = { Text(title) },
        trailingContent = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(HEADER_ACTION_GAP),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (expanded) {
                    when {
                        isLoading -> CircularProgressIndicator(
                            modifier = Modifier.size(SPINNER_SIZE),
                            strokeWidth = SPINNER_STROKE,
                        )
                        onReload != null -> AppIconButton(
                            imageVector = AppIcons.Refresh,
                            contentDescription = reloadAccessibilityLabel
                                ?: stringResource(Res.string.remotenodes_remote_nodes_status_refresh),
                            onClick = onReload,
                            enabled = isRadioReady && reloadEnabled,
                            modifier = Modifier.radioConnectionHint(isRadioReady),
                        )
                    }
                }
                Icon(
                    imageVector = if (expanded) AppIcons.KeyboardArrowUp else AppIcons.ExpandMore,
                    contentDescription = null,
                )
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .semantics { stateDescription = description }
            .clickable(role = Role.Button) { onExpandedChange(!expanded) },
    )
}

private val HEADER_ACTION_GAP = 8.dp
private val SPINNER_SIZE = 20.dp
private val SPINNER_STROKE = 2.dp
