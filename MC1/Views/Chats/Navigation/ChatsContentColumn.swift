import MC1Services
import SwiftUI

/// The iPad sidebar's Chats content column. It mirrors the regular-width (split) path of
/// `ChatsView`, supplying real action closures to `ChatsSplitSidebarContent` and attaching
/// the same sheets, alerts, and pending-navigation handlers. The compact (stack) path stays
/// solely in `ChatsView`. The `viewModel` is passed in so the content and detail columns
/// share one instance.
struct ChatsContentColumn: View {
  @Environment(\.appState) private var appState
  @Environment(\.appTheme) private var theme
  @Environment(\.colorScheme) private var colorScheme
  @Environment(\.colorSchemeContrast) private var colorSchemeContrast
  @Environment(\.dynamicTypeSize) private var dynamicTypeSize

  let viewModel: ChatViewModel

  @State private var searchText = ""
  @State private var selectedFilter: ChatFilter = .all
  @State private var showingNewChat = false
  @State private var showingChannelOptions = false

  /// View-local mirror of `appState.navigation.chatsSelectedRoute`; the detail column keys
  /// off the latter, and `ChatsSplitSidebarContent.onChange` keeps the two in sync. Re-seeded
  /// from the preserved route in `.task` because leaving and re-entering Chats rebuilds this
  /// column and resets the mirror, which would otherwise un-highlight the row the detail shows.
  @State private var selectedRoute: ChatRoute?
  @State private var lastSelectedRoomIsConnected: Bool?

  @State private var roomToAuthenticate: RemoteNodeSessionDTO?
  @State private var roomToDelete: RemoteNodeSessionDTO?
  @State private var showRoomDeleteAlert = false
  @State private var showChannelDeleteFailed = false
  @State private var channelDeleteFailure: ChatConversationActions.Failure?
  @State private var pendingChatContact: ContactDTO?
  @State private var pendingChannel: ChannelDTO?

  private var filteredFavorites: [Conversation] {
    viewModel.favoriteConversations.filtered(by: selectedFilter, searchText: searchText)
  }

  private var filteredOthers: [Conversation] {
    viewModel.nonFavoriteConversations.filtered(by: selectedFilter, searchText: searchText)
  }

  private var emptyStateMessage: (title: String, description: String, systemImage: String) {
    switch selectedFilter {
    case .all:
      (L10n.Chats.Chats.EmptyState.NoConversations.title, L10n.Chats.Chats.EmptyState.NoConversations.description, "message")
    case .unread:
      (L10n.Chats.Chats.EmptyState.NoUnread.title, L10n.Chats.Chats.EmptyState.NoUnread.description, "checkmark.circle")
    case .directMessages:
      (L10n.Chats.Chats.EmptyState.NoDirectMessages.title, L10n.Chats.Chats.EmptyState.NoDirectMessages.description, "person")
    case .channels:
      (L10n.Chats.Chats.EmptyState.NoChannels.title, L10n.Chats.Chats.EmptyState.NoChannels.description, "number")
    case .rooms:
      (L10n.Chats.Chats.EmptyState.NoRooms.title, L10n.Chats.Chats.EmptyState.NoRooms.description, "door.left.hand.open")
    }
  }

  private var actions: ChatListActions {
    ChatListActions(
      viewModel: viewModel,
      appState: appState,
      roomToDelete: $roomToDelete,
      showRoomDeleteAlert: $showRoomDeleteAlert,
      channelDeleteFailure: $channelDeleteFailure,
      showChannelDeleteFailed: $showChannelDeleteFailed,
      roomToAuthenticate: $roomToAuthenticate,
      navigate: { navigate(to: $0) },
      clearNavigationIfActive: clearNavigationIfActive
    )
  }

