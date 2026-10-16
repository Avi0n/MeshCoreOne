package one.meshcore.shared.chat.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import one.meshcore.shared.chat.ChatFilter
import one.meshcore.shared.chat.Conversation
import one.meshcore.shared.chat.ConversationListState
import one.meshcore.shared.chat.ConversationListViewModel
import one.meshcore.shared.chat.ConversationSection
import one.meshcore.shared.chat.conversationSections
import one.meshcore.shared.chat.matchesChatRoute
import one.meshcore.shared.navigation.ChatRoute
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.android_chats_search_no_results_description
import one.meshcore.shared.resources.android_chats_search_no_results_title
import one.meshcore.shared.resources.chats_action_delete
import one.meshcore.shared.resources.chats_action_mute
import one.meshcore.shared.resources.chats_action_pin
import one.meshcore.shared.resources.chats_action_unmute
import one.meshcore.shared.resources.chats_action_unpin
import one.meshcore.shared.resources.chats_empty_state_no_channels_description
import one.meshcore.shared.resources.chats_empty_state_no_channels_title
import one.meshcore.shared.resources.chats_empty_state_no_conversations_description
import one.meshcore.shared.resources.chats_empty_state_no_conversations_title
import one.meshcore.shared.resources.chats_empty_state_no_direct_messages_description
import one.meshcore.shared.resources.chats_empty_state_no_direct_messages_title
import one.meshcore.shared.resources.chats_empty_state_no_rooms_description
import one.meshcore.shared.resources.chats_empty_state_no_rooms_title
import one.meshcore.shared.resources.chats_empty_state_no_unread_description
import one.meshcore.shared.resources.chats_empty_state_no_unread_title
import one.meshcore.shared.resources.chats_filter_all
import one.meshcore.shared.resources.chats_filter_channels
import one.meshcore.shared.resources.chats_filter_clear
import one.meshcore.shared.resources.chats_filter_direct_messages
import one.meshcore.shared.resources.chats_filter_rooms
import one.meshcore.shared.resources.chats_filter_title
import one.meshcore.shared.resources.chats_filter_unread
import one.meshcore.shared.ui.AppSearchField
import one.meshcore.shared.ui.EmptyMessageColumn
import one.meshcore.shared.ui.ScrollingFilterChips
import one.meshcore.shared.ui.SwipeActionRow
import one.meshcore.shared.ui.SwipeRowAction
import one.meshcore.shared.ui.expressiveListItem
import one.meshcore.shared.ui.filterBarVisible
import one.meshcore.shared.ui.formattedStringResource
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.menu.McActionSheet
import one.meshcore.shared.ui.menu.McActionSheetItem
import one.meshcore.shared.ui.menu.McMenuRole
import one.meshcore.shared.ui.selectedRowHighlight
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterRow(selected: ChatFilter, isSearching: Boolean, onSelect: (ChatFilter) -> Unit) {
    if (!filterBarVisible(isSearching)) return
    val filterTitle = stringResource(Res.string.chats_filter_title)
    ScrollingFilterChips(
        entries = ChatFilter.entries,
        selected = selected,
        spokenLabel = filterTitle,
        onSelect = onSelect,
    ) { stringResource(it.labelResource()) }
}

