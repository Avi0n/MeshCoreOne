package one.meshcore.shared.room.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import one.meshcore.shared.avatar.AvatarCategory
import one.meshcore.shared.backup.RoomMessageDto
import one.meshcore.shared.chat.text.LinkTarget
import one.meshcore.shared.chat.translation.MessageTranslationPhase
import one.meshcore.shared.chat.ui.ChatInputBar
import one.meshcore.shared.chat.ui.NearBottomReporter
import one.meshcore.shared.chat.ui.ScrollToBottomAffordance
import one.meshcore.shared.chat.ui.ThreadErrorDialog
import one.meshcore.shared.contact.ui.NodeAvatar
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.remotenodes_remote_nodes_room_be_first_to_post
import one.meshcore.shared.resources.remotenodes_remote_nodes_room_disconnected_banner
import one.meshcore.shared.resources.remotenodes_remote_nodes_room_disconnected_hint
import one.meshcore.shared.resources.remotenodes_remote_nodes_room_no_messages_yet
import one.meshcore.shared.resources.remotenodes_remote_nodes_room_public_message
import one.meshcore.shared.resources.remotenodes_remote_nodes_room_reconnected
import one.meshcore.shared.resources.remotenodes_remote_nodes_room_view_only_banner
import one.meshcore.shared.resources.remotenodes_remote_nodes_room_view_only_hint
import one.meshcore.shared.room.RoomConversationController
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.isRoomReconnected
import one.meshcore.shared.ui.rememberAccessibilityAnnouncer
import org.jetbrains.compose.resources.stringResource

private val SCREEN_PADDING = 12.dp

private val EMPTY_ROOM_AVATAR_SIZE = 80.dp
private val EMPTY_ROOM_SPACING = 16.dp

/**
 * Room conversation list + composer over [RoomConversationViewModel]. Material messaging density; reverse
 * chronological list like the channel thread. Posts use [ChatInputBar] (unlocked, no share, no mentions).
 * Disconnected and guest/view-only each show a banner with a reconnect affordance instead of the composer;
 * long-press opens the host's message actions sheet.
 */
