import MC1Services
import SwiftUI

struct ChatsSplitSidebarContent: View {
  let viewModel: ChatViewModel
  let filteredFavorites: [Conversation]
  let filteredOthers: [Conversation]
  let emptyStateMessage: (title: String, description: String, systemImage: String)
  let hasLoadedOnce: Bool
  let selectedRoute: ChatRoute?

  @Binding var selectedFilter: ChatFilter
  @Binding var searchText: String
  @Binding var showingNewChat: Bool
  @Binding var showingChannelOptions: Bool
  @Binding var lastSelectedRoomIsConnected: Bool?

  let onSelect: (ChatRoute) -> Void
  let onDeleteConversation: (Conversation) -> Void
  let onHandlePendingNavigation: () -> Void
  let onHandlePendingChannelNavigation: () -> Void
  let onHandlePendingRoomNavigation: () -> Void
  let onAnnounceOfflineStateIfNeeded: () -> Void

  var body: some View {
    ConversationListContent(
      viewModel: viewModel,
      favoriteConversations: filteredFavorites,
      otherConversations: filteredOthers,
      selectedFilter: $selectedFilter,
      hasLoadedOnce: hasLoadedOnce,
      emptyStateMessage: emptyStateMessage,
      selectedRoute: selectedRoute,
      onSelect: onSelect,
      onDeleteConversation: onDeleteConversation
    )
    .modifier(ChatsListModifiers(
      viewModel: viewModel,
      searchText: $searchText,
      showingNewChat: $showingNewChat,
      showingChannelOptions: $showingChannelOptions,
      onAnnounceOfflineStateIfNeeded: onAnnounceOfflineStateIfNeeded,
      onHandlePendingNavigation: onHandlePendingNavigation,
      onHandlePendingChannelNavigation: onHandlePendingChannelNavigation,
      onHandlePendingRoomNavigation: onHandlePendingRoomNavigation
    ))
    .onChange(of: selectedRoute) { oldValue, newValue in
      if oldValue != nil {
        viewModel.requestConversationReload()
      }
      lastSelectedRoomIsConnected = newValue?.roomIsConnected
    }
  }
}
