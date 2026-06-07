import SwiftUI
import MC1Services

/// The iPad sidebar's Chats content column. It mirrors the regular-width (split) path of
/// `ChatsView`, supplying real action closures to `ChatsSplitSidebarContent` and attaching
/// the same sheets, alerts, and pending-navigation handlers. The compact (stack) path stays
/// solely in `ChatsView`. The `viewModel` is passed in so the content and detail columns
/// share one instance.
struct ChatsContentColumn: View {
    @Environment(\.appState) private var appState

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
            }
        }
        // Keep the selected route's payload current as the snapshot recomputes, and
        // re-run the disconnected-room reauth guard a reload may have invalidated. A
        // selected route whose conversation was removed resolves to nil and clears.
        .onChange(of: viewModel.snapshotGeneration) { _, _ in
            let refreshed = selectedRoute?.refreshedPayload(from: viewModel.allConversations)
            selectedRoute = refreshed

            if lastSelectedRoomIsConnected == true,
               case .room(let session) = selectedRoute,
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
