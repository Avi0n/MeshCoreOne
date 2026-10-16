package one.meshcore.shared.chat.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import one.meshcore.shared.chat.ChatThreadConstants
import one.meshcore.shared.chat.ChatThreadRenderState
import one.meshcore.shared.chat.ImageReference
import one.meshcore.shared.chat.MessageItem
import one.meshcore.shared.chat.ScrollCommand
import one.meshcore.shared.chat.text.LinkTarget
import one.meshcore.shared.chat.translation.MessageTranslationPhase
import one.meshcore.shared.map.MapSnapshotRequest
import one.meshcore.shared.map.MapSnapshotStore
import one.meshcore.shared.map.decodeMapSnapshotImage
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_message_unavailable
import one.meshcore.shared.resources.chats_message_unavailable_accessibility
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun ThreadList(
    reversed: List<MessageItem>,
    state: ChatThreadRenderState,
    listState: LazyListState,
    onLongPress: (MessageItem) -> Unit,
    onRetry: (Uuid) -> Unit,
    onLinkTap: (LinkTarget) -> Unit,
    mapSnapshotStore: MapSnapshotStore?,
    previews: MessagePreviewActions,
    onTapReaction: (MessageItem, String) -> Unit,
    onOpenReactionDetails: (MessageItem) -> Unit,
    translationPhase: (Uuid) -> MessageTranslationPhase?,
    onTranslate: (MessageItem) -> Unit,
    messageAvailable: (Uuid) -> Boolean,
) {
    val mapSnapshotResolver: MapSnapshotImageResolver = remember(mapSnapshotStore) {
        { request: MapSnapshotRequest ->
            mapSnapshotStore?.imageBytes(request)?.let { decodeMapSnapshotImage(it) }
        }
    }
    // Decode stored preview bytes once per reference and cache the bitmap so the decode stays off the compose
    // frame (iOS memoizes the same bytes in the view model). Only successful decodes are cached, so a
    // reference whose bytes have not resolved yet re-attempts on the next rebuild instead of sticking on null.
    val decodedPreviews = remember(previews.previewImageBytes) { mutableMapOf<ImageReference, ImageBitmap>() }
    val imageResolver: PreviewImageResolver = remember(previews.previewImageBytes) {
        { reference ->
            decodedPreviews[reference]
                ?: previews.previewImageBytes(reference)?.let { decodePreviewImageBytes(it) }?.also {
                    decodedPreviews[reference] = it
                }
        }
    }
    // Animated frames render from the same cached bytes as a Coil model; the URL path is unused for previews.
    val gifModelResolver: (ImageReference) -> Any? = remember(previews.previewImageBytes) {
        { reference -> previews.previewImageBytes(reference) }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag(THREAD_LIST_TEST_TAG),
        state = listState,
        reverseLayout = true,
    ) {
        items(items = reversed, key = { it.id.value }, contentType = { CONTENT_TYPE_MESSAGE }) { item ->
            if (threadRowShowsUnavailableCaption(hasDto = messageAvailable(item.id))) {
                UnavailableMessageCaption()
            } else {
                ChatMessageBubble(
                    item = item,
                    onRetry = { onRetry(item.id) },
                    onLinkTap = onLinkTap,
                    imageResolver = imageResolver,
                    gifModelResolver = gifModelResolver,
                    previews = previews,
                    mapSnapshotResolver = mapSnapshotResolver,
                    onRequestMapSnapshot = { request -> mapSnapshotStore?.request(request) },
                    onRetryMapSnapshot = { request ->
                        mapSnapshotStore?.retry(request)
                        mapSnapshotStore?.request(request)
                    },
                    onTapReaction = { emoji -> onTapReaction(item, emoji) },
                    onOpenReactionDetails = { onOpenReactionDetails(item) },
                    translationPhase = if (item.envelope.isOutgoing) {
                        null
                    } else {
                        translationPhase(item.id)
                    },
                    onTranslate = { onTranslate(item) },
                    modifier = Modifier.messageBubbleLongPress(
                        onLongPress = {
                            onLongPress(item)
                        },
                    ),
                )
            }
        }
        // Under reverseLayout the trailing item sits at the visual top, where older history loads.
        if (state.isLoadingOlder) {
            item(key = LOADING_OLDER_KEY, contentType = CONTENT_TYPE_LOADING) {
                Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

/**
 * Tracks the new-messages-divider visibility (jump-to-divider affordance gate) and which unseen
 * self-mentions are currently off-screen (scroll-to-mention affordance), marking each as seen the moment
 * its row scrolls into the visible range. Mirrors iOS `isDividerVisible` / `offscreenMentionIDs` /
 * `onMentionSeen`.
 */
@Composable
internal fun ThreadScrollAffordanceEffects(
    listState: LazyListState,
    state: ChatThreadRenderState,
    onMentionSeen: (Uuid) -> Unit,
    onDividerVisibleChanged: (Boolean) -> Unit,
    onOffscreenMentionsChanged: (List<Uuid>) -> Unit,
) {
    val latestOnMentionSeen by rememberUpdatedState(onMentionSeen)
    val latestOnDividerVisibleChanged by rememberUpdatedState(onDividerVisibleChanged)
    val latestOnOffscreenMentionsChanged by rememberUpdatedState(onOffscreenMentionsChanged)
    LaunchedEffect(listState, state.newMessagesDividerMessageID, state.unseenMentionMessageIDs) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.map { it.key as? String } }
            .distinctUntilChanged()
            .collect { visibleKeys ->
                val dividerId = state.newMessagesDividerMessageID
                latestOnDividerVisibleChanged(dividerId != null && visibleKeys.contains(dividerId.value))
                val unseen = state.unseenMentionMessageIDs
                latestOnOffscreenMentionsChanged(unseen.filterNot { visibleKeys.contains(it.value) })
                unseen.filter { visibleKeys.contains(it.value) }.forEach(latestOnMentionSeen)
            }
    }
}

/** Reports whether the newest rows are in view, driving the auto-scroll gate (spec §9.5). */
@Composable
internal fun NearBottomReporter(listState: LazyListState, onChanged: (Boolean) -> Unit) {
    val nearBottom by remember(listState) {
        derivedStateOf { listState.firstVisibleItemIndex <= ChatThreadConstants.NEAR_BOTTOM_ITEM_THRESHOLD }
    }
    val latestOnChanged by rememberUpdatedState(onChanged)
    LaunchedEffect(listState) {
        snapshotFlow { nearBottom }.distinctUntilChanged().collect { latestOnChanged(it) }
    }
}

/** Fires [onNearTop] once the visual top (logical end under reverseLayout) comes within the prefetch band. */
@Composable
internal fun NearTopPaginationTrigger(
    listState: LazyListState,
    hasMore: Boolean,
    isLoadingOlder: Boolean,
    onNearTop: () -> Unit,
) {
    val nearTop by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= info.totalItemsCount - 1 - ChatThreadConstants.NEAR_TOP_PREFETCH_DISTANCE
        }
    }
    val latestHasMore by rememberUpdatedState(hasMore)
    val latestIsLoadingOlder by rememberUpdatedState(isLoadingOlder)
    val latestOnNearTop by rememberUpdatedState(onNearTop)
    LaunchedEffect(listState) {
        snapshotFlow { nearTop }.distinctUntilChanged().collect { atTop ->
            if (atTop && latestHasMore && !latestIsLoadingOlder) latestOnNearTop()
        }
    }
}

