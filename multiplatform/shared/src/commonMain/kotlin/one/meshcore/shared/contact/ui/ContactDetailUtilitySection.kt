package one.meshcore.shared.contact.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.contact.ContactDetailLogic
import one.meshcore.shared.contact.ContactPingResult
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_detail_block_contact
import one.meshcore.shared.resources.contacts_detail_favorite
import one.meshcore.shared.resources.contacts_detail_join_room
import one.meshcore.shared.resources.contacts_detail_management
import one.meshcore.shared.resources.contacts_detail_ping
import one.meshcore.shared.resources.contacts_detail_saved_history
import one.meshcore.shared.resources.contacts_detail_share_contact
import one.meshcore.shared.resources.contacts_detail_share_via_advert
import one.meshcore.shared.resources.contacts_detail_telemetry
import one.meshcore.shared.resources.contacts_detail_unblock_contact
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.tint
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun ContactDetailUtilitySection(
    contact: ContactDto,
    actionFlags: ContactDetailLogic.ActionFlags,
    enablement: ContactDetailLogic.ActionEnablement,
    radioHint: String?,
    onJoinRoom: (() -> Unit)?,
    onShowTelemetry: (() -> Unit)?,
    onShowSavedHistory: (() -> Unit)?,
    onManagement: (() -> Unit)?,
    onToggleFavorite: () -> Unit,
    onRequestBlock: () -> Unit,
    onShare: () -> Unit,
    shareAdvertBusy: Boolean,
    shareAdvertSuccess: Boolean,
    shareAdvertError: String?,
    onShareViaAdvert: () -> Unit,
    onPing: (() -> Unit)?,
    isPinging: Boolean,
    pingResult: ContactPingResult?,
) {
    val blockLabel = if (contact.isBlocked) {
        stringResource(Res.string.contacts_detail_unblock_contact)
    } else {
        stringResource(Res.string.contacts_detail_block_contact)
    }
    val favoriteLabel = stringResource(Res.string.contacts_detail_favorite)
    DetailSection(title = null) {
        if (actionFlags.joinRoom && onJoinRoom != null) {
            DetailActionItem(
                headline = stringResource(Res.string.contacts_detail_join_room),
                icon = AppIcons.MeetingRoom,
                enabled = enablement.joinRoom,
                onClick = onJoinRoom,
                radioHint = radioHint,
            )
        }
        if (onShowTelemetry != null) {
            DetailActionItem(
                headline = stringResource(Res.string.contacts_detail_telemetry),
                icon = AppIcons.ShowChart,
                enabled = enablement.telemetry,
                onClick = onShowTelemetry,
                radioHint = radioHint,
            )
        }
        if (onShowSavedHistory != null) {
            DetailActionItem(
                headline = stringResource(Res.string.contacts_detail_saved_history),
                icon = AppIcons.History,
                onClick = onShowSavedHistory,
            )
        }
        if (actionFlags.management && onManagement != null) {
            DetailActionItem(
                headline = stringResource(Res.string.contacts_detail_management),
                icon = AppIcons.SettingsSelected,
                enabled = enablement.management,
                onClick = onManagement,
                radioHint = radioHint,
            )
        }
        val favoriteContentColor = if (enablement.favorite) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA)
        }
        val favoriteIconTint = if (enablement.favorite) {
            contactDetailFavoriteRole(contact.isFavorite).tint()
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = DISABLED_ALPHA)
        }
        ListItem(
            headlineContent = { Text(favoriteLabel, color = favoriteContentColor) },
            leadingContent = {
                Icon(
                    imageVector = contactDetailFavoriteIcon(contact.isFavorite),
                    contentDescription = null,
                    tint = favoriteIconTint,
                )
            },
            trailingContent = {
                Switch(
                    checked = contact.isFavorite,
                    enabled = enablement.favorite,
                    onCheckedChange = null,
                    modifier = Modifier.clearAndSetSemantics {},
                )
            },
            colors = detailListColors(),
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (!enablement.favorite && radioHint != null) {
                        Modifier.semantics { stateDescription = radioHint }
                    } else {
                        Modifier
                    },
                )
                .toggleable(
                    value = contact.isFavorite,
                    enabled = enablement.favorite,
                    role = Role.Switch,
                    onValueChange = { onToggleFavorite() },
                ),
        )
        if (actionFlags.block) {
            DetailActionItem(
                headline = blockLabel,
                icon = blockToggleIcon(contact.isBlocked),
                enabled = enablement.block,
                onClick = onRequestBlock,
                destructive = contact.isBlocked.not(),
                radioHint = radioHint,
            )
        }
        DetailActionItem(
            headline = stringResource(Res.string.contacts_detail_share_contact),
            icon = AppIcons.Share,
            onClick = onShare,
        )
        ContactDetailShareAdvertRow(
            enablement = enablement,
            radioHint = radioHint,
            shareAdvertBusy = shareAdvertBusy,
            shareAdvertSuccess = shareAdvertSuccess,
            shareAdvertError = shareAdvertError,
            onShareViaAdvert = onShareViaAdvert,
        )
        ContactDetailPingRow(
            onPing = onPing,
            enablement = enablement,
            radioHint = radioHint,
            isPinging = isPinging,
            pingResult = pingResult,
        )
    }
}

@Composable
private fun ContactDetailShareAdvertRow(
    enablement: ContactDetailLogic.ActionEnablement,
    radioHint: String?,
    shareAdvertBusy: Boolean,
    shareAdvertSuccess: Boolean,
    shareAdvertError: String?,
    onShareViaAdvert: () -> Unit,
) {
    DetailActionItem(
        headline = stringResource(Res.string.contacts_detail_share_via_advert),
        icon = AppIcons.CellTower,
        enabled = enablement.shareViaAdvert,
        onClick = onShareViaAdvert,
        radioHint = radioHint,
        trailing = when {
            shareAdvertBusy -> {
                {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }
            shareAdvertSuccess -> {
                {
                    Icon(
                        imageVector = AppIcons.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            else -> null
        },
    )
    if (shareAdvertError != null) {
        Text(
            text = shareAdvertError,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun ContactDetailPingRow(
    onPing: (() -> Unit)?,
    enablement: ContactDetailLogic.ActionEnablement,
    radioHint: String?,
    isPinging: Boolean,
    pingResult: ContactPingResult?,
) {
    if (onPing != null) {
        DetailActionItem(
            headline = stringResource(Res.string.contacts_detail_ping),
            icon = AppIcons.WifiTethering,
            enabled = enablement.ping,
            onClick = onPing,
            radioHint = radioHint,
            trailing = if (isPinging) {
                {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                }
            } else {
                null
            },
        )
        pingResult?.let { result ->
            PingResultRow(
                result = result,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}
