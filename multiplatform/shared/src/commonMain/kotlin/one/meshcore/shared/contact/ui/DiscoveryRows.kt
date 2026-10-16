package one.meshcore.shared.contact.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import one.meshcore.shared.avatar.AvatarCategory
import one.meshcore.shared.contact.DiscoverSegment
import one.meshcore.shared.contact.DiscoveredNodeRecord
import one.meshcore.shared.contact.NodeSortOrder
import one.meshcore.shared.contact.UserLocation
import one.meshcore.shared.contact.discoveryHopLabel
import one.meshcore.shared.contact.distanceMeters
import one.meshcore.shared.contact.formatDiscoveryPath
import one.meshcore.shared.contact.hasLocation
import one.meshcore.shared.contact.pathNodesHex
import one.meshcore.shared.contact.relativeAgeLabel
import one.meshcore.shared.contact.routeKind
import one.meshcore.shared.contact.showsDiscoveryPathIcon
import one.meshcore.shared.map.ui.rememberSnrBadgeFormatter
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.toUppercaseHex
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.android_overflow_menu
import one.meshcore.shared.resources.contacts_discovery_clear
import one.meshcore.shared.resources.contacts_discovery_menu
import one.meshcore.shared.resources.contacts_discovery_remove
import one.meshcore.shared.resources.contacts_discovery_segment_all
import one.meshcore.shared.resources.contacts_discovery_segment_contacts
import one.meshcore.shared.resources.contacts_discovery_segment_picker_label
import one.meshcore.shared.resources.contacts_discovery_segment_repeaters
import one.meshcore.shared.resources.contacts_discovery_segment_rooms
import one.meshcore.shared.resources.contacts_discovery_sort_menu
import one.meshcore.shared.resources.contacts_discovery_sort_menu_hint
import one.meshcore.shared.resources.contacts_row_away
import one.meshcore.shared.resources.tools_node_discovery_unknown_node
import one.meshcore.shared.ui.FilterChipRowHorizontalPadding
import one.meshcore.shared.ui.IconLeadEmptyColumn
import one.meshcore.shared.ui.ScrollingFilterChips
import one.meshcore.shared.ui.filterBarVisible
import one.meshcore.shared.ui.formattedStringResource
import one.meshcore.shared.ui.icons.AppIconButton
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.menu.McActionSheet
import one.meshcore.shared.ui.menu.McActionSheetItem
import one.meshcore.shared.ui.menu.McDropdownMenu
import one.meshcore.shared.ui.menu.McMenuItem
import one.meshcore.shared.ui.menu.McMenuRole
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun SegmentRow(selected: DiscoverSegment, isSearching: Boolean, onSelect: (DiscoverSegment) -> Unit) {
    if (!filterBarVisible(isSearching)) return
    val pickerLabel = stringResource(Res.string.contacts_discovery_segment_picker_label)
    ScrollingFilterChips(
        entries = DiscoverSegment.entries,
        selected = selected,
        spokenLabel = pickerLabel,
        onSelect = onSelect,
        contentPadding = PaddingValues(
            horizontal = FilterChipRowHorizontalPadding,
            vertical = DISCOVERY_SEGMENT_VERTICAL_PADDING,
        ),
    ) { segmentLabel(it) }
}

@Composable
private fun segmentLabel(segment: DiscoverSegment): String = when (segment) {
    DiscoverSegment.ALL -> stringResource(Res.string.contacts_discovery_segment_all)
    DiscoverSegment.CONTACTS -> stringResource(Res.string.contacts_discovery_segment_contacts)
    DiscoverSegment.REPEATERS -> stringResource(Res.string.contacts_discovery_segment_repeaters)
    DiscoverSegment.ROOMS -> stringResource(Res.string.contacts_discovery_segment_rooms)
}

@Composable
internal fun DiscoverySortMenu(sortOrder: NodeSortOrder, onSortSelect: (NodeSortOrder) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = stringResource(Res.string.contacts_discovery_sort_menu)
    Box {
        val sortHint = stringResource(Res.string.contacts_discovery_sort_menu_hint)
        AppIconButton(
            imageVector = AppIcons.Sort,
            contentDescription = label,
            onClick = { expanded = true },
            modifier = Modifier.semantics { stateDescription = sortHint },
        )
        McDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            NodeSortMenuItems(
                sortOrder = sortOrder,
                onSortSelect = onSortSelect,
                onDismiss = { expanded = false },
            )
        }
    }
}