/** Runs a one-shot scroll when the state's scroll command token changes (state-driven, no delays). */
@Composable
internal fun ScrollCommandEffect(listState: LazyListState, command: ScrollCommand?, items: List<MessageItem>) {
    LaunchedEffect(command?.token) {
        when (command) {
            is ScrollCommand.ToBottom -> listState.animateScrollToItem(0)
            is ScrollCommand.ToMessage -> {
                val chronological = items.indexOfFirst { it.id == command.messageID }
                if (chronological >= 0) {
                    val index = items.size - 1 - chronological
                    if (command.animated) listState.animateScrollToItem(index) else listState.scrollToItem(index)
                }
            }
            null -> Unit
        }
    }
}

/** iOS `MessageBubbleView` paints the unavailable caption when `resolver.message(item)` is nil. */
internal fun threadRowShowsUnavailableCaption(hasDto: Boolean): Boolean = !hasDto

@Composable
private fun UnavailableMessageCaption() {
    val accessibility = stringResource(Res.string.chats_message_unavailable_accessibility)
    Text(
        text = stringResource(Res.string.chats_message_unavailable),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics { contentDescription = accessibility },
    )
}

/** Test tag for the timeline list, used by the Pixel-5 scroll benchmark to target fling gestures. */
const val THREAD_LIST_TEST_TAG = "chat-thread-list"

private const val CONTENT_TYPE_MESSAGE = "message"
private const val CONTENT_TYPE_LOADING = "loading-older"
private const val LOADING_OLDER_KEY = "loading-older"
