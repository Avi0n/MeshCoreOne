// PortedFrom: MC1/Views/Chats/ChatConversationMessagesContent.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/Components/ChatTiledView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/Components/ScrollToBottomButton.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.chats.timeline.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.ui.sharedTouchTarget
import com.meshcoreone.android.feature.chats.timeline.ChatTimelineState
import com.meshcoreone.android.feature.chats.timeline.ChatViewModel
import com.meshcoreone.android.feature.chats.timeline.InitialTimelineAnchor
import com.meshcoreone.android.feature.chats.timeline.TimelineRow
import com.meshcoreone.android.feature.chats.timeline.TimelineScrollAnchor
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.launch

/** Route wrapper: retains the supplied screen owner and performs no radio/session work. */
@Composable
fun ChatTimelineRoute(
    viewModel: ChatViewModel,
    modifier: Modifier = Modifier,
    messageBody: @Composable (MessageDTO, Boolean) -> Unit = DefaultMessageBody,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val callbacks = remember(viewModel, scope) {
        ChatTimelineCallbacks(
            onLoadOlder = { scope.launch { viewModel.loadOlder() } },
            onRetry = { message -> scope.launch { viewModel.retry(message.id) } },
            onInitialAnchorConsumed = viewModel::consumeInitialAnchor,
            onScrollPositionChanged = viewModel::updateScrollPosition,
            onJumpToLatest = viewModel::jumpToLatest,
            onRetryLoad = { scope.launch { viewModel.open() } },
        )
    }
    LaunchedEffect(viewModel) {
        viewModel.start()
        viewModel.open()
    }
    ChatTimeline(state, callbacks, modifier, messageBody = messageBody)
}

/** Plain-text body; WP-308 supplies the linkified/mention/preview body through the `messageBody` slot. */
val DefaultMessageBody: @Composable (MessageDTO, Boolean) -> Unit = { message, _ ->
    Text(message.text, style = MaterialTheme.typography.bodyLarge)
}

data class ChatTimelineCallbacks(
    val onLoadOlder: () -> Unit,
    val onRetry: (MessageDTO) -> Unit,
    val onInitialAnchorConsumed: () -> Unit,
    val onScrollPositionChanged: (TimelineScrollAnchor?, Boolean) -> Unit,
    val onJumpToLatest: () -> Unit,
    val onRetryLoad: () -> Unit,
)

@Composable
fun ChatTimeline(
    state: ChatTimelineState,
    callbacks: ChatTimelineCallbacks,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    messageBody: @Composable (MessageDTO, Boolean) -> Unit = DefaultMessageBody,
) {
    val visualRows = state.rows.asReversed()
    val scope = rememberCoroutineScope()
    var restoredSavedAnchor by remember(state.hasLoadedOnce) { mutableStateOf(false) }
    LaunchedEffect(state.initialAnchor, state.initialAnchorConsumed, visualRows) {
        if (state.initialAnchorConsumed || visualRows.isEmpty()) return@LaunchedEffect
        val target = when (val anchor = state.initialAnchor) {
            InitialTimelineAnchor.Latest -> 0
            is InitialTimelineAnchor.Message -> visualRows.indexOfFirst {
                it is TimelineRow.Message && it.message.id == anchor.messageId
            }.coerceAtLeast(0)
            null -> return@LaunchedEffect
        }
        listState.scrollToItem(target)
        callbacks.onInitialAnchorConsumed()
    }
    LaunchedEffect(state.initialAnchorConsumed, state.scrollAnchor, visualRows) {
        if (!state.initialAnchorConsumed || restoredSavedAnchor) return@LaunchedEffect
        val saved = state.scrollAnchor ?: return@LaunchedEffect
        val target = visualRows.indexOfFirst {
            it is TimelineRow.Message && it.message.id == saved.messageId
        }
        if (target >= 0) listState.scrollToItem(target, saved.offset)
        restoredSavedAnchor = true
    }
    LaunchedEffect(listState, visualRows) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.map { it.index } }.collect { visible ->
            val atLatest = visible.any { it == 0 } || visualRows.isEmpty()
            val anchor = listState.firstVisibleItemIndex.let { visualIndex ->
                (visualRows.getOrNull(visualIndex) as? TimelineRow.Message)?.message?.id
            }?.let { TimelineScrollAnchor(it, listState.firstVisibleItemScrollOffset) }
            callbacks.onScrollPositionChanged(anchor, atLatest)
            if (visible.any { it >= visualRows.lastIndex - 3 } && state.hasMoreMessages && !state.isLoadingOlder) {
                callbacks.onLoadOlder()
            }
        }
    }

    Box(modifier.fillMaxSize()) {
        when {
            state.isLoading && !state.hasLoadedOnce -> {
                CircularProgressIndicator(
                    Modifier
                        .size(48.dp)
                        .align(Alignment.Center),
                )
                return@Box
            }
            state.loadError != null && state.messages.isEmpty() -> {
                LoadFailure(callbacks.onRetryLoad, Modifier.align(Alignment.Center))
                return@Box
            }
            state.hasLoadedOnce && state.messages.isEmpty() -> {
                Text(
                    stringResource(AppChatsStrings.chatsRowNoMessages),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
                return@Box
            }
        }
        LazyColumn(
            state = listState,
            reverseLayout = true,
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (state.passiveError != null) {
                item("passive-error") {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(AppChatsStrings.chatsErrorLoadOlderMessagesFailed),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f),
                            )
                            Button(onClick = callbacks.onLoadOlder, modifier = Modifier.sharedTouchTarget()) {
                                Text(stringResource(AppLocalizableStrings.commonTryAgain))
                            }
                        }
                    }
                }
            }
            if (state.isLoadingOlder) {
                item("loading-older") {
                    Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
            items(visualRows, key = TimelineRow::stableKey) { row ->
                when (row) {
                    is TimelineRow.DayDivider -> DayDivider(row)
                    is TimelineRow.UnreadDivider -> UnreadDivider()
                    is TimelineRow.Message -> MessageBubble(
                        row,
                        retrying = row.message.id in state.retryingMessageIds,
                        onRetry = { callbacks.onRetry(row.message) },
                        messageBody = messageBody,
                    )
                }
            }
        }
        if (!state.isAtLatest || state.newMessageCount > 0) {
            val label = stringResource(AppChatsStrings.chatsScrollButtonScrollToBottomAccessibilityLabel)
            ExtendedFloatingActionButton(
                onClick = {
                    scope.launch {
                        listState.animateScrollToItem(0)
                        callbacks.onJumpToLatest()
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
                    .sharedTouchTarget()
                    .semantics { contentDescription = label },
            ) {
                Text(if (state.newMessageCount > 0) "$label (${state.newMessageCount})" else label)
            }
        }
    }
}

@Composable
private fun DayDivider(row: TimelineRow.DayDivider) {
    Text(
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(row.date),
        style = MaterialTheme.typography.labelMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).semantics { heading() },
    )
}

