package one.meshcore.shared.chat.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.chat.ChatFilter
import one.meshcore.shared.chat.Conversation
import one.meshcore.shared.chat.ConversationListViewModel
import one.meshcore.shared.chat.NewChatViewModel
import one.meshcore.shared.chat.clearChannelDeleteFailure
import one.meshcore.shared.chat.clearError
import one.meshcore.shared.chat.deleteConversation
import one.meshcore.shared.chat.filteredOthers
import one.meshcore.shared.chat.filteredPinned
import one.meshcore.shared.chat.setFilter
import one.meshcore.shared.chat.setSearchText
import one.meshcore.shared.chat.toggleMute
import one.meshcore.shared.chat.togglePinned
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.navigation.ChatRoute
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_alert_leave_room_confirm
import one.meshcore.shared.resources.chats_alert_leave_room_message
import one.meshcore.shared.resources.chats_alert_leave_room_title
import one.meshcore.shared.resources.chats_channel_default_name
import one.meshcore.shared.resources.chats_channel_info_delete_failed_title
import one.meshcore.shared.resources.chats_common_cancel
import one.meshcore.shared.resources.chats_common_ok
import one.meshcore.shared.resources.chats_compose_new_channel
import one.meshcore.shared.resources.chats_compose_new_chat
import one.meshcore.shared.resources.chats_compose_new_message
import one.meshcore.shared.resources.chats_search_placeholder
import one.meshcore.shared.resources.chats_title
import one.meshcore.shared.resources.localizable_common_try_again
import one.meshcore.shared.ui.AppRootTopBar
import one.meshcore.shared.ui.ConfirmAlertDialog
import one.meshcore.shared.ui.formatTemplate
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.menu.McFabMenu
import one.meshcore.shared.ui.menu.McFabMenuItem
import org.jetbrains.compose.resources.stringResource

