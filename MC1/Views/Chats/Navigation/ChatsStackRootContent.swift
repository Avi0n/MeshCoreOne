import MC1Services
import SwiftUI

struct ChatsStackRootContent: View {
  let viewModel: ChatViewModel
  let filteredFavorites: [Conversation]
  let filteredOthers: [Conversation]
  let emptyStateMessage: (title: String, description: String, systemImage: String)
  let hasLoadedOnce: Bool

  @Binding var selectedFilter: ChatFilter
  @Binding var searchText: String
  @Binding var showingNewChat: Bool
  @Binding var showingChannelOptions: Bool
  @Binding var roomToAuthenticate: RemoteNodeSessionDTO?
  @Binding var navigationPath: NavigationPath

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
      onNavigate: { navigationPath.append($0) },
      onRequestRoomAuth: { roomToAuthenticate = $0 },
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
  }
}
