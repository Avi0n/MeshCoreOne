package one.meshcore.shared.contact.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.contact.UserLocation
import one.meshcore.shared.contact.contactType
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_action_block
import one.meshcore.shared.resources.contacts_action_unblock
import one.meshcore.shared.resources.contacts_action_unfavorite
import one.meshcore.shared.resources.contacts_common_delete
import one.meshcore.shared.resources.contacts_detail_send_message
import one.meshcore.shared.resources.contacts_row_favorite
import one.meshcore.shared.ui.SwipeActionRow
import one.meshcore.shared.ui.SwipeRowAction
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.icons.favoriteToggleIcon
import one.meshcore.shared.ui.menu.McActionSheet
import one.meshcore.shared.ui.menu.McActionSheetItem
import one.meshcore.shared.ui.menu.McMenuRole
import org.jetbrains.compose.resources.stringResource

/**
 * One Nodes-list row: avatar, id-prefix + name, blocked/favorite markers, a relative timestamp, and a
 * second line with the (search-only) type label, route, and a location marker. A long press opens the
 * iOS-parity context menu (favorite, block/unblock for chats, delete), gated on [isReady]. Node management
 * entry points live on the contact detail screen, not the list menu.
 */
@Composable
fun ContactRow(
    contact: ContactDto,
    showTypeLabel: Boolean,
    /** The connected radio's key-prefix width in bytes (iOS `connectedDevice?.hashSize ?? 1`). */
    hashSize: Int,
    isReady: Boolean,
    isTogglingFavorite: Boolean,
    isDeletePending: Boolean,
    inboundHopCount: Int?,
    nowSeconds: Long,
    onTap: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleBlocked: () -> Unit,
    onDelete: () -> Unit,
    onSendMessage: (() -> Unit)? = null,
    userLocation: UserLocation? = null,
    /** Whether the row's context menu offers Delete; the ZephCore V-contact hides it (iOS `isVContact`). */
    allowDelete: Boolean = true,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val favoriteLabel =
        stringResource(
            if (contact.isFavorite) Res.string.contacts_action_unfavorite else Res.string.contacts_row_favorite,
        )
    val blockLabel =
        stringResource(if (contact.isBlocked) Res.string.contacts_action_unblock else Res.string.contacts_action_block)
    val deleteLabel = stringResource(Res.string.contacts_common_delete)

    Box {
        SwipeActionRow(
            leadingActions = listOf(
                SwipeRowAction(
                    label = favoriteLabel,
                    icon = favoriteToggleIcon(contact.isFavorite),
                    enabled = isReady && !isTogglingFavorite,
                    onClick = onToggleFavorite,
                ),
            ),
            trailingActions = buildList {
                if (contact.contactType == ContactType.CHAT) {
                    add(
                        SwipeRowAction(
                            label = blockLabel,
                            icon = blockToggleIcon(contact.isBlocked),
                            enabled = isReady,
                            onClick = onToggleBlocked,
                        ),
                    )
                }
                if (allowDelete) {
                    add(
                        SwipeRowAction(
                            label = deleteLabel,
                            icon = AppIcons.Delete,
                            enabled = isReady && !isDeletePending,
                            destructive = true,
                            onClick = onDelete,
                        ),
                    )
                }
            },
        ) { swipeModifier, consumeOpenTap ->
            ContactRowContent(
                contact = contact,
                showTypeLabel = showTypeLabel,
                hashSize = hashSize,
                isTogglingFavorite = isTogglingFavorite,
                inboundHopCount = inboundHopCount,
                userLocation = userLocation,
                nowSeconds = nowSeconds,
                modifier = swipeModifier.combinedClickable(
                    onClick = { if (!consumeOpenTap()) onTap() },
                    onLongClick = { menuOpen = true },
                ),
            )
        }

        ContextMenu(
            contact = contact,
            expanded = menuOpen,
            isReady = isReady,
            isTogglingFavorite = isTogglingFavorite,
            isDeletePending = isDeletePending,
            allowDelete = allowDelete,
            onDismiss = { menuOpen = false },
            onToggleFavorite = onToggleFavorite,
            onToggleBlocked = onToggleBlocked,
            onDelete = onDelete,
            onSendMessage = onSendMessage,
        )
    }
}

@Composable
private fun ContextMenu(
    contact: ContactDto,
    expanded: Boolean,
    isReady: Boolean,
    isTogglingFavorite: Boolean,
    isDeletePending: Boolean,
    allowDelete: Boolean,
    onDismiss: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleBlocked: () -> Unit,
    onDelete: () -> Unit,
    onSendMessage: (() -> Unit)? = null,
) {
    if (!expanded) return
    val favoriteLabel = if (contact.isFavorite) {
        Res.string.contacts_action_unfavorite
    } else {
        Res.string.contacts_row_favorite
    }
    val favoriteIcon = favoriteToggleIcon(contact.isFavorite)
    // Send Message sits at the top of the menu for chat-type, non-blocked contacts, mirroring iOS.
    val showSendMessage = onSendMessage != null && contact.contactType == ContactType.CHAT && !contact.isBlocked
    McActionSheet(onDismiss = onDismiss) {
        if (showSendMessage) {
            McActionSheetItem(
                text = stringResource(Res.string.contacts_detail_send_message),
                leadingIcon = AppIcons.Chat,
                enabled = isReady,
                onClick = {
                    onDismiss()
                    onSendMessage?.invoke()
                },
            )
        }
        McActionSheetItem(
            text = stringResource(favoriteLabel),
            leadingIcon = favoriteIcon,
            enabled = isReady && !isTogglingFavorite,
            onClick = {
                onDismiss()
                onToggleFavorite()
            },
        )
        if (contact.contactType == ContactType.CHAT) {
            val blockLabel = if (contact.isBlocked) {
                Res.string.contacts_action_unblock
            } else {
                Res.string.contacts_action_block
            }
            McActionSheetItem(
                text = stringResource(blockLabel),
                leadingIcon = blockToggleIcon(contact.isBlocked),
                enabled = isReady,
                onClick = {
                    onDismiss()
                    onToggleBlocked()
                },
            )
        }
        // ZephCore's loopback admin V-contact must keep its slot; removing it turns off the firmware
        // CLI, so Delete is hidden for it, mirroring the iOS `ContactContextMenuModifier` isVContact guard.
        if (allowDelete) {
            McActionSheetItem(
                text = stringResource(Res.string.contacts_common_delete),
                leadingIcon = AppIcons.Delete,
                role = McMenuRole.Destructive,
                enabled = isReady && !isDeletePending,
                onClick = {
                    onDismiss()
                    onDelete()
                },
            )
        }
    }
}
