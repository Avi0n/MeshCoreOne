import SwiftUI
import MC1Services

/// Shared modifiers applied to the conversation list in both stack and split layouts.
struct ChatsListModifiers: ViewModifier {
    @Environment(\.appState) private var appState
    @Environment(\.appTheme) private var theme
    @State private var reloadTask: Task<Void, Never>?

    let viewModel: ChatViewModel

    @Binding var searchText: String
    @Binding var showingNewChat: Bool
    @Binding var showingChannelOptions: Bool

    /// Non-nil only in split layout; guards version-change reloads during deletion.
    let routeBeingDeleted: ChatRoute?

    let onLoadConversations: () async -> Void
    let onAnnounceOfflineStateIfNeeded: () -> Void
    let onHandlePendingNavigation: () -> Void
    let onHandlePendingChannelNavigation: () -> Void
    let onHandlePendingRoomNavigation: () -> Void

    func body(content: Content) -> some View {
        content
            .themedCanvas(theme)
            .navigationTitle(L10n.Chats.Chats.title)
            .searchable(text: $searchText, prompt: L10n.Chats.Chats.Search.placeholder)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    BLEStatusIndicatorView()
                }
                ToolbarItem(placement: .automatic) {
                    Menu {
                        Button {
                            showingNewChat = true
                        } label: {
                            Label(L10n.Chats.Chats.Compose.newChat, systemImage: "person")
                        }

                        Button {
                            showingChannelOptions = true
                        } label: {
                            Label(L10n.Chats.Chats.Compose.newChannel, systemImage: "number")
                        }
                    } label: {
                        Label(L10n.Chats.Chats.Compose.newMessage, systemImage: "square.and.pencil")
                    }
                }
            }
            .task {
                viewModel.configure(appState: appState)
                await onLoadConversations()
                onAnnounceOfflineStateIfNeeded()
                onHandlePendingNavigation()
                onHandlePendingChannelNavigation()
                onHandlePendingRoomNavigation()
            }
            .onChange(of: appState.navigation.pendingChatContact) { _, _ in
                onHandlePendingNavigation()
            }
            .onChange(of: appState.navigation.pendingChannel) { _, _ in
                onHandlePendingChannelNavigation()
            }
            .onChange(of: appState.navigation.pendingRoomSession) { _, _ in
                onHandlePendingRoomNavigation()
            }
            .onChange(of: appState.servicesVersion) { _, _ in
                guard routeBeingDeleted == nil else { return }
                reloadTask?.cancel()
                reloadTask = Task {
                    await onLoadConversations()
                }
            }
            .onChange(of: appState.conversationsVersion) { _, _ in
                guard routeBeingDeleted == nil else { return }
                reloadTask?.cancel()
                reloadTask = Task {
                    await onLoadConversations()
                }
            }
            .onChange(of: appState.connectionState) { _, newState in
                if newState == .disconnected {
                    reloadTask?.cancel()
                    reloadTask = Task {
                        await onLoadConversations()
                    }
                }
            }
    }
}