/**
 * The conversation-list screen: search and filter chips as the first lazy items so they scroll
 * with the rows (iOS `ConversationListContent`), then pinned and other section headings when anything is pinned,
 * with per-row long-press actions (delete, mute/unmute, pin/unpin), a deleting-row
 * spinner overlay, and per-filter empty states. Kotlin port of the iOS `ChatsView` /
 * `ConversationListContent`; conversation navigation is delegated to [onConversationTap].
 *
 * The screen is driven entirely by [viewModel]; [isReady] gates the row actions and [nowSeconds] renders
 * each row's relative timestamp. Primary create is a single FAB menu (new chat / new channel); radio
 * status is injected by [AppRootTopBar]. A picked contact routes through [onStartChat] to open its DM.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationListScreen(
    viewModel: ConversationListViewModel,
    newChatViewModel: NewChatViewModel,
    newChatServiceOwner: ContactService?,
    newChatRadioId: Uuid?,
    isReady: Boolean,
    nowSeconds: Long,
    onConversationTap: (Conversation) -> Unit,
    onStartChat: (ContactDto) -> Unit,
    modifier: Modifier = Modifier,
    selectedRoute: ChatRoute? = null,
    onNewChannel: () -> Unit = {},
    onConversationDeleted: (Conversation) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val unnamedTemplate = stringResource(Res.string.chats_channel_default_name)
    val unnamedChannelName = remember(unnamedTemplate) {
        { index: Int -> formatTemplate(unnamedTemplate, listOf(index)) }
    }
    val pinned = remember(state, unnamedTemplate) { viewModel.filteredPinned(state, unnamedChannelName) }
    val others = remember(state, unnamedTemplate) { viewModel.filteredOthers(state, unnamedChannelName) }
    var showNewChat by remember { mutableStateOf(false) }
    var conversationToConfirmDelete by remember { mutableStateOf<Conversation?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            AppRootTopBar(title = stringResource(Res.string.chats_title))
        },
        floatingActionButton = {
            ComposeFabMenu(
                onNewChat = { showNewChat = true },
                onNewChannel = onNewChannel,
            )
        },
    ) { innerPadding ->
        ListBody(
            modifier = Modifier.padding(innerPadding),
            searchText = state.searchText,
            onSearchChange = viewModel::setSearchText,
            searchPlaceholder = stringResource(Res.string.chats_search_placeholder),
            filter = state.filter,
            isSearching = state.isSearching,
            onSelectFilter = viewModel::setFilter,
            state = state,
            pinned = pinned,
            others = others,
            isReady = isReady,
            nowSeconds = nowSeconds,
            viewModel = viewModel,
            selectedRoute = selectedRoute,
            onConversationTap = onConversationTap,
            onClearFilter = { viewModel.setFilter(ChatFilter.ALL) },
            onDelete = { conversation ->
                if (conversation.requiresDeleteConfirmation) {
                    conversationToConfirmDelete = conversation
                } else {
                    scope.launch { deleteAndNotify(viewModel, conversation, onConversationDeleted) }
                }
            },
            onToggleMute = { conversation -> scope.launch { viewModel.toggleMute(conversation) } },
            onTogglePinned = { conversation -> scope.launch { viewModel.togglePinned(conversation) } },
        )
    }

    conversationToConfirmDelete?.let { conversation ->
        LeaveRoomConfirmationDialog(
            onDismiss = { conversationToConfirmDelete = null },
            onConfirm = {
                conversationToConfirmDelete = null
                scope.launch { deleteAndNotify(viewModel, conversation, onConversationDeleted) }
            },
        )
    }

    state.errorMessage?.let { message ->
        ThreadErrorDialog(message = message, onDismiss = viewModel::clearError)
    }

    state.channelDeleteFailure?.let { failure ->
        ConfirmAlertDialog(
            title = stringResource(Res.string.chats_channel_info_delete_failed_title),
            message = failure.message,
            confirmLabel = stringResource(Res.string.localizable_common_try_again),
            dismissLabel = stringResource(Res.string.chats_common_ok),
            onConfirm = {
                viewModel.clearChannelDeleteFailure()
                scope.launch {
                    deleteAndNotify(
                        viewModel,
                        Conversation.Channel(failure.channel),
                        onConversationDeleted,
                    )
                }
            },
            onDismiss = viewModel::clearChannelDeleteFailure,
        )
    }

    if (showNewChat) {
        NewChatSheet(
            viewModel = newChatViewModel,
            serviceOwner = newChatServiceOwner,
            radioId = newChatRadioId,
            onSelect = { contact ->
                showNewChat = false
                onStartChat(contact)
            },
            onDismiss = { showNewChat = false },
        )
    }
}

/**
 * Confirms leaving a room before the irreversible delete runs, carrying the iOS alert's copy: it warns
 * that every room message goes and the backing contact is removed.
 */
@Composable
private fun LeaveRoomConfirmationDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    ConfirmAlertDialog(
        title = stringResource(Res.string.chats_alert_leave_room_title),
        message = stringResource(Res.string.chats_alert_leave_room_message),
        confirmLabel = stringResource(Res.string.chats_alert_leave_room_confirm),
        dismissLabel = stringResource(Res.string.chats_common_cancel),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        confirmColor = MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun ComposeFabMenu(onNewChat: () -> Unit, onNewChannel: () -> Unit) {
    val label = stringResource(Res.string.chats_compose_new_message)
    McFabMenu(
        expandedContentDescription = label,
        collapsedIcon = AppIcons.Edit,
        items = listOf(
            McFabMenuItem(
                label = stringResource(Res.string.chats_compose_new_chat),
                icon = AppIcons.Chat,
                onClick = onNewChat,
            ),
            McFabMenuItem(
                label = stringResource(Res.string.chats_compose_new_channel),
                icon = AppIcons.Tag,
                onClick = onNewChannel,
            ),
        ),
    )
}

/** Deletes [conversation] and notifies only when the row was actually removed. */
private suspend fun deleteAndNotify(
    viewModel: ConversationListViewModel,
    conversation: Conversation,
    onConversationDeleted: (Conversation) -> Unit,
) {
    if (viewModel.deleteConversation(conversation)) {
        onConversationDeleted(conversation)
    }
}