@Composable
private fun UnreadDivider() {
    val label = stringResource(AppChatsStrings.chatsDividerNewMessages)
    Text(
        label,
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelLarge,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .semantics {
                heading()
                contentDescription = label
            },
    )
}

@Composable
private fun MessageBubble(
    row: TimelineRow.Message,
    retrying: Boolean,
    onRetry: () -> Unit,
    messageBody: @Composable (MessageDTO, Boolean) -> Unit,
) {
    val outgoing = row.message.direction == MessageDirection.OUTGOING
    val status = if (outgoing) statusText(row.message) else null
    val accessibilityLabel = buildString {
        if (!outgoing && row.message.isChannelMessage && !row.message.senderNodeName.isNullOrBlank()) {
            append(row.message.senderNodeName)
            append(". ")
        }
        append(row.message.text)
        if (status != null) {
            append(". ")
            append(status)
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = if (row.startsGroup) 6.dp else 1.dp)
            .semantics(mergeDescendants = true) { contentDescription = accessibilityLabel },
        horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = if (outgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium,
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (!outgoing && row.message.isChannelMessage && row.startsGroup && !row.message.senderNodeName.isNullOrBlank()) {
                    Text(row.message.senderNodeName.orEmpty(), style = MaterialTheme.typography.labelMedium)
                }
                messageBody(row.message, outgoing)
                if (outgoing) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(status.orEmpty(), style = MaterialTheme.typography.labelSmall)
                        if (row.message.status == MessageStatus.FAILED) {
                            Button(
                                onClick = onRetry,
                                enabled = !retrying,
                                modifier = Modifier.sharedTouchTarget(),
                            ) {
                                Text(stringResource(AppChatsStrings.chatsMessageStatusRetry))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun statusText(message: MessageDTO): String = stringResource(
    when (message.status) {
        MessageStatus.PENDING, MessageStatus.SENDING -> AppChatsStrings.chatsMessageStatusSending
        MessageStatus.SENT -> if (message.isChannelMessage) {
            AppChatsStrings.chatsMessageStatusSent
        } else {
            AppChatsStrings.chatsMessageStatusSending
        }
        MessageStatus.DELIVERED -> AppChatsStrings.chatsMessageStatusDelivered
        MessageStatus.FAILED -> AppChatsStrings.chatsMessageStatusFailed
        MessageStatus.RETRYING -> AppChatsStrings.chatsMessageStatusRetrying
    },
)

@Composable
private fun LoadFailure(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(AppChatsStrings.chatsErrorLoadConversationsFailed),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onRetry, modifier = Modifier.sharedTouchTarget()) {
            Text(stringResource(AppLocalizableStrings.commonTryAgain))
        }
    }
}
