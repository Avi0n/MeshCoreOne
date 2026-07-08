import MC1Services
import SwiftUI

struct ChatsStackRootContent: View {
  @Environment(\.appState) private var appState
  @Environment(\.appTheme) private var theme
  @Environment(\.colorScheme) private var colorScheme
  @Environment(\.colorSchemeContrast) private var colorSchemeContrast
  @Environment(\.dynamicTypeSize) private var dynamicTypeSize

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
      onNavigate: { route in
        prefetch(route)
        navigationPath.append(route)
      },
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

  /// Warms the shared coordinator for the tapped conversation while the push
  /// transition plays, so the chat renders populated instead of jumping in a
  /// frame after the segue on a cold open.
  private func prefetch(_ route: ChatRoute) {
    guard let conversation = route.chatConversationType else { return }
    appState.prefetchConversation(
      conversation,
      envInputs: appState.chatEnvInputs(
        for: conversation,
        themeID: theme.id,
        isDark: colorScheme == .dark,
        isHighContrast: colorSchemeContrast == .increased,
        contentSizeCategory: AppearanceToken.contentSizeCategoryToken(dynamicTypeSize)
      )
    )
  }
}
