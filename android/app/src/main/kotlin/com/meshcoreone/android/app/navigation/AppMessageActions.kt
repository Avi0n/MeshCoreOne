// AndroidOnly: WP-407 Native message actions preserve original text and call the real services/store.
package com.meshcoreone.android.app.navigation

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.meshcoreone.android.app.container.AppChatsFeature
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.feature.chats.actions.*
import com.meshcoreone.android.feature.chats.composer.ComposerStateHolder
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
    navigation: NavigationCoordinator,
    onReply: (MessageDTO) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val holder = remember(feature, message.id) { MessageActionsStateHolder(feature.actions(message.radioId), scope) }
    var failure by remember { mutableStateOf<Throwable?>(null) }
    val preferences = feature.content?.rendering?.collectAsState()?.value
    ModalBottomSheet(onDismissRequest = onDismiss) {
        MessageActionsRoute(message, holder, null, null,
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
                            MessageAction.SendAgain -> feature.sender(message.radioId).let { sender ->
                                when (conversation) {
                                    is TimelineConversation.Direct -> sender.resendDirect(message.id, conversation.contact)
                                    is TimelineConversation.Channel -> sender.resendChannel(message.id)
                                }
                            }
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
            })
        failure?.let { problem -> Text(com.meshcoreone.android.core.ui.uiString(com.meshcoreone.android.core.ui.UiErrorMapper().message(problem))) }
    }
}
