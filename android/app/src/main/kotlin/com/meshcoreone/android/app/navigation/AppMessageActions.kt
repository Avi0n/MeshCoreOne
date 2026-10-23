// AndroidOnly: WP-407 Native message actions preserve original text and call the real services/store.
package com.meshcoreone.android.app.navigation

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshcoreone.android.app.container.AppChatsFeature
import com.meshcoreone.android.core.maps.GeoPoint
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.feature.chats.actions.*
import com.meshcoreone.android.feature.chats.composer.ComposerStateHolder
import com.meshcoreone.android.feature.chats.composer.ComposerLocationSource
import com.meshcoreone.android.feature.chats.timeline.ChatViewModel
import com.meshcoreone.android.feature.chats.timeline.TimelineConversation
import kotlinx.coroutines.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppMessageActions(
    feature: AppChatsFeature,
    message: MessageDTO,
    conversation: TimelineConversation,
    timeline: ChatViewModel,
    composer: ComposerStateHolder,
    location: ComposerLocationSource,
    navigation: NavigationCoordinator,
    onReply: (MessageDTO) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val holder = remember(feature, message.radioId, message.id) { MessageActionsStateHolder(feature.actions(message.radioId), scope) }
    val deviceFlow = remember(feature, message.radioId, scope) { feature.pathDevice(message.radioId, scope) }
    val device by deviceFlow.collectAsStateWithLifecycle()
    val path = messagePathLocation(message.radioId, device, location.currentCoordinate, location.isAuthorized)
    var failure by remember { mutableStateOf<Throwable?>(null) }
    var showEmojiPicker by remember { mutableStateOf(false) }
    var selectedReaction by remember { mutableStateOf<String?>(null) }
    val emojiPicker = remember(feature, scope) { feature.content?.emojiPicker(scope) }
    val preferences = feature.content?.rendering?.collectAsState()?.value
    LaunchedEffect(selectedReaction) {
        val emoji = selectedReaction ?: return@LaunchedEffect
        try {
            feature.react(message, emoji, conversation)
            feature.content?.recordReaction(emoji)
            onDismiss()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (problem: Exception) { feature.report("Message reaction failed", problem); failure = problem }
        finally { selectedReaction = null }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        if (emojiPicker != null) TextButton(onClick = { showEmojiPicker = true }) {
            Text(androidx.compose.ui.res.stringResource(com.meshcoreone.android.core.l10n.generated.AppChatsStrings.reactionsMoreEmojis))
        }
        MessageActionsRoute(message, holder, path.localDevice, path.userLocation,
            preferences?.get(com.meshcoreone.android.core.datastore.AppStorageKey.recentReactionEmojis)
                ?.ifEmpty { com.meshcoreone.android.feature.chats.composer.RecentEmojis.DEFAULTS }
                ?: com.meshcoreone.android.feature.chats.composer.RecentEmojis.DEFAULTS,
            onAction = { action ->
                scope.launch {
                    try {
                        when (action) {
                            MessageAction.Copy -> clipboard.setText(AnnotatedString(message.text))
                            MessageAction.Reply -> {
                                onReply(message)
                                val sender = message.senderNodeName ?: (conversation as? TimelineConversation.Direct)?.contact?.name
                                if (sender != null) {
                                    if (preferences?.get(com.meshcoreone.android.core.datastore.AppStorageKey.replyWithQuote) == true) {
                                        composer.insertShared(com.meshcoreone.android.core.services.rendering.MentionUtilities.buildReplyText(sender, message.text))
                                    } else composer.appendMention(sender)
                                }

                            }
                            MessageAction.SendAgain -> feature.sendAgain(message, conversation)

                            MessageAction.Delete -> { feature.delete(message); timeline.refresh() }
                            is MessageAction.React -> {
                                feature.react(message, action.emoji, conversation)
                                feature.content?.recordReaction(action.emoji)
                            }
                            MessageAction.SendDirectMessage -> {
                                val contact = MessagePathDirectory(feature.actions(message.radioId).contacts(message.radioId))
                                    .senderContact(message)
                                    ?: throw IllegalStateException("Sender contact cannot be resolved")
                                navigation.navigateToChat(contact)
                            }
                            MessageAction.BlockSender -> holder.blockSender(message, emptySet())
                            MessageAction.Translate -> error("Message translation is not registered")
                        }
                        if (action != MessageAction.BlockSender) onDismiss()
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (problem: Exception) { feature.report("Message action failed", problem); failure = problem }
                }
            },
            localDeviceName = path.localDeviceName,
        )
        failure?.let { problem -> Text(com.meshcoreone.android.core.ui.uiString(com.meshcoreone.android.core.ui.UiErrorMapper().message(problem))) }
    }
    if (showEmojiPicker && emojiPicker != null) {
        com.meshcoreone.android.feature.chats.composer.ui.EmojiPickerSheet(
            emojiPicker,
            onDismiss = { showEmojiPicker = false },
            onPick = { emoji ->
                emojiPicker.markAsFrequentlyUsed(emoji)
                showEmojiPicker = false
                selectedReaction = emoji
            },
        )
    }
}

internal data class MessagePathLocation(
    val localDevice: PathEndpoint?,
    val userLocation: Coordinate?,
    val localDeviceName: String?,
)

internal fun messagePathLocation(
    radio: RadioId,
    device: DeviceDTO?,
    phone: Coordinate?,
    authorized: Boolean,
): MessagePathLocation {
    val matchingDevice = device?.takeIf { it.radioId == radio }
    val local = matchingDevice?.takeIf { it.hasLocation }
    val endpoint = local?.let { PathEndpoint(it.id, it.publicKey, it.nodeName, GeoPoint(it.latitude, it.longitude)) }
    val best = phone?.takeIf { authorized && it.isValidFix }
        ?: local?.let { Coordinate(it.latitude, it.longitude) }
    return MessagePathLocation(endpoint, best, matchingDevice?.nodeName)
}
