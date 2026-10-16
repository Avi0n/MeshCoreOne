package one.meshcore.shared.chat

import kotlinx.coroutines.flow.update
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.room.RoomServerEvent
import one.meshcore.shared.room.shouldRefreshFailedSendIndicators

/**
 * The filtered pins, through the pure [filtered] port. Recomputed in composition as the collected
 * state changes; a plain function (not a derived flow) keeps the order the tests assert independent of
 * scheduler timing.
 */
fun ConversationListViewModel.filteredPinned(
    state: ConversationListState,
    unnamedChannelName: (Int) -> String,
): List<Conversation> = state.snapshot.pinned.filtered(state.filter, state.searchText, unnamedChannelName)

/** The filtered non-pinned conversations, through the pure [filtered] port. */
fun ConversationListViewModel.filteredOthers(
    state: ConversationListState,
    unnamedChannelName: (Int) -> String,
): List<Conversation> = state.snapshot.others.filtered(state.filter, state.searchText, unnamedChannelName)

/** The cached last-message preview for [id], or `null` when none is cached. */
fun ConversationListViewModel.previewText(id: Uuid): String? = listState.value.previews[id]

fun ConversationListViewModel.setSearchText(text: String) = listState.update { it.copy(searchText = text) }

fun ConversationListViewModel.setFilter(filter: ChatFilter) = listState.update { it.copy(filter = filter) }

fun ConversationListViewModel.clearError() = listState.update {
    it.copy(errorMessage = null, channelDeleteFailure = null)
}

fun ConversationListViewModel.clearChannelDeleteFailure() = listState.update { it.copy(channelDeleteFailure = null) }

/** True when the conversation row for [id] should show the failed-send badge. */
fun ConversationListViewModel.conversationHasFailedSend(id: Uuid): Boolean =
    listState.value.failedSendConversationIDs.contains(id)

/**
 * Whether a DM/channel status event should requery failed-send badges. Mirrors iOS
 * `shouldRefreshFailedSendIndicators(for:)`.
 */
fun ConversationListViewModel.shouldRefreshFailedSendIndicators(event: MessageStatusEvent): Boolean =
    event.shouldRefreshFailedSendIndicators(listState.value.failedSendConversationIDs.isNotEmpty())

/**
 * Whether a room-server event should requery failed-send badges. Mirrors iOS
 * `roomMessageFailed` / `roomMessageStatusUpdated`.
 */
fun ConversationListViewModel.shouldRefreshFailedSendIndicators(event: RoomServerEvent): Boolean =
    event.shouldRefreshFailedSendIndicators(listState.value.failedSendConversationIDs.isNotEmpty())