@Composable
internal fun ListBody(
    searchText: String,
    onSearchChange: (String) -> Unit,
    searchPlaceholder: String,
    filter: ChatFilter,
    isSearching: Boolean,
    onSelectFilter: (ChatFilter) -> Unit,
    state: ConversationListState,
    pinned: List<Conversation>,
    others: List<Conversation>,
    isReady: Boolean,
    nowSeconds: Long,
    viewModel: ConversationListViewModel,
    selectedRoute: ChatRoute?,
    onConversationTap: (Conversation) -> Unit,
    onClearFilter: () -> Unit,
    onDelete: (Conversation) -> Unit,
    onToggleMute: (Conversation) -> Unit,
    onTogglePinned: (Conversation) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sections = remember(pinned, others) { conversationSections(pinned, others) }
    Column(modifier = modifier.fillMaxSize()) {
        AppSearchField(
            value = searchText,
            onValueChange = onSearchChange,
            placeholder = searchPlaceholder,
        )
        FilterRow(
            selected = filter,
            isSearching = isSearching,
            onSelect = onSelectFilter,
        )
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxSize(),
            contentPadding = PaddingValues(bottom = FAB_CLEARANCE),
        ) {
            when {
                !state.hasLoadedOnce -> item {
                    Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                sections.isEmpty() -> item {
                    Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                        if (isSearching) {
                            SearchEmptyState(query = searchText)
                        } else {
                            EmptyState(filter = state.filter, onClearFilter = onClearFilter)
                        }
                    }
                }
                else -> sections.forEach { section ->
                    when (section) {
                        is ConversationSection.Header -> item(key = section.kind) {
                            ConversationSectionHeader(section.kind)
                        }
                        is ConversationSection.Row -> {
                            val conversation = section.conversation
                            item(key = conversation.id.value) {
                                ConversationListItem(
                                    conversation = conversation,
                                    viewModel = viewModel,
                                    previewOverride = state.previews[conversation.id],
                                    hasFailedSend = conversation.id in state.failedSendConversationIDs,
                                    isReady = isReady,
                                    isDeletePending = state.isDeletePending(conversation.id),
                                    isDeleting = conversation.id in state.deletingIds,
                                    nowSeconds = nowSeconds,
                                    isSelected = selectedRoute?.let { conversation.matchesChatRoute(it) } == true,
                                    onTap = { onConversationTap(conversation) },
                                    onDelete = { onDelete(conversation) },
                                    onToggleMute = { onToggleMute(conversation) },
                                    onTogglePinned = { onTogglePinned(conversation) },
                                    modifier = expressiveListItem(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * One interactive row: a tap navigates, a long-press opens the action menu. A radio-backed delete in
 * flight dims the row under a spinner. Mirrors the iOS row's `.contextMenu` and `deletingRowOverlay`.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationListItem(
    conversation: Conversation,
    viewModel: ConversationListViewModel,
    previewOverride: String?,
    hasFailedSend: Boolean,
    isReady: Boolean,
    isDeletePending: Boolean,
    isDeleting: Boolean,
    nowSeconds: Long,
    isSelected: Boolean,
    onTap: () -> Unit,
    onDelete: () -> Unit,
    onToggleMute: () -> Unit,
    onTogglePinned: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val pinLabel =
        stringResource(
            if (conversation.isPinned) Res.string.chats_action_unpin else Res.string.chats_action_pin,
        )
    val muteLabel =
        stringResource(if (conversation.isMuted) Res.string.chats_action_unmute else Res.string.chats_action_mute)
    val deleteLabel = stringResource(Res.string.chats_action_delete)
    Box(modifier = modifier.selectedRowHighlight(isSelected)) {
        SwipeActionRow(
            leadingActions = listOf(
                SwipeRowAction(
                    label = pinLabel,
                    icon = AppIcons.PushPin,
                    enabled = true,
                    onClick = onTogglePinned,
                ),
            ),
            trailingActions = listOf(
                SwipeRowAction(
                    label = muteLabel,
                    icon = if (conversation.isMuted) AppIcons.Notifications else AppIcons.NotificationsOff,
                    enabled = isReady,
                    onClick = onToggleMute,
                ),
                SwipeRowAction(
                    label = deleteLabel,
                    icon = AppIcons.Delete,
                    enabled = isReady && !isDeletePending,
                    destructive = true,
                    onClick = onDelete,
                ),
            ),
        ) { swipeModifier, consumeOpenTap ->
            ConversationRow(
                conversation = conversation,
                viewModel = viewModel,
                isConnectedReady = isReady,
                nowSeconds = nowSeconds,
                previewOverride = previewOverride,
                hasFailedSend = hasFailedSend,
                modifier = swipeModifier.combinedClickable(
                    onClick = { if (!consumeOpenTap()) onTap() },
                    onLongClick = { menuOpen = true },
                ),
            )
        }
        if (isDeleting) {
            Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }
        ConversationActionMenu(
            conversation = conversation,
            expanded = menuOpen,
            isReady = isReady,
            isDeletePending = isDeletePending,
            onDismiss = { menuOpen = false },
            onDelete = onDelete,
            onToggleMute = onToggleMute,
            onTogglePinned = onTogglePinned,
        )
    }
}

@Composable
private fun ConversationActionMenu(
    conversation: Conversation,
    expanded: Boolean,
    isReady: Boolean,
    isDeletePending: Boolean,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onToggleMute: () -> Unit,
    onTogglePinned: () -> Unit,
) {
    if (!expanded) return
    val muteLabel = if (conversation.isMuted) Res.string.chats_action_unmute else Res.string.chats_action_mute
    val muteIcon = if (conversation.isMuted) AppIcons.Notifications else AppIcons.NotificationsOff
    val pinLabel = if (conversation.isPinned) {
        Res.string.chats_action_unpin
    } else {
        Res.string.chats_action_pin
    }
    McActionSheet(onDismiss = onDismiss) {
        McActionSheetItem(
            text = stringResource(muteLabel),
            leadingIcon = muteIcon,
            enabled = isReady,
            onClick = {
                onDismiss()
                onToggleMute()
            },
        )
        McActionSheetItem(
            text = stringResource(pinLabel),
            leadingIcon = AppIcons.PushPin,
            enabled = true,
            onClick = {
                onDismiss()
                onTogglePinned()
            },
        )
        McActionSheetItem(
            text = stringResource(Res.string.chats_action_delete),
            leadingIcon = AppIcons.Delete,
            role = McMenuRole.Destructive,
            enabled = isReady && !isDeletePending,
            onClick = {
                onDismiss()
                onDelete()
            },
        )
    }
}

@Composable
private fun SearchEmptyState(query: String) {
    EmptyMessageColumn(
        title = stringResource(Res.string.android_chats_search_no_results_title),
        icon = AppIcons.Search,
        description = formattedStringResource(Res.string.android_chats_search_no_results_description, query),
        modifier = Modifier.padding(24.dp),
    )
}

@Composable
private fun EmptyState(filter: ChatFilter, onClearFilter: () -> Unit) {
    val (title, description) = filter.emptyStateResources()
    EmptyMessageColumn(
        title = stringResource(title),
        icon = filter.emptyStateIcon(),
        description = stringResource(description),
        modifier = Modifier.fillMaxWidth().padding(24.dp),
    ) {
        if (filter != ChatFilter.ALL) {
            Button(
                onClick = onClearFilter,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.chats_filter_clear))
            }
        }
    }
}

private fun ChatFilter.labelResource(): StringResource = when (this) {
    ChatFilter.ALL -> Res.string.chats_filter_all
    ChatFilter.UNREAD -> Res.string.chats_filter_unread
    ChatFilter.DIRECT_MESSAGES -> Res.string.chats_filter_direct_messages
    ChatFilter.CHANNELS -> Res.string.chats_filter_channels
    ChatFilter.ROOMS -> Res.string.chats_filter_rooms
}

private fun ChatFilter.emptyStateResources(): Pair<StringResource, StringResource> = when (this) {
    ChatFilter.ALL ->
        Res.string.chats_empty_state_no_conversations_title to Res.string.chats_empty_state_no_conversations_description
    ChatFilter.UNREAD ->
        Res.string.chats_empty_state_no_unread_title to Res.string.chats_empty_state_no_unread_description
    ChatFilter.DIRECT_MESSAGES ->
        Res.string.chats_empty_state_no_direct_messages_title to
            Res.string.chats_empty_state_no_direct_messages_description
    ChatFilter.CHANNELS ->
        Res.string.chats_empty_state_no_channels_title to Res.string.chats_empty_state_no_channels_description
    ChatFilter.ROOMS ->
        Res.string.chats_empty_state_no_rooms_title to Res.string.chats_empty_state_no_rooms_description
}

/** iOS `ChatsView.emptyStateMessage` SF Symbols. */
internal fun ChatFilter.emptyStateIcon(): ImageVector = when (this) {
    ChatFilter.ALL -> AppIcons.Chat
    ChatFilter.UNREAD -> AppIcons.CheckCircle
    ChatFilter.DIRECT_MESSAGES -> AppIcons.Person
    ChatFilter.CHANNELS -> AppIcons.Tag
    ChatFilter.ROOMS -> AppIcons.MeetingRoom
}

/** Bottom list inset so the last row scrolls clear of the floating action button. */
private val FAB_CLEARANCE = 88.dp
