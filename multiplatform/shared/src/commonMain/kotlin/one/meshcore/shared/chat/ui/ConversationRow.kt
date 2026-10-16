package one.meshcore.shared.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import one.meshcore.shared.avatar.AvatarCategory
import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.channel.displayName
import one.meshcore.shared.channel.ui.ChannelAvatar
import one.meshcore.shared.chat.Conversation
import one.meshcore.shared.chat.ConversationListViewModel
import one.meshcore.shared.chat.previewText
import one.meshcore.shared.contact.ui.ContactAvatar
import one.meshcore.shared.contact.ui.NodeAvatar
import one.meshcore.shared.model.NotificationLevel
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_channel_default_name
import one.meshcore.shared.resources.chats_room_connected
import one.meshcore.shared.resources.chats_room_tap_to_reconnect
import one.meshcore.shared.resources.chats_row_failed_send
import one.meshcore.shared.resources.chats_row_mentions_only
import one.meshcore.shared.resources.chats_row_muted
import one.meshcore.shared.resources.chats_row_no_messages
import one.meshcore.shared.resources.localizable_tabs_chats_unread_accessibility_value
import one.meshcore.shared.ui.formatTemplate
import one.meshcore.shared.ui.formattedStringResource
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.tint
import org.jetbrains.compose.resources.stringResource

/** The row avatar diameter, matching the iOS conversation-row 44pt avatar. */
private val ROW_AVATAR_SIZE = 44.dp
private val FAILED_SEND_BADGE_SIZE = 18.dp

/**
 * A conversation-list row, dispatched by kind. Each renders an avatar, a headline name, the
 * notification-level indicator, a favorite star (or a spinner while a contact's toggle is in flight),
 * a last-message timestamp, a preview line, and the unread badges. Kotlin port of the iOS
 * `ConversationRow` / `ChannelConversationRow` / `RoomConversationRow`.
 */
@Composable
fun ConversationRow(
    conversation: Conversation,
    viewModel: ConversationListViewModel,
    isConnectedReady: Boolean,
    nowSeconds: Long,
    modifier: Modifier = Modifier,
    /** When non-null, preferred over [ConversationListViewModel.previewText] so list composition tracks state. */
    previewOverride: String? = null,
    hasFailedSend: Boolean = false,
) {
    when (conversation) {
        is Conversation.Direct -> DirectRow(
            conversation.contact,
            viewModel,
            nowSeconds,
            previewOverride,
            hasFailedSend,
            modifier,
        )
        is Conversation.Channel -> ChannelRow(
            conversation.channel,
            viewModel,
            nowSeconds,
            previewOverride,
            hasFailedSend,
            modifier,
        )
        is Conversation.Room -> RoomRow(
            conversation.session,
            isConnectedReady,
            nowSeconds,
            hasFailedSend,
            modifier,
        )
    }
}

@Composable
private fun DirectRow(
    contact: ContactDto,
    viewModel: ConversationListViewModel,
    nowSeconds: Long,
    previewOverride: String?,
    hasFailedSend: Boolean,
    modifier: Modifier,
) {
    val preview = previewOverride
        ?: viewModel.previewText(contact.id)
        ?: stringResource(Res.string.chats_row_no_messages)
    RowScaffold(
        modifier = modifier,
        avatar = { ContactAvatar(contact = contact, size = ROW_AVATAR_SIZE) },
        name = contact.nickname ?: contact.name,
        level = if (contact.isMuted) NotificationLevel.MUTED else NotificationLevel.ALL,
        lastMessageDate = contact.lastMessageDate,
        nowSeconds = nowSeconds,
        preview = preview,
        unreadCount = contact.unreadCount,
        unreadMentionCount = contact.unreadMentionCount,
        hasFailedSend = hasFailedSend,
    )
}

@Composable
private fun ChannelRow(
    channel: ChannelDto,
    viewModel: ConversationListViewModel,
    nowSeconds: Long,
    previewOverride: String?,
    hasFailedSend: Boolean,
    modifier: Modifier,
) {
    val unnamedTemplate = stringResource(Res.string.chats_channel_default_name)
    val name = channel.displayName { formatTemplate(unnamedTemplate, listOf(it)) }
    val preview = previewOverride
        ?: viewModel.previewText(channel.id)
        ?: stringResource(Res.string.chats_row_no_messages)
    RowScaffold(
        modifier = modifier,
        avatar = { ChannelAvatar(channel = channel, size = ROW_AVATAR_SIZE) },
        name = name,
        level = channel.notificationLevel,
        lastMessageDate = channel.lastMessageDate,
        nowSeconds = nowSeconds,
        preview = preview,
        unreadCount = channel.unreadCount,
        unreadMentionCount = channel.unreadMentionCount,
        hasFailedSend = hasFailedSend,
    )
}

