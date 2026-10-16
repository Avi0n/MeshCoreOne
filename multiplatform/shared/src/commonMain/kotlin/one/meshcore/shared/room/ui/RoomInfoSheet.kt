package one.meshcore.shared.room.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import one.meshcore.shared.avatar.AvatarCategory
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.channel.ui.NotificationLevelRow
import one.meshcore.shared.channel.ui.notificationLevelPickerHintRes
import one.meshcore.shared.contact.ui.NodeAvatar
import one.meshcore.shared.model.NotificationLevel
import one.meshcore.shared.model.RoomPermissionLevel
import one.meshcore.shared.remotenode.isAdmin
import one.meshcore.shared.remotenode.publicKeyHex
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_notification_level_label
import one.meshcore.shared.resources.remotenodes_remote_nodes_auth_type_room
import one.meshcore.shared.resources.remotenodes_remote_nodes_name
import one.meshcore.shared.resources.remotenodes_remote_nodes_permission_admin
import one.meshcore.shared.resources.remotenodes_remote_nodes_permission_guest
import one.meshcore.shared.resources.remotenodes_remote_nodes_permission_member
import one.meshcore.shared.resources.remotenodes_remote_nodes_room_activity
import one.meshcore.shared.resources.remotenodes_remote_nodes_room_connected
import one.meshcore.shared.resources.remotenodes_remote_nodes_room_details
import one.meshcore.shared.resources.remotenodes_remote_nodes_room_identification
import one.meshcore.shared.resources.remotenodes_remote_nodes_room_last_connected
import one.meshcore.shared.resources.remotenodes_remote_nodes_room_permission
import one.meshcore.shared.resources.remotenodes_remote_nodes_room_public_key
import one.meshcore.shared.resources.remotenodes_remote_nodes_room_settings_title
import one.meshcore.shared.resources.remotenodes_remote_nodes_room_status
import one.meshcore.shared.resources.remotenodes_remote_nodes_room_telemetry
import one.meshcore.shared.resources.settings_public_key_copy
import one.meshcore.shared.ui.PreferenceHubHorizontalPadding
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.PreferenceHubTopPadding
import one.meshcore.shared.ui.PreferenceHubValueRow
import one.meshcore.shared.ui.icons.AppIconButton
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.namedRelativeLabel
import one.meshcore.shared.ui.plainTextClipEntry
import one.meshcore.shared.ui.preferenceHubFormScroll
import one.meshcore.shared.ui.rememberMinuteTickSeconds
import org.jetbrains.compose.resources.stringResource

/** Header room-avatar diameter (the Material analog of the iOS 150-pt `NodeAvatar`). */
private val ROOM_INFO_AVATAR_SIZE = 96.dp

/** Room notification levels (muted / all), matching iOS `NotificationLevel.roomLevels`. */
val ROOM_NOTIFICATION_LEVELS: List<NotificationLevel> =
    listOf(NotificationLevel.MUTED, NotificationLevel.ALL)

/**
 * Room info body: pin, mute/all, details, last connected, public key, and connected-status /
 * admin settings entries. Hosted as a nested destination, matching Channel Info.
 */
