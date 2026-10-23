// AndroidOnly: WP-407 App composition supplies notification anchors through the public timeline presentation contract.
package com.meshcoreone.android.app.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.ui.UiErrorMapper
import com.meshcoreone.android.core.ui.uiString
import com.meshcoreone.android.feature.chats.composer.*
import com.meshcoreone.android.feature.chats.composer.ui.*
import com.meshcoreone.android.feature.chats.timeline.*
import com.meshcoreone.android.feature.chats.timeline.ui.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
internal fun AppChatConversationScreen(
    timeline: ChatViewModel,
    composer: ComposerStateHolder,
    placeholder: String,
    links: ConversationLinkActions,
    currentUserName: String?,
    onPickContact: () -> Unit,
    scrollTo: UUID?,
    onScrollConsumed: (UUID) -> Unit,
    report: (String, Throwable?) -> Unit,
    attachments: @Composable (MessageDTO, Boolean) -> Unit,
    messageAccessibilityLabel: ((MessageDTO) -> String)? = null,
    emojiPicker: EmojiPickerStateHolder? = null,
    emojiStatus: @Composable () -> Unit = {},
    incomingAvatar: @Composable (MessageDTO) -> Unit = DefaultIncomingAvatar,
) {
    val configuration = MessageBubbleConfiguration.forConversation(timeline.conversation)
    val state by timeline.state.collectAsState()
    val draft by composer.state.collectAsState()
    val scope = rememberCoroutineScope()
    var picker by remember { mutableStateOf<MentionPickerContext?>(null) }
    var failure by remember { mutableStateOf<Throwable?>(null) }
    val dispatcher = remember(links) {
        MessageLinkDispatcher(links.contacts, links.radioId, links.connectedDeviceName,
            onMention = {
                when (it) {
                    is MentionTapEvaluator.Outcome.Navigate -> links.navigateToContact(it.contact)
                    is MentionTapEvaluator.Outcome.Picker -> picker = it.context
                }
            }, route = links.route, openExternal = links.openExternal)
    }
    LaunchedEffect(timeline) { timeline.start(); timeline.open() }
    LaunchedEffect(timeline, scrollTo) {
        val requested = scrollTo ?: return@LaunchedEffect
        timeline.state.first { it.hasLoadedOnce && !it.isLoading }
        try {
            while (timeline.state.value.messages.none { it.id == requested } && timeline.state.value.hasMoreMessages) {
                timeline.state.first { !it.isLoadingOlder }
                val fetched = timeline.state.value.totalFetchedCount
                timeline.loadOlder()
                timeline.state.value.passiveError?.let { throw it }
                check(timeline.state.value.totalFetchedCount > fetched || !timeline.state.value.hasMoreMessages) {
                    "Older message loading made no progress"
                }
            }
            check(timeline.state.value.messages.any { it.id == requested }) { "Requested message is not in this conversation" }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (problem: Exception) {
            report("Notification message could not be opened", problem)
            failure = problem
            onScrollConsumed(requested)
        }
    }
    val requested = scrollTo?.takeIf { id -> state.messages.any { it.id == id } }
    val presented = if (requested == null) state else state.copy(
        initialAnchor = InitialTimelineAnchor.Message(requested), initialAnchorConsumed = false,
        scrollAnchor = null,
    )
    val callbacks = ChatTimelineCallbacks(
        onLoadOlder = { scope.launch { timeline.loadOlder() } },
        onRetry = { scope.launch { timeline.retry(it.id) } },
        onInitialAnchorConsumed = {
            timeline.consumeInitialAnchor()
            requested?.let(onScrollConsumed)
        },
        onScrollPositionChanged = timeline::updateScrollPosition,
        onJumpToLatest = timeline::jumpToLatest,
        onRetryLoad = { scope.launch { timeline.open() } },
    )
    Column(Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
        Box(Modifier.weight(1f)) {
            ChatTimeline(
                presented, callbacks,
                showSenderName = configuration.showSenderName,
                showsIncomingAvatars = configuration.showsIncomingAvatars,
                incomingAvatar = incomingAvatar,
                messageAccessibilityLabel = messageAccessibilityLabel,
                messageBody = { message, outgoing ->
                    Column {
                        MessageBody(message.text, outgoing, currentUserName, onLink = { dispatcher.dispatch(it) })
                        attachments(message, outgoing)
                    }
                },
            )
            MentionSuggestionList(draft.mentionSuggestions, composer::selectMention,
                Modifier.align(Alignment.BottomCenter).padding(horizontal = 12.dp, vertical = 8.dp))
        }
        emojiStatus()
        ComposerRoute(composer, placeholder, onPickContact, emojiPicker = emojiPicker)
    }
    picker?.let { context ->
        MentionPickerSheet(context, onSelect = { picker = null; links.navigateToContact(it) }, onDismiss = { picker = null })
    }
    failure?.let { problem ->
        AlertDialog(onDismissRequest = { failure = null }, text = { Text(uiString(UiErrorMapper().message(problem))) },
            confirmButton = { TextButton(onClick = { failure = null }) {
                Text(androidx.compose.ui.res.stringResource(com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings.commonOk))
            } })
    }
}
