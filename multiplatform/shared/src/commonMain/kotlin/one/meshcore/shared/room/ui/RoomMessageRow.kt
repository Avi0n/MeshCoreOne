package one.meshcore.shared.room.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import one.meshcore.shared.backup.RoomMessageDto
import one.meshcore.shared.chat.text.LinkTarget
import one.meshcore.shared.chat.text.MessageTextLinkifier
import one.meshcore.shared.chat.translation.MessageTranslationPhase
import one.meshcore.shared.chat.ui.BubbleTranslationControl
import one.meshcore.shared.chat.ui.ChatFormattedText
import one.meshcore.shared.chat.ui.INCOMING_AVATAR_COLUMN_WIDTH_DP
import one.meshcore.shared.chat.ui.IncomingAvatarGutter
import one.meshcore.shared.chat.ui.LocalTimestampFormatter
import one.meshcore.shared.chat.ui.chatMonthDayClockLabel
import one.meshcore.shared.chat.ui.formattedTextLinkActions
import one.meshcore.shared.chat.ui.messageBubbleLongPress
import one.meshcore.shared.chat.ui.senderIdentityColor
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_message_action_retry
import one.meshcore.shared.resources.chats_message_action_show_original
import one.meshcore.shared.resources.chats_message_action_translate
import one.meshcore.shared.resources.chats_message_action_translating
import one.meshcore.shared.resources.chats_message_status_retry
import one.meshcore.shared.resources.chats_message_translated_accessibility
import one.meshcore.shared.resources.remotenodes_remote_nodes_room_message_retry_hint
import one.meshcore.shared.resources.remotenodes_remote_nodes_room_message_status_delivered_label
import one.meshcore.shared.resources.remotenodes_remote_nodes_room_message_status_failed_label
import one.meshcore.shared.resources.remotenodes_remote_nodes_room_message_status_sending_label
import one.meshcore.shared.room.authorDisplayName
import one.meshcore.shared.theme.LocalAppTheme
import one.meshcore.shared.theme.LocalUseDarkTheme
import one.meshcore.shared.ui.LocalHighContrast
import one.meshcore.shared.ui.formattedStringResource
import one.meshcore.shared.ui.icons.AppIcons
import org.jetbrains.compose.resources.stringResource

private val BUBBLE_PADDING = 10.dp
private const val STATUS_GLYPH_DP = 12

