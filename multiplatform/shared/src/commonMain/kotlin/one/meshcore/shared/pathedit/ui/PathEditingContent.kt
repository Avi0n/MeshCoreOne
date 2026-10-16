package one.meshcore.shared.pathedit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import one.meshcore.shared.pathedit.PathHop
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.android_overflow_menu
import one.meshcore.shared.resources.android_path_edit_move_hop_down
import one.meshcore.shared.resources.android_path_edit_move_hop_up
import one.meshcore.shared.resources.android_path_edit_remove_hop
import one.meshcore.shared.resources.contacts_common_cancel
import one.meshcore.shared.resources.contacts_path_edit_add_hop
import one.meshcore.shared.resources.contacts_path_edit_empty_description
import one.meshcore.shared.resources.contacts_path_edit_empty_title
import one.meshcore.shared.resources.contacts_path_edit_hop_with_hex
import one.meshcore.shared.resources.contacts_path_edit_hop_with_name
import one.meshcore.shared.resources.contacts_path_edit_max_hops_footer
import one.meshcore.shared.resources.contacts_path_edit_max_hops_reached
import one.meshcore.shared.resources.contacts_path_edit_use_direct_routing
import one.meshcore.shared.resources.contacts_path_edit_use_flood_routing
import one.meshcore.shared.ui.ButtonIconLabel
import one.meshcore.shared.ui.ConfirmAlertDialog
import one.meshcore.shared.ui.EMPTY_STATE_ICON_SIZE
import one.meshcore.shared.ui.PreferenceHubFooter
import one.meshcore.shared.ui.formattedStringResource
import one.meshcore.shared.ui.icons.AppIconButton
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.menu.McDropdownMenu
import one.meshcore.shared.ui.menu.McMenuItem
import one.meshcore.shared.ui.menu.McMenuRole
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun PathEditingEmptyState(
    contactName: String,
    onAddHop: () -> Unit,
    onDirect: () -> Unit,
    onFlood: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(PathEditMetrics.rowInset),
        verticalArrangement = Arrangement.spacedBy(PathEditMetrics.rowVerticalPadding, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = PATH_EDIT_EMPTY_ICON,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(EMPTY_STATE_ICON_SIZE),
        )
        Text(stringResource(Res.string.contacts_path_edit_empty_title), style = MaterialTheme.typography.titleMedium)
        Text(
            text = formattedStringResource(Res.string.contacts_path_edit_empty_description, contactName),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onAddHop, modifier = Modifier.fillMaxWidth()) {
            ButtonIconLabel(stringResource(Res.string.contacts_path_edit_add_hop), PATH_EDIT_ADD_HOP_ICON)
        }
        OutlinedButton(onClick = onDirect, modifier = Modifier.fillMaxWidth()) {
            ButtonIconLabel(stringResource(Res.string.contacts_path_edit_use_direct_routing), PATH_EDIT_DIRECT_ICON)
        }
        OutlinedButton(onClick = onFlood, modifier = Modifier.fillMaxWidth()) {
            ButtonIconLabel(stringResource(Res.string.contacts_path_edit_use_flood_routing), PATH_EDIT_FLOOD_ICON)
        }
    }
}

@Composable
internal fun PathEditingAddHopCta(isPathFull: Boolean, maxHopCount: Int, onAddHop: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(PathEditMetrics.rowInset)) {
        Button(onClick = onAddHop, enabled = !isPathFull, modifier = Modifier.fillMaxWidth()) {
            ButtonIconLabel(
                title = if (isPathFull) {
                    stringResource(Res.string.contacts_path_edit_max_hops_reached)
                } else {
                    stringResource(Res.string.contacts_path_edit_add_hop)
                },
                icon = if (isPathFull) PATH_EDIT_PATH_FULL_ICON else PATH_EDIT_ADD_HOP_ICON,
            )
        }
        if (isPathFull) {
            PreferenceHubFooter(
                formattedStringResource(Res.string.contacts_path_edit_max_hops_footer, maxHopCount),
                applyHorizontalPadding = false,
            )
        }
    }
}

@Composable
internal fun PathEditingHopRow(
    hop: PathHop,
    index: Int,
    total: Int,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    val name = hop.resolvedName
    val spoken = if (name != null) {
        formattedStringResource(Res.string.contacts_path_edit_hop_with_name, index + 1, total, name)
    } else {
        formattedStringResource(Res.string.contacts_path_edit_hop_with_hex, index + 1, total, hop.hashHex)
    }
    ListItem(
        headlineContent = {
            Column(
                modifier = Modifier.semantics(mergeDescendants = true) {
                    contentDescription = spoken
                },
            ) {
                if (name != null) {
                    Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        hop.hashHex,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Text(
                        hop.hashHex,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        trailingContent = {
            var menuOpen by remember { mutableStateOf(false) }
            Box {
                AppIconButton(
                    imageVector = AppIcons.MoreVert,
                    contentDescription = stringResource(Res.string.android_overflow_menu),
                    onClick = { menuOpen = true },
                )
                McDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    McMenuItem(
                        text = stringResource(Res.string.android_path_edit_move_hop_up),
                        leadingIcon = AppIcons.KeyboardArrowUp,
                        enabled = index > 0,
                        onClick = {
                            menuOpen = false
                            onMoveUp()
                        },
                    )
                    McMenuItem(
                        text = stringResource(Res.string.android_path_edit_move_hop_down),
                        leadingIcon = AppIcons.KeyboardArrowDown,
                        enabled = index < total - 1,
                        onClick = {
                            menuOpen = false
                            onMoveDown()
                        },
                    )
                    McMenuItem(
                        text = stringResource(Res.string.android_path_edit_remove_hop),
                        leadingIcon = AppIcons.Delete,
                        role = McMenuRole.Destructive,
                        onClick = {
                            menuOpen = false
                            onRemove()
                        },
                    )
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth().heightIn(min = PathEditMetrics.tapTarget),
    )
}

@Composable
internal fun PathEditingRoutingConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ConfirmAlertDialog(
        title = title,
        message = message,
        confirmLabel = confirmLabel,
        dismissLabel = stringResource(Res.string.contacts_common_cancel),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/** iOS `PathEditingSheet` empty: `antenna.radiowaves.left.and.right.slash`. */
internal val PATH_EDIT_EMPTY_ICON get() = AppIcons.CellTowerOff