@Composable
internal fun DiscoveryMoreMenu(isEmpty: Boolean, onClear: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = stringResource(discoveryMoreMenuLabelRes())
    Box {
        AppIconButton(
            imageVector = DISCOVERY_MORE_MENU_ICON,
            contentDescription = label,
            onClick = { expanded = true },
        )
        McDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            McMenuItem(
                text = stringResource(Res.string.contacts_discovery_clear),
                leadingIcon = AppIcons.Delete,
                role = McMenuRole.Destructive,
                enabled = !isEmpty,
                onClick = {
                    expanded = false
                    onClear()
                },
            )
        }
    }
}

/** iOS `DiscoveryMoreMenu` overflow label. */
internal fun discoveryMoreMenuLabelRes() = Res.string.contacts_discovery_menu

/** Material analog of iOS `ellipsis.circle`. */
internal val DISCOVERY_MORE_MENU_ICON get() = AppIcons.MoreVert

@Composable
internal fun DiscoveryRow(
    node: DiscoveredNodeRecord,
    isAdded: Boolean,
    isAdding: Boolean,
    nowSeconds: Long,
    userLocation: UserLocation?,
    onAdd: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box(modifier) {
        ListItem(
            modifier = Modifier.fillMaxWidth(),
            leadingContent = { DiscoveryAvatar(node) },
            headlineContent = {
                Text(
                    discoveryHeadlineText(
                        name = node.name,
                        unknownLabel = stringResource(Res.string.tools_node_discovery_unknown_node),
                        publicKey = node.publicKey,
                    ),
                )
            },
            supportingContent = {
                DiscoveryRowSecondaryLines(node, userLocation, nowSeconds)
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DiscoveryAddTrailing(isAdded = isAdded, isAdding = isAdding, onAdd = onAdd)
                    AppIconButton(
                        imageVector = DISCOVERY_MORE_MENU_ICON,
                        contentDescription = stringResource(Res.string.android_overflow_menu),
                        onClick = { menuOpen = true },
                    )
                }
            },
        )

        if (menuOpen) {
            McActionSheet(onDismiss = { menuOpen = false }) {
                McActionSheetItem(
                    text = stringResource(Res.string.contacts_discovery_remove),
                    leadingIcon = AppIcons.Delete,
                    role = McMenuRole.Destructive,
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                )
            }
        }
    }
}

/** The row's leading identity mark, matching the iOS `DiscoveryNodeRow.avatarView` branches. */
@Composable
private fun DiscoveryAvatar(node: DiscoveredNodeRecord) {
    when (node.contactType) {
        ContactType.CHAT -> ContactAvatar(node.name, CONTACT_ROW_AVATAR_SIZE)
        ContactType.REPEATER -> NodeAvatar(AvatarCategory.REPEATER, CONTACT_ROW_AVATAR_SIZE)
        ContactType.ROOM -> NodeAvatar(AvatarCategory.ROOM, CONTACT_ROW_AVATAR_SIZE)
    }
}

@Composable
private fun DiscoveryRowSecondaryLines(node: DiscoveredNodeRecord, userLocation: UserLocation?, nowSeconds: Long) {
    Column {
        // The full key, middle-truncated: Discover is where an unknown node is identified before the
        // irreversible Add, and two nodes can advertise the same name.
        Text(
            text = node.publicKey.toUppercaseHex(),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
        )
        Text(
            text = relativeAgeLabel(node.lastHeardEpochSeconds, nowSeconds),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = node.contactType.kindLabel(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(
                imageVector = AppIcons.Route,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(ROW_LOCATION_ICON_SIZE),
            )
            Text(
                text = node.routeKind.discoveryHopLabel() ?: node.routeKind.label(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val pathNodes = node.pathNodesHex
            if (node.showsDiscoveryPathIcon) {
                Icon(
                    imageVector = AppIcons.Timeline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(ROW_LOCATION_ICON_SIZE),
                )
                Text(
                    text = formatDiscoveryPath(pathNodes),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
            }
            if (node.hasLocation) {
                Icon(
                    imageVector = AppIcons.LocationOn,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(ROW_LOCATION_ICON_SIZE),
                )
                val distanceMeters = userLocation?.let(node::distanceMeters)
                if (distanceMeters != null) {
                    Text(
                        text = formattedStringResource(
                            Res.string.contacts_row_away,
                            rememberSnrBadgeFormatter().formatDistance(distanceMeters),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
internal fun EmptyBlock(title: String, description: String, icon: ImageVector, modifier: Modifier = Modifier) {
    IconLeadEmptyColumn(
        title = title,
        description = description,
        icon = icon,
        modifier = modifier.padding(24.dp),
    )
}

private val DISCOVERY_SEGMENT_VERTICAL_PADDING = 4.dp
private val ROW_LOCATION_ICON_SIZE = 16.dp