@Composable
internal fun RoomMessageRow(
    message: RoomMessageDto,
    showTimestamp: Boolean,
    showSenderName: Boolean,
    showAvatar: Boolean,
    onRetry: () -> Unit,
    onLongPress: () -> Unit,
    onLinkTap: (LinkTarget) -> Unit,
    localNodeName: String?,
    translationPhase: MessageTranslationPhase?,
    onTranslate: () -> Unit,
) {
    val outgoing = message.isFromSelf
    val status = MessageStatus.fromRaw(message.statusRawValue)
    val formatted = remember(message.text, localNodeName) {
        MessageTextLinkifier.linkify(message.text, localNodeName).formatted
    }
    val linkActions = formattedTextLinkActions(formatted, onLinkTap)
    val author = message.authorDisplayName
    // Whether an outgoing message actually went out was carried only by the presence of a retry
    // button, so a screen reader could not tell a delivered message from a failed one.
    val spokenStatus = status?.takeIf { outgoing }?.let { stringResource(it.roomStatusLabel()) }
    val translatedSpeech = formattedStringResource(
        Res.string.chats_message_translated_accessibility,
        (translationPhase as? MessageTranslationPhase.Showing)?.translatedText.orEmpty(),
    )
    val inProgressSpeech = "${message.text}, ${stringResource(Res.string.chats_message_action_translating)}"
    val spokenBody = roomSpokenBody(
        phase = translationPhase,
        original = message.text,
        translatedLine = translatedSpeech,
        inProgressLine = inProgressSpeech,
    )
    val spokenLabel = roomMessageAccessibilityLabel(
        author = author,
        outgoing = outgoing,
        body = spokenBody,
        status = spokenStatus,
    )
    val messageActions = roomMessageCustomActions(
        input = RoomMessageActionInput(
            phase = translationPhase,
            outgoing = outgoing,
            status = status,
            translateLabel = stringResource(Res.string.chats_message_action_translate),
            showOriginalLabel = stringResource(Res.string.chats_message_action_show_original),
            retryLabel = stringResource(Res.string.chats_message_action_retry),
        ),
        linkActions = linkActions,
        onTranslate = onTranslate,
        onRetry = onRetry,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = roomSameClusterTopPaddingDp(showTimestamp).dp),
        horizontalAlignment = roomMessageHorizontalAlignment(outgoing),
    ) {
        if (showTimestamp) {
            Text(
                text = chatMonthDayClockLabel(message.timestamp, formatter = LocalTimestampFormatter.current),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
        if (roomMessageShowsAuthor(outgoing, showSenderName, author)) {
            Text(
                text = author,
                style = MaterialTheme.typography.labelMedium,
                color = senderIdentityColor(
                    name = author,
                    theme = LocalAppTheme.current,
                    useDark = LocalUseDarkTheme.current,
                    highContrast = LocalHighContrast.current,
                ),
                modifier = Modifier.padding(start = INCOMING_AVATAR_COLUMN_WIDTH_DP.dp),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = roomMessageHorizontalArrangement(outgoing),
            verticalAlignment = Alignment.Bottom,
        ) {
            if (!outgoing) {
                IncomingAvatarGutter(showAvatar = showAvatar, name = author)
            }
            Column(
                horizontalAlignment = roomMessageHorizontalAlignment(outgoing),
                modifier = Modifier.semantics(mergeDescendants = true) {
                    contentDescription = spokenLabel
                    customActions = messageActions
                },
            ) {
                Surface(
                    color = roomBubbleColor(roomBubbleTone(outgoing, status)),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.messageBubbleLongPress(
                        onLongPress = onLongPress,
                        role = Role.Button,
                    ),
                ) {
                    Column(Modifier.padding(BUBBLE_PADDING)) {
                        val showing = translationPhase as? MessageTranslationPhase.Showing
                        if (showing != null) {
                            Text(
                                text = showing.translatedText,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        } else {
                            ChatFormattedText(
                                formatted = formatted,
                                outgoing = outgoing,
                                style = MaterialTheme.typography.bodyLarge,
                                onLinkTap = onLinkTap,
                            )
                        }
                        if (translationPhase != null) {
                            BubbleTranslationControl(phase = translationPhase, onTap = onTranslate)
                        }
                    }
                }
                status?.takeIf { outgoing }?.let {
                    RoomOutgoingStatusRow(status = it, spokenStatus = spokenStatus.orEmpty(), onRetry = onRetry)
                }
            }
        }
    }
}

/**
 * Outgoing status under a room bubble, matching iOS `StatusIndicator`: Retry plus caption plus a failed
 * glyph. TalkBack on the combined bubble still uses [roomStatusLabel]; the Retry control speaks Retry and
 * uses the room retry hint as its action label.
 */
@Composable
private fun RoomOutgoingStatusRow(status: MessageStatus, spokenStatus: String, onRetry: () -> Unit) {
    val retry = stringResource(Res.string.chats_message_status_retry)
    val retryHint = stringResource(Res.string.remotenodes_remote_nodes_room_message_retry_hint)
    Row(
        modifier = Modifier
            .padding(end = 4.dp)
            .semantics(mergeDescendants = true) { contentDescription = spokenStatus },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (roomOutgoingShowsRetry(status)) {
            IconButton(
                onClick = onRetry,
                modifier = Modifier.semantics {
                    contentDescription = retry
                    onClick(label = retryHint, action = null)
                },
            ) {
                Icon(
                    imageVector = AppIcons.Refresh,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(STATUS_GLYPH_DP.dp),
                )
            }
            Text(
                text = retry,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            text = stringResource(roomOutgoingStatusCaption(status)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (roomOutgoingShowsFailedGlyph(status)) {
            Icon(
                imageVector = AppIcons.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(STATUS_GLYPH_DP.dp),
            )
        }
    }
}

/**
 * What a screen reader says about an outgoing room message, mirroring the iOS
 * `RoomMessageDTO.accessibilityStatusLabel`: anything still in flight speaks as sending, and everything
 * past that speaks as delivered.
 */
private fun MessageStatus.roomStatusLabel() = when (this) {
    MessageStatus.FAILED -> Res.string.remotenodes_remote_nodes_room_message_status_failed_label
    MessageStatus.PENDING, MessageStatus.SENDING, MessageStatus.RETRYING ->
        Res.string.remotenodes_remote_nodes_room_message_status_sending_label
    MessageStatus.SENT, MessageStatus.DELIVERED ->
        Res.string.remotenodes_remote_nodes_room_message_status_delivered_label
}
