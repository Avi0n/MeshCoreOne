import SwiftUI
import MC1Services

struct ChatsView: View {
    @Environment(\.appState) private var appState

    @State private var viewModel = ChatViewModel()
    @State private var searchText = ""
    @State private var selectedFilter: ChatFilter = .all
    @State private var showingNewChat = false
    @State private var showingChannelOptions = false

    @State private var navigationPath = NavigationPath()
    @State private var activeRoute: ChatRoute?

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
            return (L10n.Chats.Chats.EmptyState.NoConversations.title, L10n.Chats.Chats.EmptyState.NoConversations.description, "message")
        case .unread:
            return (L10n.Chats.Chats.EmptyState.NoUnread.title, L10n.Chats.Chats.EmptyState.NoUnread.description, "checkmark.circle")
        case .directMessages:
            return (L10n.Chats.Chats.EmptyState.NoDirectMessages.title, L10n.Chats.Chats.EmptyState.NoDirectMessages.description, "person")
        case .channels:
            return (L10n.Chats.Chats.EmptyState.NoChannels.title, L10n.Chats.Chats.EmptyState.NoChannels.description, "number")
        case .rooms:
            return (L10n.Chats.Chats.EmptyState.NoRooms.title, L10n.Chats.Chats.EmptyState.NoRooms.description, "door.left.hand.open")
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
        ChatsStackLayout(
            viewModel: viewModel,
            navigationPath: $navigationPath,
            activeRoute: $activeRoute
        ) {
            ChatsStackRootContent(
                viewModel: viewModel,
                filteredFavorites: filteredFavorites,
                filteredOthers: filteredOthers,
                emptyStateMessage: emptyStateMessage,
                hasLoadedOnce: viewModel.hasLoadedOnce,
                selectedFilter: $selectedFilter,
                searchText: $searchText,
                showingNewChat: $showingNewChat,
                showingChannelOptions: $showingChannelOptions,
                roomToAuthenticate: $roomToAuthenticate,
                navigationPath: $navigationPath,
                onDeleteConversation: actions.handleDeleteConversation,
                onHandlePendingNavigation: actions.handlePendingNavigation,
                onHandlePendingChannelNavigation: actions.handlePendingChannelNavigation,
                onHandlePendingRoomNavigation: actions.handlePendingRoomNavigation,
                onAnnounceOfflineStateIfNeeded: actions.announceOfflineStateIfNeeded
            )
        }
        .task {
            actions.consumePendingRoomAuthentication()
        }
        .onChange(of: appState.navigation.pendingRoomAuthentication) { _, _ in
            actions.consumePendingRoomAuthentication()
        }
        // Keep the pushed route's payload current as the snapshot recomputes; a route
        // whose conversation was removed resolves to nil and the push unwinds.
        .onChange(of: viewModel.snapshotGeneration) { _, _ in
            activeRoute = activeRoute?.refreshedPayload(from: viewModel.allConversations)
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
        if case .room(let session) = route, !session.isConnected {
            roomToAuthenticate = session
            return
        }

        appState.navigation.tabBarVisibility = .hidden
        navigationPath.removeLast(navigationPath.count)
        navigationPath.append(route)
    }

    private func clearNavigationIfActive(_ route: ChatRoute) {
        if activeRoute == route {
            navigationPath.removeLast(navigationPath.count)
            activeRoute = nil
            appState.navigation.tabBarVisibility = .visible
        }
    }
}

#Preview {
    ChatsView()
        .environment(\.appState, AppState())
}
