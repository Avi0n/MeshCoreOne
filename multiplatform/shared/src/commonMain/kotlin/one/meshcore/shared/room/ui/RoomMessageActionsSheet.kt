package one.meshcore.shared.room.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.backup.RoomMessageDto
import one.meshcore.shared.chat.ui.ActionInfoRow
import one.meshcore.shared.chat.ui.LocalTimestampFormatter
import one.meshcore.shared.chat.ui.MESSAGE_ACTIONS_PREVIEW_PADDING
import one.meshcore.shared.chat.ui.MessageDetailsCard
import one.meshcore.shared.chat.ui.SEND_AGAIN_ICON
import one.meshcore.shared.chat.ui.SEND_DM_ICON
import one.meshcore.shared.chat.ui.chatClockLabel
import one.meshcore.shared.chat.ui.formatMessageDetailDateTime
import one.meshcore.shared.chat.ui.messageActionsPreviewLineLimit
import one.meshcore.shared.chat.ui.rememberMessageActionsSheetChrome
import one.meshcore.shared.chat.ui.senderIdentityColor
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_message_action_copy
import one.meshcore.shared.resources.chats_message_action_details
import one.meshcore.shared.resources.chats_message_action_mention
import one.meshcore.shared.resources.chats_message_action_reply
import one.meshcore.shared.resources.chats_message_action_send_again
import one.meshcore.shared.resources.chats_message_action_send_dm
import one.meshcore.shared.resources.chats_message_action_translate
import one.meshcore.shared.resources.chats_message_info_received
import one.meshcore.shared.resources.chats_message_info_round_trip
import one.meshcore.shared.resources.chats_message_info_sent
import one.meshcore.shared.room.RoomMessageAction
import one.meshcore.shared.room.RoomMessageActionAvailability
import one.meshcore.shared.room.RoomMessageActionDetails
import one.meshcore.shared.room.authorDisplayName
import one.meshcore.shared.theme.LocalAppTheme
import one.meshcore.shared.theme.LocalUseDarkTheme
import one.meshcore.shared.ui.ExpressiveSheetContent
import one.meshcore.shared.ui.LocalHighContrast
import one.meshcore.shared.ui.formattedStringResource
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.menu.McActionSheetDivider
import one.meshcore.shared.ui.menu.McActionSheetItem
import one.meshcore.shared.ui.menu.McActionSheetSectionHeader
import org.jetbrains.compose.resources.stringResource

/**
 * Long-press sheet for a room message: pinned preview, action buttons, then the Details block
 * (sent / received / status / optional RTT). Mirrors iOS `RoomMessageActionsSheet` — no reactions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomMessageActionsSheet(
    message: RoomMessageDto,
    session: RemoteNodeSessionDto,
    onAction: (RoomMessageAction) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    replyWithQuote: Boolean = false,
    translateLabel: String? = null,
) {
    val availability = RoomMessageActionAvailability.of(message, session)
    val chrome = rememberMessageActionsSheetChrome()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = chrome.sheetState, modifier = modifier) {
        ExpressiveSheetContent(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
        ) {
            RoomMessageActionsHeader(
                message = message,
                previewLineLimit = messageActionsPreviewLineLimit(chrome.accessibilitySize),
            )
            McActionSheetDivider()
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                for (action in availability.availableActions) {
                    val label = when (action) {
                        RoomMessageAction.COPY -> stringResource(Res.string.chats_message_action_copy)
                        RoomMessageAction.TRANSLATE ->
                            translateLabel ?: stringResource(Res.string.chats_message_action_translate)
                        RoomMessageAction.REPLY ->
                            stringResource(
                                // Quote-on uses Reply; quote-off inserts a bare @name, so it reads Mention.
                                if (replyWithQuote) {
                                    Res.string.chats_message_action_reply
                                } else {
                                    Res.string.chats_message_action_mention
                                },
                            )
                        RoomMessageAction.SEND_DM -> stringResource(Res.string.chats_message_action_send_dm)
                        RoomMessageAction.SEND_AGAIN -> stringResource(Res.string.chats_message_action_send_again)
                    }
                    McActionSheetItem(
                        text = label,
                        leadingIcon = action.icon(),
                        onClick = {
                            onAction(action)
                            onDismiss()
                        },
                    )
                }
                RoomMessageDetailsSection(message)
            }
        }
    }
}

@Composable
private fun RoomMessageActionsHeader(message: RoomMessageDto, previewLineLimit: Int) {
    val time = chatClockLabel(message.timestamp, formatter = LocalTimestampFormatter.current)
    val author = message.authorDisplayName
    Column(Modifier.fillMaxWidth().padding(MESSAGE_ACTIONS_PREVIEW_PADDING)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = author,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = ROOM_ACTIONS_AUTHOR_WEIGHT,
                color = senderIdentityColor(
                    name = author,
                    theme = LocalAppTheme.current,
                    useDark = LocalUseDarkTheme.current,
                    highContrast = LocalHighContrast.current,
                ),
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = time,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = message.text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = previewLineLimit,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun RoomMessageDetailsSection(message: RoomMessageDto) {
    McActionSheetSectionHeader(stringResource(Res.string.chats_message_action_details))
    MessageDetailsCard {
        for (line in RoomMessageActionDetails.lines(message)) {
            val text = when (line) {
                is RoomMessageActionDetails.Line.Sent ->
                    formattedStringResource(
                        Res.string.chats_message_info_sent,
                        formatMessageDetailDateTime(line.epochSeconds, formatter = LocalTimestampFormatter.current),
                    )
                is RoomMessageActionDetails.Line.Received ->
                    formattedStringResource(
                        Res.string.chats_message_info_received,
                        formatMessageDetailDateTime(line.epochSeconds, formatter = LocalTimestampFormatter.current),
                    )
                is RoomMessageActionDetails.Line.Status -> roomMessageStatusLabel(line.status)
                is RoomMessageActionDetails.Line.RoundTrip ->
                    formattedStringResource(
                        Res.string.chats_message_info_round_trip,
                        line.milliseconds.toInt(),
                    )
            }
            ActionInfoRow(text = text)
        }
    }
}

@Composable
private fun roomMessageStatusLabel(status: MessageStatus): String = stringResource(roomOutgoingStatusCaption(status))

private fun RoomMessageAction.icon(): ImageVector = when (this) {
    RoomMessageAction.COPY -> AppIcons.ContentCopy
    RoomMessageAction.TRANSLATE -> AppIcons.Translate
    RoomMessageAction.REPLY -> AppIcons.Reply
    RoomMessageAction.SEND_DM -> SEND_DM_ICON
    RoomMessageAction.SEND_AGAIN -> SEND_AGAIN_ICON
}

/** Matches `SenderNameLabel` rather than an iOS Form title. */
internal val ROOM_ACTIONS_AUTHOR_WEIGHT = FontWeight.SemiBold
