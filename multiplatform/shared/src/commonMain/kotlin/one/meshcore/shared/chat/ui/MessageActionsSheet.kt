package one.meshcore.shared.chat.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.chat.MessageItem
import one.meshcore.shared.chat.reactions.RecentEmojisStore
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_message_action_block_sender
import one.meshcore.shared.resources.chats_message_action_copy
import one.meshcore.shared.resources.chats_message_action_delete
import one.meshcore.shared.resources.chats_message_action_mention
import one.meshcore.shared.resources.chats_message_action_reply
import one.meshcore.shared.resources.chats_message_action_send_again
import one.meshcore.shared.resources.chats_message_action_send_dm
import one.meshcore.shared.resources.chats_message_action_translate
import one.meshcore.shared.ui.ExpressiveSheetContent
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.menu.McActionSheetDivider
import one.meshcore.shared.ui.menu.McActionSheetItem
import one.meshcore.shared.ui.menu.McMenuRole
import one.meshcore.shared.ui.rememberAppHaptics
import one.meshcore.shared.ui.rememberReduceMotion
import org.jetbrains.compose.resources.stringResource

/**
 * The long-press message actions sheet: preview, reaction row, Reply/Mention, Send DM, Copy,
 * Translate, Send Again, then Path Details / Repeat Details (iOS `ActionsDetailsSection`), then
 * Block and Delete (`ActionsDestructiveSection`). Image retry, View Reactions, and failed-send
 * Retry stay on the bubble / TalkBack set, matching iOS. Bound to a captured [item] so an incoming
 * reorder can't re-anchor the actions to a different message.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MessageActionsSheet(
    item: MessageItem,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    replyWithQuote: Boolean = false,
    onReply: (() -> Unit)? = null,
    onSendAgain: (() -> Unit)? = null,
    onBlockSender: (() -> Unit)? = null,
    onSendDm: (() -> Unit)? = null,
    onOpenPathMap: ((Uuid?) -> Unit)? = null,
    onOpenRepeatDetails: (() -> Unit)? = null,
    onTranslate: (() -> Unit)? = null,
    translateLabel: String? = null,
    recentEmojis: List<String> = RecentEmojisStore.DEFAULT_EMOJIS,
    frequentEmojis: List<String> = emptyList(),
    onReact: ((String) -> Unit)? = null,
    messageDetails: MessageDto? = null,
    pathPresentation: MessagePathPresentation? = null,
    isNetworkOffline: Boolean = false,
) {
    var selectedPathArrivalID by remember(item.id) { mutableStateOf<Uuid?>(null) }
    val chrome = rememberMessageActionsSheetChrome()
    val accessibilitySize = chrome.accessibilitySize
    val sheetState = chrome.sheetState
    var showEmojiPicker by remember { mutableStateOf(false) }
    // Defer reaction selection from the nested picker until the picker dismisses so the parent sheet
    // is not torn down while the child is still presented (iOS ActionsEmojiSection pattern).
    var pendingEmoji by remember { mutableStateOf<String?>(null) }
    val reduceMotion = rememberReduceMotion()
    val pathDetailsBringIntoView = remember(reduceMotion) { PathDetailsBringIntoViewSpec(reduceMotion) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, modifier = modifier) {
        CompositionLocalProvider(LocalBringIntoViewSpec provides pathDetailsBringIntoView) {
            ExpressiveSheetContent(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
            ) {
                MessageActionsPreviewHeader(
                    item = item,
                    message = messageDetails,
                    previewLineLimit = messageActionsPreviewLineLimit(accessibilitySize),
                )
                McActionSheetDivider()
                if (onReact != null && !accessibilitySize) {
                    EmojiPickerRow(
                        emojis = recentEmojis,
                        onSelect = { emoji ->
                            onReact(emoji)
                            onDismiss()
                        },
                        onOpenMore = { showEmojiPicker = true },
                        modifier = Modifier.padding(vertical = EMOJI_ROW_V_PADDING),
                    )
                    McActionSheetDivider()
                }
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    if (onReact != null && accessibilitySize) {
                        EmojiPickerRow(
                            emojis = recentEmojis,
                            onSelect = { emoji ->
                                onReact(emoji)
                                onDismiss()
                            },
                            onOpenMore = { showEmojiPicker = true },
                            modifier = Modifier.padding(vertical = EMOJI_ROW_V_PADDING),
                        )
                        McActionSheetDivider()
                    }
                    MessageActionsRows(
                        replyWithQuote = replyWithQuote,
                        onReply = onReply,
                        onSendDm = onSendDm,
                        onCopy = onCopy,
                        onTranslate = onTranslate,
                        translateLabel = translateLabel,
                        onSendAgain = onSendAgain,
                        messageDetails = messageDetails,
                        pathPresentation = pathPresentation,
                        selectedPathArrivalID = selectedPathArrivalID,
                        onSelectPathArrival = { selectedPathArrivalID = it },
                        isNetworkOffline = isNetworkOffline,
                        onOpenPathMap = onOpenPathMap,
                        onOpenRepeatDetails = onOpenRepeatDetails,
                        onBlockSender = onBlockSender,
                        onDelete = onDelete,
                        onDismiss = onDismiss,
                    )
                }
            }
        }
    }

    if (showEmojiPicker) {
        EmojiPickerSheet(
            onSelect = { pendingEmoji = it },
            frequent = frequentEmojis,
            onDismiss = {
                showEmojiPicker = false
                val emoji = pendingEmoji
                pendingEmoji = null
                if (emoji != null && onReact != null) {
                    onReact(emoji)
                    onDismiss()
                }
            },
        )
    }
}

@Composable
private fun MessageActionsRows(
    replyWithQuote: Boolean,
    onReply: (() -> Unit)?,
    onSendDm: (() -> Unit)?,
    onCopy: () -> Unit,
    onTranslate: (() -> Unit)?,
    translateLabel: String?,
    onSendAgain: (() -> Unit)?,
    messageDetails: MessageDto?,
    pathPresentation: MessagePathPresentation?,
    selectedPathArrivalID: Uuid?,
    onSelectPathArrival: (Uuid) -> Unit,
    isNetworkOffline: Boolean,
    onOpenPathMap: ((Uuid?) -> Unit)?,
    onOpenRepeatDetails: (() -> Unit)?,
    onBlockSender: (() -> Unit)?,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val haptics = rememberAppHaptics()
    // Visibility is gated by MessageActionAvailability at the call site (null callbacks).
    if (onReply != null) {
        McActionSheetItem(
            // Default (quoting off) appends a bare @name mention, so the button reads "Mention";
            // with quoting on it inserts a quoted preview and reads "Reply" (iOS ActionsButtonsSection).
            text = if (replyWithQuote) {
                stringResource(Res.string.chats_message_action_reply)
            } else {
                stringResource(Res.string.chats_message_action_mention)
            },
            leadingIcon = AppIcons.Reply,
            onClick = {
                onReply()
                onDismiss()
            },
        )
    }
    if (onSendDm != null) {
        McActionSheetItem(
            text = stringResource(Res.string.chats_message_action_send_dm),
            leadingIcon = SEND_DM_ICON,
            onClick = {
                onSendDm()
                onDismiss()
            },
        )
    }
    McActionSheetItem(
        text = stringResource(Res.string.chats_message_action_copy),
        leadingIcon = AppIcons.ContentCopy,
        onClick = {
            onCopy()
            onDismiss()
        },
    )
    if (onTranslate != null) {
        McActionSheetItem(
            text = translateLabel ?: stringResource(Res.string.chats_message_action_translate),
            leadingIcon = AppIcons.Translate,
            onClick = {
                onTranslate()
                onDismiss()
            },
        )
    }
    if (onSendAgain != null) {
        McActionSheetItem(
            text = stringResource(Res.string.chats_message_action_send_again),
            leadingIcon = SEND_AGAIN_ICON,
            onClick = {
                onSendAgain()
                onDismiss()
            },
        )
    }
    ChatMessageDetailsSection(
        message = messageDetails,
        pathPresentation = pathPresentation,
        selectedPathArrivalID = selectedPathArrivalID,
        onSelectPathArrival = onSelectPathArrival,
        isNetworkOffline = isNetworkOffline,
        onOpenPathMap = onOpenPathMap,
        onOpenRepeatDetails = onOpenRepeatDetails,
    )
    McActionSheetDivider()
    if (onBlockSender != null) {
        McActionSheetItem(
            text = stringResource(Res.string.chats_message_action_block_sender),
            leadingIcon = BLOCK_SENDER_ICON,
            role = McMenuRole.Destructive,
            onClick = {
                haptics.warning()
                onBlockSender()
                onDismiss()
            },
        )
    }
    McActionSheetItem(
        text = stringResource(Res.string.chats_message_action_delete),
        leadingIcon = AppIcons.Delete,
        role = McMenuRole.Destructive,
        onClick = {
            haptics.warning()
            onDelete()
            onDismiss()
        },
    )
}

private val EMOJI_ROW_V_PADDING = 8.dp

/** iOS `ActionsButtonsSection` Send Again: `arrow.uturn.forward`. */
internal val SEND_AGAIN_ICON get() = AppIcons.Redo

/** iOS `ActionsButtonsSection` Send DM: `bubble.left.and.bubble.right`. */
internal val SEND_DM_ICON get() = AppIcons.Forum

/** iOS `ActionsDestructiveSection` Block Sender: `hand.raised`. */
internal val BLOCK_SENDER_ICON get() = AppIcons.FrontHand