@Composable
fun RoomInfoSheet(
    session: RemoteNodeSessionDto,
    onPinnedChange: suspend (Boolean) -> Unit,
    onNotificationLevelChange: suspend (NotificationLevel) -> Unit,
    isRadioReady: () -> Boolean = { true },
    modifier: Modifier = Modifier,
    onOpenStatus: (() -> Unit)? = null,
    onOpenSettings: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    var notificationLevel by remember(session.id, session.radioID, session.notificationLevel) {
        mutableStateOf(session.notificationLevel)
    }
    val nowSeconds by rememberMinuteTickSeconds()

    Column(
        modifier
            .fillMaxSize()
            .padding(horizontal = PreferenceHubHorizontalPadding)
            .padding(top = PreferenceHubTopPadding, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(
            modifier = preferenceHubFormScroll(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(),
            ) {
                NodeAvatar(AvatarCategory.ROOM, ROOM_INFO_AVATAR_SIZE)
                Text(
                    session.name,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Text(
                    stringResource(Res.string.remotenodes_remote_nodes_auth_type_room),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            PreferenceHubSection(applyHorizontalPadding = false) {
                RoomInfoPinPreference(session, onPinnedChange)
            }

            PreferenceHubSection(
                title = stringResource(Res.string.chats_notification_level_label),
                applyHorizontalPadding = false,
            ) {
                val levelHint = stringResource(notificationLevelPickerHintRes())
                ROOM_NOTIFICATION_LEVELS.forEach { level ->
                    NotificationLevelRow(
                        level = level,
                        selected = level == notificationLevel,
                        levelHint = levelHint,
                        onClick = {
                            if (!isRadioReady()) return@NotificationLevelRow
                            val previous = notificationLevel
                            notificationLevel = level
                            scope.launch {
                                if (!persistRoomInfoChange(isRadioReady()) {
                                        onNotificationLevelChange(level)
                                    }
                                ) {
                                    notificationLevel = previous
                                }
                            }
                        },
                    )
                }
            }

            PreferenceHubSection(
                title = stringResource(Res.string.remotenodes_remote_nodes_room_details),
                applyHorizontalPadding = false,
            ) {
                PreferenceHubValueRow(
                    label = stringResource(Res.string.remotenodes_remote_nodes_name),
                    value = session.name,
                )
                PreferenceHubValueRow(
                    label = stringResource(Res.string.remotenodes_remote_nodes_room_permission),
                    value = session.permissionLevel.label(),
                )
                // iOS `RoomInfoSheet` Details shows Status only while connected.
                if (session.isConnected) {
                    PreferenceHubValueRow(
                        label = stringResource(Res.string.remotenodes_remote_nodes_room_status),
                        value = stringResource(Res.string.remotenodes_remote_nodes_room_connected),
                    )
                }
            }

            session.lastConnectedDate?.let { lastConnected ->
                PreferenceHubSection(
                    title = stringResource(Res.string.remotenodes_remote_nodes_room_activity),
                    applyHorizontalPadding = false,
                ) {
                    PreferenceHubValueRow(
                        label = stringResource(Res.string.remotenodes_remote_nodes_room_last_connected),
                        value = namedRelativeLabel(lastConnected.toLong(), nowSeconds),
                    )
                }
            }

            PreferenceHubSection(
                title = stringResource(Res.string.remotenodes_remote_nodes_room_identification),
                applyHorizontalPadding = false,
            ) {
                ListItem(
                    headlineContent = {
                        Text(stringResource(Res.string.remotenodes_remote_nodes_room_public_key))
                    },
                    supportingContent = { Text(session.publicKeyHex) },
                    trailingContent = {
                        AppIconButton(
                            imageVector = AppIcons.ContentCopy,
                            contentDescription = stringResource(Res.string.settings_public_key_copy),
                            onClick = {
                                scope.launch {
                                    clipboard.setClipEntry(plainTextClipEntry(session.publicKeyHex))
                                }
                            },
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (session.isConnected && onOpenStatus != null) {
            FilledTonalButton(
                onClick = onOpenStatus,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.remotenodes_remote_nodes_room_telemetry))
            }
        }
        if (session.isConnected && session.isAdmin && onOpenSettings != null) {
            FilledTonalButton(
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.remotenodes_remote_nodes_room_settings_title))
            }
        }
    }
}

@Composable
internal fun RoomPermissionLevel.label(): String = stringResource(
    when (this) {
        RoomPermissionLevel.GUEST -> Res.string.remotenodes_remote_nodes_permission_guest
        RoomPermissionLevel.READ_WRITE -> Res.string.remotenodes_remote_nodes_permission_member
        RoomPermissionLevel.ADMIN -> Res.string.remotenodes_remote_nodes_permission_admin
    },
)
