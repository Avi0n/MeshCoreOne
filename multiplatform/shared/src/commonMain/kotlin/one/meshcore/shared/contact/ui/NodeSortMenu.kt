package one.meshcore.shared.contact.ui

import androidx.compose.runtime.Composable
import one.meshcore.shared.contact.NodeSortOrder
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_list_sort
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.menu.McMenuItem
import one.meshcore.shared.ui.menu.McMenuSectionHeader
import org.jetbrains.compose.resources.stringResource

/** Sort rows shared by the Nodes overflow menu and the discovery sort menu. */
@Composable
internal fun NodeSortMenuItems(
    sortOrder: NodeSortOrder,
    onSortSelect: (NodeSortOrder) -> Unit,
    onDismiss: () -> Unit,
) {
    McMenuSectionHeader(text = stringResource(Res.string.contacts_list_sort))
    NodeSortOrder.entries.forEach { order ->
        McMenuItem(
            text = order.label(),
            leadingIcon = AppIcons.Sort,
            selected = order == sortOrder,
            onClick = {
                onDismiss()
                onSortSelect(order)
            },
        )
    }
}