@Composable
private fun RoomRow(
    session: RemoteNodeSessionDto,
    isConnectedReady: Boolean,
    nowSeconds: Long,
    hasFailedSend: Boolean,
    modifier: Modifier,
) {
    val connected = session.isConnected && isConnectedReady
    val preview = if (connected) {
        stringResource(Res.string.chats_room_connected)
    } else {
        stringResource(Res.string.chats_room_tap_to_reconnect)
    }
    RowScaffold(
        modifier = modifier,
        avatar = { NodeAvatar(category = AvatarCategory.ROOM, size = ROW_AVATAR_SIZE) },
        name = session.name,
        level = session.notificationLevel,
        lastMessageDate = session.lastMessageDate,
        nowSeconds = nowSeconds,
        preview = preview,
        previewColor = if (connected) MaterialTheme.colorScheme.primary else null,
        previewLeadingIcon = if (connected) AppIcons.CheckCircle else null,
        unreadCount = session.unreadCount,
        unreadMentionCount = 0,
        hasFailedSend = hasFailedSend,
    )
}

@Composable
private fun RowScaffold(
    modifier: Modifier,
    avatar: @Composable () -> Unit,
    name: String,
    level: NotificationLevel,
    lastMessageDate: Double?,
    nowSeconds: Long,
    preview: String,
    unreadCount: Int,
    unreadMentionCount: Int,
    hasFailedSend: Boolean,
    previewColor: Color? = null,
    previewLeadingIcon: ImageVector? = null,
) {
    // Material 3 ListItem density (leading avatar, headline, supporting, trailing meta).
    ListItem(
        modifier = modifier,
        leadingContent = avatar,
        headlineContent = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                NotificationLevelIndicator(level)
            }
        },
        supportingContent = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (previewLeadingIcon != null) {
                    Icon(
                        imageVector = previewLeadingIcon,
                        contentDescription = null,
                        tint = previewColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(ROOM_CONNECTED_ICON_SIZE),
                    )
                }
                Text(
                    preview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = previewColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        },
        trailingContent = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (lastMessageDate != null) {
                    Text(
                        conversationTimestampLabel(lastMessageDate, nowSeconds),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (hasFailedSend) FailedSendIndicator()
                    UnreadBadges(unreadCount = unreadCount, unreadMentionCount = unreadMentionCount, level = level)
                }
            }
        },
    )
}

/**
 * The per-conversation notification-level glyph: a bell-slash for muted, an at-symbol for mentions-only,
 * nothing for all. Kotlin port of the iOS `NotificationLevelIndicator`.
 */
@Composable
fun NotificationLevelIndicator(level: NotificationLevel) {
    val label = when (level) {
        NotificationLevel.MUTED -> stringResource(Res.string.chats_row_muted)
        NotificationLevel.MENTIONS_ONLY -> stringResource(Res.string.chats_row_mentions_only)
        NotificationLevel.ALL -> return
    }
    val icon = if (level == NotificationLevel.MUTED) AppIcons.NotificationsOff else AppIcons.AlternateEmail
    Icon(
        imageVector = icon,
        contentDescription = label,
        modifier = Modifier.size(NOTIFICATION_LEVEL_ICON_SIZE),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Presence-only badge for a conversation with at least one outgoing failed send. */
@Composable
private fun FailedSendIndicator() {
    val label = stringResource(Res.string.chats_row_failed_send)
    Box(
        modifier = Modifier
            .size(FAILED_SEND_BADGE_SIZE)
            .background(MaterialTheme.colorScheme.error, CircleShape)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = AppIcons.Refresh,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onError,
            modifier = Modifier.size(12.dp),
        )
    }
}

/**
 * The unread badges: an `@` mention circle when [unreadMentionCount] > 0 (blue unless muted), and an
 * unread-count capsule when [unreadCount] > 0 (blue only at level [NotificationLevel.ALL], else muted).
 * Kotlin port of the iOS `UnreadBadges`.
 */
@Composable
fun UnreadBadges(unreadCount: Int, unreadMentionCount: Int, level: NotificationLevel) {
    if (unreadCount == 0 && unreadMentionCount == 0) return
    val scheme = MaterialTheme.colorScheme
    val mentionContainer =
        if (level == NotificationLevel.MUTED) scheme.surfaceContainerHighest else scheme.primary
    val mentionContent =
        if (level == NotificationLevel.MUTED) scheme.onSurfaceVariant else scheme.onPrimary
    val unreadContainer =
        if (level == NotificationLevel.ALL) scheme.primary else scheme.surfaceContainerHighest
    val unreadContent =
        if (level == NotificationLevel.ALL) scheme.onPrimary else scheme.onSurfaceVariant
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (unreadMentionCount > 0) {
            Box(
                modifier = Modifier.size(MENTION_BADGE_SIZE).background(mentionContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "@",
                    color = mentionContent,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        if (unreadCount > 0) {
            val unreadLabel = formattedStringResource(
                Res.string.localizable_tabs_chats_unread_accessibility_value,
                unreadCount,
            )
            Surface(color = unreadContainer, shape = MaterialTheme.shapes.small) {
                Text(
                    unreadCount.toString(),
                    color = unreadContent,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                        .semantics { contentDescription = unreadLabel },
                )
            }
        }
    }
}

private val ROOM_CONNECTED_ICON_SIZE = 14.dp
private val NOTIFICATION_LEVEL_ICON_SIZE = 16.dp
private val MENTION_BADGE_SIZE = 18.dp