  var body: some View {
    ChatsSplitSidebarContent(
      viewModel: viewModel,
      filteredFavorites: filteredFavorites,
      filteredOthers: filteredOthers,
      emptyStateMessage: emptyStateMessage,
      hasLoadedOnce: viewModel.hasLoadedOnce,
      selectedRoute: $selectedRoute,
      selectedFilter: $selectedFilter,
      searchText: $searchText,
      showingNewChat: $showingNewChat,
      showingChannelOptions: $showingChannelOptions,
      roomToAuthenticate: $roomToAuthenticate,
      lastSelectedRoomIsConnected: $lastSelectedRoomIsConnected,
      onDeleteConversation: actions.handleDeleteConversation,
      onHandlePendingNavigation: actions.handlePendingNavigation,
      onHandlePendingChannelNavigation: actions.handlePendingChannelNavigation,
      onHandlePendingRoomNavigation: actions.handlePendingRoomNavigation,
      onAnnounceOfflineStateIfNeeded: actions.announceOfflineStateIfNeeded
    )
    .task {
      seedSelectionFromPreservedRoute()
      actions.consumePendingRoomAuthentication()
    }
    .onChange(of: appState.navigation.pendingRoomAuthentication) { _, _ in
      actions.consumePendingRoomAuthentication()
    }
    .onChange(of: appState.navigation.chatsSelectedRoute) { _, newRoute in
      // The detail column keys off chatsSelectedRoute, but the list highlight keys off the
      // view-local mirror. An external clear (a radio switch runs clearPerRadioSelection while
      // Chats stays mounted, so .task never re-seeds) only nils the coordinator route, so drop
      // the mirror here too to keep the list highlight and detail pane in agreement.
      if newRoute == nil {
        selectedRoute = nil
        lastSelectedRoomIsConnected = nil
      } else if let newRoute {
        // Sidebar taps bind selection directly (bypassing `navigate(to:)`), so warm the
        // coordinator here — the universal hook for every split-view selection.
        prefetch(newRoute)
      }
    }
    // Refresh the selected route's payload as the snapshot recomputes and re-run the
    // room reauth guard; a route whose conversation was removed resolves to nil and clears.
    .onChange(of: viewModel.snapshotGeneration) { _, _ in
      let refreshed = selectedRoute?.refreshedPayload(from: viewModel.allConversations)
      selectedRoute = refreshed

      if lastSelectedRoomIsConnected == true,
         case let .room(session) = selectedRoute,
         !session.isConnected {
        roomToAuthenticate = session
        selectedRoute = nil
      }

      lastSelectedRoomIsConnected = selectedRoute?.roomIsConnected
    }
    .modifier(ChatsConversationSheets(
      viewModel: viewModel,
      showingNewChat: $showingNewChat,
      showingChannelOptions: $showingChannelOptions,
      roomToAuthenticate: $roomToAuthenticate,
      roomToDelete: $roomToDelete,
      showRoomDeleteAlert: $showRoomDeleteAlert,
      channelDeleteFailure: $channelDeleteFailure,
      showChannelDeleteFailed: $showChannelDeleteFailed,
      pendingChatContact: $pendingChatContact,
      pendingChannel: $pendingChannel,
      navigate: { navigate(to: $0) },
      deleteChannelConversation: actions.deleteChannelConversation,
      deleteRoom: actions.deleteRoom
    ))
  }

  private func navigate(to route: ChatRoute) {
    selectedRoute = route
    appState.navigation.chatsSelectedRoute = route
  }

  /// Warms the shared coordinator for the selected conversation before the detail
  /// column swaps in, so the chat renders populated instead of jumping in a frame
  /// later on a cold open.
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

  private func clearNavigationIfActive(_ route: ChatRoute) {
    if appState.navigation.chatsSelectedRoute == route {
      selectedRoute = nil
      appState.navigation.chatsSelectedRoute = nil
    }
  }

  /// Restores the view-local selection from the route preserved in `NavigationCoordinator` when
  /// this column is rebuilt on Chats re-entry, so the list re-highlights the row the detail pane
  /// is still showing and the room-reauth guard regains its `lastSelectedRoomIsConnected` baseline.
  /// Skips when a selection already exists so a pending navigation that ran first is not clobbered.
  private func seedSelectionFromPreservedRoute() {
    guard selectedRoute == nil, let route = appState.navigation.chatsSelectedRoute else { return }
    selectedRoute = route
    lastSelectedRoomIsConnected = route.roomIsConnected
  }
}
