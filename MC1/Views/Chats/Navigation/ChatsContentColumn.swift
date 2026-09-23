import MC1Services
import SwiftUI

/// Conversation list for the Chats split. Search, filter, and sheet state live
/// here so they survive column collapse. Selection is `chatsSelectedRoute`.
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

  @State private var lastSelectedRoomIsConnected: Bool?

  @State private var roomToAuthenticate: RemoteNodeSessionDTO?
  @State private var roomToDelete: RemoteNodeSessionDTO?
  @State private var showRoomDeleteAlert = false
  @State private var showChannelDeleteFailed = false
  @State private var channelDeleteFailure: ChatConversationActions.Failure?
  @State private var newChatContact: ContactDTO?
  @State private var newChannel: ChannelDTO?

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
      selectedRoute: appState.navigation.chatsSelectedRoute,
      selectedFilter: $selectedFilter,
      searchText: $searchText,
      showingNewChat: $showingNewChat,
      showingChannelOptions: $showingChannelOptions,
      lastSelectedRoomIsConnected: $lastSelectedRoomIsConnected,
      onSelect: { navigate(to: $0) },
      onDeleteConversation: actions.handleDeleteConversation,
      onAnnounceOfflineStateIfNeeded: actions.announceOfflineStateIfNeeded
    )
    .task {
      lastSelectedRoomIsConnected = appState.navigation.chatsSelectedRoute?.roomIsConnected
      if let route = appState.navigation.chatsSelectedRoute {
        prefetch(route)
      }
      actions.consumePendingRoomAuthentication()
    }
    .onChange(of: appState.navigation.pendingRoomAuthentication) { _, _ in
      actions.consumePendingRoomAuthentication()
    }
    .onChange(of: appState.navigation.chatsSelectedRoute) { _, newRoute in
      if let newRoute {
        prefetch(newRoute)
      }
    }
    .onChange(of: viewModel.snapshotGeneration) { _, _ in
      refreshSelectedRoutePayload()
    }
    .onChange(of: appState.connectedDevice?.radioID) { _, _ in
      dismissStaleRoomAuthentication()
    }
    .onChange(of: appState.currentRadioID) { _, _ in
      dismissStaleRoomAuthentication()
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
      newChatContact: $newChatContact,
      newChannel: $newChannel,
      navigate: { navigate(to: $0) },
      deleteChannelConversation: actions.deleteChannelConversation,
      deleteRoom: actions.deleteRoom
    ))
  }

  private func navigate(to route: ChatRoute) {
    if case let .room(session) = route, !session.isConnected {
      roomToAuthenticate = session
      appState.navigation.chatsSelectedRoute = nil
      lastSelectedRoomIsConnected = nil
      return
    }

    appState.navigation.chatsSelectedRoute = route
  }

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
      appState.navigation.chatsSelectedRoute = nil
    }
  }

  private func refreshSelectedRoutePayload() {
    let current = appState.navigation.chatsSelectedRoute
    let refreshed = current?.refreshedPayload(from: viewModel.allConversations)

    if lastSelectedRoomIsConnected == true,
       case let .room(session) = refreshed,
       !session.isConnected {
      roomToAuthenticate = session
      appState.navigation.chatsSelectedRoute = nil
      lastSelectedRoomIsConnected = nil
      return
    }

    appState.navigation.chatsSelectedRoute = refreshed
    lastSelectedRoomIsConnected = refreshed?.roomIsConnected
  }

  private func dismissStaleRoomAuthentication() {
    guard let session = roomToAuthenticate else { return }
    guard ChatsRadioScopedSheets.shouldKeepRoomAuth(
      sessionRadioID: session.radioID,
      currentRadioID: appState.currentRadioID,
      hasConnectedDevice: appState.connectedDevice != nil
    ) else {
      roomToAuthenticate = nil
      return
    }
  }
}