@Composable
fun RoomConversationScreen(
    viewModel: RoomConversationViewModel,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
    onMessageLongPress: (RoomMessageDto) -> Unit = {},
    onLinkTap: (LinkTarget) -> Unit = {},
    localNodeName: String? = null,
    radioReady: Boolean = true,
    translationPhase: (Uuid) -> MessageTranslationPhase? = { null },
    onTranslate: (RoomMessageDto) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    RoomReconnectionAnnouncer(state.isConnected)

    Column(modifier = modifier.fillMaxSize()) {
        when {
            state.isLoading && !state.hasLoadedOnce -> {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            state.hasLoadedOnce && state.messages.isEmpty() -> {
                Box(Modifier.weight(1f).fillMaxWidth().padding(SCREEN_PADDING), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(EMPTY_ROOM_SPACING),
                    ) {
                        NodeAvatar(AvatarCategory.ROOM, EMPTY_ROOM_AVATAR_SIZE)
                        Text(
                            state.sessionName,
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            stringResource(Res.string.remotenodes_remote_nodes_room_no_messages_yet),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (state.canPost) {
                            Text(
                                stringResource(Res.string.remotenodes_remote_nodes_room_be_first_to_post),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            else -> {
                val bookends = remember(state.messages) {
                    RoomConversationController.incomingBookends(state.messages)
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(
                        state = listState,
                        reverseLayout = true,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(SCREEN_PADDING),
                    ) {
                        items(state.messages.asReversed(), key = { it.id.value }) { message ->
                            val index = state.messages.indexOfFirst { it.id == message.id }
                            RoomMessageRow(
                                message = message,
                                showTimestamp = RoomConversationController.shouldShowTimestamp(index, state.messages),
                                showSenderName = bookends.nameIDs.contains(message.id),
                                showAvatar = bookends.avatarIDs.contains(message.id),
                                onRetry = { viewModel.retry(message.id) },
                                onLongPress = { onMessageLongPress(message) },
                                onLinkTap = onLinkTap,
                                localNodeName = localNodeName,
                                translationPhase = if (message.isFromSelf) {
                                    null
                                } else {
                                    translationPhase(message.id)
                                },
                                onTranslate = { onTranslate(message) },
                            )
                        }
                    }
                    NearBottomReporter(listState, viewModel::setNearBottom)
                    LaunchedEffect(state.scrollToBottomVersion) {
                        if (state.scrollToBottomVersion > 0) {
                            listState.animateScrollToItem(0)
                        }
                    }
                    ScrollToBottomAffordance(
                        unreadCount = state.unreadWhileScrolledUp,
                        onClick = viewModel::requestScrollToBottom,
                        isVisible = !state.isAtBottom,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(SCREEN_PADDING),
                    )
                }
            }
        }

        // Disconnected / view-only banners stay here; ChatInputBar owns nav-bar + IME lift.
        when {
            !state.isConnected -> {
                Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
                    RoomStatusBanner(
                        title = stringResource(Res.string.remotenodes_remote_nodes_room_disconnected_banner),
                        hint = stringResource(Res.string.remotenodes_remote_nodes_room_disconnected_hint),
                        emphasize = true,
                        onAction = onConnect,
                    )
                }
            }
            state.canPost -> {
                ChatInputBar(
                    state = state.composer,
                    isReady = radioReady,
                    isEncrypted = false,
                    placeholder = stringResource(Res.string.remotenodes_remote_nodes_room_public_message),
                    onTextChange = viewModel::setComposingText,
                    onSend = viewModel::send,
                    onFocus = viewModel::requestPinToBottom,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            else -> {
                Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
                    RoomStatusBanner(
                        title = stringResource(Res.string.remotenodes_remote_nodes_room_view_only_banner),
                        hint = stringResource(Res.string.remotenodes_remote_nodes_room_view_only_hint),
                        emphasize = false,
                        onAction = onConnect,
                    )
                }
            }
        }
    }
    state.errorMessage?.let { message ->
        ThreadErrorDialog(message = message, onDismiss = viewModel::clearError)
    }
}

/**
 * Speaks "Room reconnected" on the false-to-true edge of the session's connectivity. The screen replaces
 * the disconnected banner with the composer, which is a different node rather than a changed text, so a
 * live region has nothing to compare against; a user who tapped Connect and moved on hears nothing
 * otherwise, and misses the window to post.
 */
@Composable
private fun RoomReconnectionAnnouncer(isConnected: Boolean) {
    val announcer = rememberAccessibilityAnnouncer()
    val message = stringResource(Res.string.remotenodes_remote_nodes_room_reconnected)
    val previous = remember { mutableStateOf(isConnected) }
    LaunchedEffect(isConnected) {
        val was = previous.value
        previous.value = isConnected
        if (isRoomReconnected(was, isConnected)) announcer.announce(message)
    }
}

@Composable
private fun RoomStatusBanner(title: String, hint: String, emphasize: Boolean, onAction: () -> Unit) {
    // The banner title is the accessibility label; its caption is the hint.
    val spokenLabel = title
    val spokenHint = hint
    val container = if (emphasize) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val onContainer = if (emphasize) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val supporting = if (emphasize) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    ListItem(
        headlineContent = { Text(title, color = onContainer) },
        supportingContent = { Text(hint, color = supporting) },
        leadingContent = {
            Icon(
                imageVector = if (emphasize) ROOM_STATUS_DISCONNECTED_ICON else ROOM_STATUS_VIEW_ONLY_ICON,
                contentDescription = null,
                tint = onContainer,
            )
        },
        colors = ListItemDefaults.colors(containerColor = container),
        modifier = Modifier
            .fillMaxWidth()
            .padding(SCREEN_PADDING)
            .clickable(onClick = onAction)
            .semantics(mergeDescendants = true) {
                contentDescription = spokenLabel
                stateDescription = spokenHint
                role = Role.Button
            },
    )
}

internal val ROOM_STATUS_DISCONNECTED_ICON get() = AppIcons.Warning

internal val ROOM_STATUS_VIEW_ONLY_ICON get() = AppIcons.Visibility
