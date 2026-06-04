import SwiftUI
import MC1Services

struct ChatsSplitSidebarContent: View {
    @Environment(\.appState) private var appState
    @State private var reloadTask: Task<Void, Never>?

    let viewModel: ChatViewModel
    let filteredFavorites: [Conversation]
    let filteredOthers: [Conversation]
    let emptyStateMessage: (title: String, description: String, systemImage: String)
    let hasLoadedOnce: Bool

    @Binding var selectedRoute: ChatRoute?
    @Binding var selectedFilter: ChatFilter
    @Binding var searchText: String
    @Binding var showingNewChat: Bool
    @Binding var showingChannelOptions: Bool
    @Binding var roomToAuthenticate: RemoteNodeSessionDTO?
    @Binding var lastSelectedRoomIsConnected: Bool?
    @Binding var routeBeingDeleted: ChatRoute?

    let onDeleteConversation: (Conversation) -> Void
    let onLoadConversations: () async -> Void
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
            selection: $selectedRoute,
            onDeleteConversation: onDeleteConversation
        )
        .modifier(ChatsListModifiers(
            viewModel: viewModel,
            isSidebar: true,
            searchText: $searchText,
            showingNewChat: $showingNewChat,
            showingChannelOptions: $showingChannelOptions,
            routeBeingDeleted: routeBeingDeleted,
            onLoadConversations: onLoadConversations,
            onAnnounceOfflineStateIfNeeded: onAnnounceOfflineStateIfNeeded,
            onHandlePendingNavigation: onHandlePendingNavigation,
            onHandlePendingChannelNavigation: onHandlePendingChannelNavigation,
            onHandlePendingRoomNavigation: onHandlePendingRoomNavigation
        ))
        .onChange(of: selectedRoute) { oldValue, newValue in
            // Reload conversations when navigating away (but not when clearing for deletion)
            if oldValue != nil {
                let didClearSelectionForDeletion = (newValue == nil && oldValue == routeBeingDeleted)
                if !didClearSelectionForDeletion {
                    reloadTask?.cancel()
                    reloadTask = Task {
                        await onLoadConversations()
                    }
                }
            }

            if case .room(let session) = newValue, !session.isConnected {
                roomToAuthenticate = session
                selectedRoute = nil
                appState.navigation.chatsSelectedRoute = nil
                lastSelectedRoomIsConnected = nil
                routeBeingDeleted = nil
                return
            }

            lastSelectedRoomIsConnected = newValue?.roomIsConnected

            // Mirror the sidebar selection to AppState so the detail pane tracks it. Assigned
            // unconditionally, including nil: the disconnected-room reload path clears the local
            // selection without writing `chatsSelectedRoute`, so mirroring nil here is what dismisses
            // the now-stale detail pane.
            appState.navigation.chatsSelectedRoute = newValue
        }
    }

}
