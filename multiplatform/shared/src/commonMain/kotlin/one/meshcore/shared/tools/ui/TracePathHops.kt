package one.meshcore.shared.tools.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.android_path_edit_move_hop_down
import one.meshcore.shared.resources.android_path_edit_move_hop_up
import one.meshcore.shared.resources.android_path_edit_remove_hop
import one.meshcore.shared.resources.contacts_path_edit_empty_title
import one.meshcore.shared.resources.contacts_trace_list_hop_label
import one.meshcore.shared.resources.contacts_trace_list_round_trip_path
import one.meshcore.shared.ui.EMPTY_STATE_ICON_SIZE
import one.meshcore.shared.ui.PreferenceHubCardPadding
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.formatTemplate
import one.meshcore.shared.ui.icons.AppIconButton
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.tint
import org.jetbrains.compose.resources.stringResource
import one.meshcore.shared.pathedit.PathHop as EditableHop

/**
 * The ordered outbound hops with reorder and remove controls, mirroring the iOS `TracePathListView`
 * outbound-path section (drag reorder + swipe delete; Material uses explicit per-row buttons).
 */
@Composable
internal fun TraceHopListSection(
    hops: List<EditableHop>,
    enabled: Boolean,
    emptyPathHint: String,
    onMoveUp: (Int) -> Unit,
    onMoveDown: (Int) -> Unit,
    onRemove: (Int) -> Unit,
) {
    PreferenceHubSection(
        title = stringResource(Res.string.contacts_trace_list_round_trip_path),
        applyHorizontalPadding = false,
    ) {
        if (hops.isEmpty()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(PreferenceHubCardPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = TRACE_PATH_EMPTY_ICON,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(EMPTY_STATE_ICON_SIZE),
                )
                Text(
                    text = stringResource(Res.string.contacts_path_edit_empty_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = emptyPathHint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            hops.forEachIndexed { index, hop ->
                TraceHopRow(
                    hop = hop,
                    index = index,
                    total = hops.size,
                    enabled = enabled,
                    onMoveUp = { onMoveUp(index) },
                    onMoveDown = { onMoveDown(index) },
                    onRemove = { onRemove(index) },
                )
            }
        }
    }
}

@Composable
private fun TraceHopRow(
    hop: EditableHop,
    index: Int,
    total: Int,
    enabled: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    val spokenHop = formatTemplate(
        stringResource(Res.string.contacts_trace_list_hop_label),
        listOf(index + 1, hop.resolvedName ?: hop.hashHex),
    )
    ListItem(
        leadingContent = {
            Text("${index + 1}.", modifier = Modifier.clearAndSetSemantics { })
        },
        headlineContent = {
            Column(
                modifier = Modifier.semantics(mergeDescendants = true) {
                    contentDescription = spokenHop
                },
            ) {
                val name = hop.resolvedName
                if (name != null) {
                    Text(name)
                    Text(
                        hop.hashHex,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(hop.hashHex, fontFamily = FontFamily.Monospace)
                }
            }
        },
        trailingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppIconButton(
                    imageVector = AppIcons.KeyboardArrowUp,
                    contentDescription = stringResource(Res.string.android_path_edit_move_hop_up),
                    onClick = onMoveUp,
                    enabled = enabled && index > 0,
                )
                AppIconButton(
                    imageVector = AppIcons.KeyboardArrowDown,
                    contentDescription = stringResource(Res.string.android_path_edit_move_hop_down),
                    onClick = onMoveDown,
                    enabled = enabled && index < total - 1,
                )
                AppIconButton(
                    imageVector = AppIcons.Close,
                    contentDescription = stringResource(Res.string.android_path_edit_remove_hop),
                    onClick = onRemove,
                    enabled = enabled,
                )
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Empty hops is a missing path, not a disconnected radio. */
internal val TRACE_PATH_EMPTY_ICON get() = AppIcons.Route
