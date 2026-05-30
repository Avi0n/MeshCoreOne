import SwiftUI
import MC1Services
import OSLog

private let chatsViewLogger = Logger(subsystem: "com.mc1", category: "ChatsView")

struct ChatsView: View {
    @Environment(\.appState) private var appState
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass

    @State private var viewModel = ChatViewModel()
    @State private var searchText = ""
    @State private var selectedFilter: ChatFilter = .all
    @State private var showingNewChat = false
    @State private var showingChannelOptions = false

    @State private var selectedRoute: ChatRoute?
    @State private var navigationPath = NavigationPath()
    @State private var activeRoute: ChatRoute?
    @State private var lastSelectedRoomIsConnected: Bool?
    @State private var routeBeingDeleted: ChatRoute?

    @State private var roomToAuthenticate: RemoteNodeSessionDTO?
    @State private var roomToDelete: RemoteNodeSessionDTO?
    @State private var showRoomDeleteAlert = false
    @State private var showChannelDeleteFailed = false
    @State private var channelDeleteFailure: ChannelDeleteFailure?
    @State private var pendingChatContact: ContactDTO?
    @State private var pendingChannel: ChannelDTO?
    private var shouldUseSplitView: Bool {
        horizontalSizeClass == .regular
    }

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

    var body: some View {
        Group {
            if shouldUseSplitView {
                ChatsSplitLayout(detailID: appState.navigation.chatsSelectedRoute?.conversationID) {
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
                        routeBeingDeleted: $routeBeingDeleted,
                        onDeleteConversation: handleDeleteConversation,
                        onLoadConversations: loadConversations,
                        onHandlePendingNavigation: handlePendingNavigation,
                        onHandlePendingChannelNavigation: handlePendingChannelNavigation,
                        onHandlePendingRoomNavigation: handlePendingRoomNavigation,
                        onAnnounceOfflineStateIfNeeded: announceOfflineStateIfNeeded
                    )
                } detail: {
                    ChatsSplitDetailContent(viewModel: viewModel)
                }
            } else {
                ChatsStackLayout(
                    viewModel: viewModel,
                    navigationPath: $navigationPath,
                    activeRoute: $activeRoute,
                    onLoadConversations: loadConversations
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
                        onDeleteConversation: handleDeleteConversation,
                        onLoadConversations: loadConversations,
                        onHandlePendingNavigation: handlePendingNavigation,
                        onHandlePendingChannelNavigation: handlePendingChannelNavigation,
                        onHandlePendingRoomNavigation: handlePendingRoomNavigation,
                        onAnnounceOfflineStateIfNeeded: announceOfflineStateIfNeeded
                    )
                }
            }
        }
        .environment(\.openURL, OpenURLAction { url in
            ChatLinkRouter.route(url, appState: appState) ? .handled : .systemAction
        })
        .task {
            consumePendingRoomAuthentication()
        }
        .onChange(of: appState.navigation.pendingRoomAuthentication) { _, _ in
            consumePendingRoomAuthentication()
        }
        .sheet(item: Binding(
            get: { appState.navigation.pendingHashtag },
            set: { appState.navigation.pendingHashtag = $0 }
        )) { request in
            JoinHashtagFromMessageView(channelName: request.id) { channel in
                appState.navigation.clearPendingHashtag()
                if let channel {
                    navigate(to: .channel(channel))
                }
            }
            .presentationDetents([.medium])
        }
        .sheet(item: Binding(
            get: { appState.navigation.pendingContactLink },
            set: { appState.navigation.pendingContactLink = $0 }
        )) { result in
            AddContactConfirmationSheet(contactResult: result) { addedContact in
                appState.navigation.clearPendingContactLink()
                if let addedContact {
                    appState.navigation.navigateToContactDetail(addedContact)
                }
            }
            .presentationDetents([.medium, .large])
        }
        .sheet(item: Binding(
            get: { appState.navigation.pendingChannelLink },
            set: { appState.navigation.pendingChannelLink = $0 }
        )) { result in
            JoinChannelConfirmationSheet(channelResult: result) { newChannel in
                appState.navigation.clearPendingChannelLink()
                if let newChannel {
                    navigate(to: .channel(newChannel))
                }
            }
            .presentationDetents([.medium, .large])
        }
        .sheet(isPresented: $showingNewChat, onDismiss: {
            if let contact = pendingChatContact {
                pendingChatContact = nil
                navigate(to: .direct(contact))
            }
        }) {
            NewChatView { contact in
                pendingChatContact = contact
                showingNewChat = false
            }
        }
        .sheet(isPresented: $showingChannelOptions, onDismiss: {
            Task {
                await loadConversations()
                if let channel = pendingChannel {
                    pendingChannel = nil
                    navigate(to: .channel(channel))
                }
            }
        }) {
            ChannelOptionsSheet { channel in
                pendingChannel = channel
            }
        }
        .sheet(item: $roomToAuthenticate) { session in
            RoomAuthenticationSheet(session: session) { authenticatedSession in
                roomToAuthenticate = nil
                navigate(to: .room(authenticatedSession))
            }
            .presentationSizing(.page)
        }
        .alert(L10n.Chats.Chats.Alert.LeaveRoom.title, isPresented: $showRoomDeleteAlert) {
            Button(L10n.Chats.Chats.Common.cancel, role: .cancel) {
                roomToDelete = nil
                routeBeingDeleted = nil
            }
            Button(L10n.Chats.Chats.Alert.LeaveRoom.confirm, role: .destructive) {
                Task {
                    if let session = roomToDelete {
                        routeBeingDeleted = .room(session)
                        await deleteRoom(session)
                    }
                    roomToDelete = nil
                    await loadConversations()
                    routeBeingDeleted = nil
                }
            }
        } message: {
            Text(L10n.Chats.Chats.Alert.LeaveRoom.message)
        }
        .alert(
            L10n.Chats.Chats.ChannelInfo.DeleteFailed.title,
            isPresented: $showChannelDeleteFailed,
            presenting: channelDeleteFailure
        ) { failure in
            Button(L10n.Localizable.Common.tryAgain) {
                deleteChannelConversation(failure.channel)
            }
            Button(L10n.Chats.Chats.Common.ok, role: .cancel) { }
        } message: { failure in
            Text(failure.message)
        }
    }

    private struct ChannelDeleteFailure {
        let channel: ChannelDTO
        let message: String
    }

    private enum ChannelDeleteError: LocalizedError {
        case servicesUnavailable

        var errorDescription: String? {
            switch self {
            case .servicesUnavailable: L10n.Chats.Chats.Error.servicesUnavailable
            }
        }
    }

    private func loadConversations() async {
        guard let deviceID = appState.currentRadioID else {
            viewModel.clearConversations()
            return
        }
        viewModel.configure(appState: appState)
        await viewModel.loadAllConversations(radioID: deviceID)

        // If we're in the middle of deleting an item, ensure it stays removed
        // This handles race conditions where a reload happens before DB delete completes
        if let routeBeingDeleted {
            viewModel.removeConversation(routeBeingDeleted.toConversation())
        }

        if let selectedRoute {
            self.selectedRoute = selectedRoute.refreshedPayload(from: viewModel.allConversations)
        }
        if let activeRoute {
            self.activeRoute = activeRoute.refreshedPayload(from: viewModel.allConversations)
        }

        if shouldUseSplitView,
           lastSelectedRoomIsConnected == true,
           case .room(let session) = self.selectedRoute,
           !session.isConnected {
            roomToAuthenticate = session
            self.selectedRoute = nil
        }

        lastSelectedRoomIsConnected = selectedRoute?.roomIsConnected
    }

    private func announceOfflineStateIfNeeded() {
        guard appState.connectionState == .disconnected,
              appState.currentRadioID != nil else { return }

        AccessibilityNotification.Announcement(L10n.Chats.Chats.Accessibility.offlineAnnouncement).post()
    }

    private func navigate(to route: ChatRoute) {
        if shouldUseSplitView {
            selectedRoute = route
            appState.navigation.chatsSelectedRoute = route
            return
        }

        if case .room(let session) = route, !session.isConnected {
            roomToAuthenticate = session
            return
        }

        appState.navigation.tabBarVisibility = .hidden
        navigationPath.removeLast(navigationPath.count)
        navigationPath.append(route)
    }

    private func handleDeleteConversation(_ conversation: Conversation) {
        switch conversation {
        case .direct(let contact):
            routeBeingDeleted = .direct(contact)
            deleteDirectConversation(contact)

        case .channel(let channel):
            deleteChannelConversation(channel)

        case .room(let session):
            roomToDelete = session
            showRoomDeleteAlert = true
        }
    }

    private func deleteDirectConversation(_ contact: ContactDTO) {
        clearNavigationIfActive(.direct(contact))
        viewModel.removeConversation(.direct(contact))

        Task {
            try? await viewModel.deleteDirectConversation(for: contact)
            await loadConversations()
            routeBeingDeleted = nil
        }
    }

    private func deleteChannelConversation(_ channel: ChannelDTO) {
        Task {
            do {
                try await deleteChannel(channel)
                clearNavigationIfActive(.channel(channel))
                await loadConversations()
            } catch {
                channelDeleteFailure = ChannelDeleteFailure(
                    channel: channel,
                    message: error.localizedDescription
                )
                showChannelDeleteFailed = true
            }
        }
    }

    private func deleteRoom(_ session: RemoteNodeSessionDTO) async {
        do {
            try await appState.services?.roomServerService.leaveRoom(
                sessionID: session.id,
                publicKey: session.publicKey
            )

            try await appState.services?.contactService.removeContact(
                radioID: session.radioID,
                publicKey: session.publicKey
            )

            await appState.services?.notificationService.updateBadgeCount()

            clearNavigationIfActive(.room(session))
            viewModel.removeConversation(.room(session))
        } catch {
            chatsViewLogger.error("Failed to delete room: \(error)")
        }
    }

    private func deleteChannel(_ channel: ChannelDTO) async throws {
        guard let channelService = appState.services?.channelService else {
            throw ChannelDeleteError.servicesUnavailable
        }
        try await channelService.clearChannel(
            radioID: channel.radioID,
            index: channel.index
        )
        await appState.services?.notificationService.removeDeliveredNotifications(
            forChannelIndex: channel.index,
            radioID: channel.radioID
        )
        await appState.services?.notificationService.updateBadgeCount()
    }

    private func clearNavigationIfActive(_ route: ChatRoute) {
        if shouldUseSplitView && appState.navigation.chatsSelectedRoute == route {
            selectedRoute = nil
            appState.navigation.chatsSelectedRoute = nil
        }
        if !shouldUseSplitView && activeRoute == route {
            navigationPath.removeLast(navigationPath.count)
            activeRoute = nil
            appState.navigation.tabBarVisibility = .visible
        }
    }

    private func handlePendingNavigation() {
        guard let contact = appState.navigation.pendingChatContact else { return }
        navigate(to: .direct(contact))
        appState.navigation.clearPendingNavigation()
    }

    private func handlePendingChannelNavigation() {
        guard let channel = appState.navigation.pendingChannel else { return }
        navigate(to: .channel(channel))
        appState.navigation.clearPendingChannelNavigation()
    }

    private func handlePendingRoomNavigation() {
        guard let session = appState.navigation.pendingRoomSession else { return }
        navigate(to: .room(session))
        appState.navigation.clearPendingRoomNavigation()
    }

    /// Presents the room auth sheet for a disconnected room a notification tap
    /// wants to open, reusing the same sheet a disconnected-room list tap uses.
    private func consumePendingRoomAuthentication() {
        guard let session = appState.navigation.pendingRoomAuthentication else { return }
        roomToAuthenticate = session
        appState.navigation.clearPendingRoomAuthentication()
    }

}

#Preview {
    ChatsView()
        .environment(\.appState, AppState())
}
