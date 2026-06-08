import SwiftUI
import MC1Services

/// Shared modifiers applied to the conversation list in both stack and split layouts.
struct ChatsListModifiers: ViewModifier {
    @Environment(\.appState) private var appState
    @Environment(\.appTheme) private var theme

    let viewModel: ChatViewModel

    /// `true` when this list is the iPad sidebar shell's content column, where the outer
    /// sidebar owns the radio; `false` on the iPhone stack, which has no sidebar.
    let isSidebar: Bool

    @Binding var searchText: String
    @Binding var showingNewChat: Bool
    @Binding var showingChannelOptions: Bool

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
                bleStatusToolbarItem(isVisible: !isSidebar || appState.navigation.isSidebarCollapsed)
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
                await viewModel.requestConversationReload()?.value
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
                viewModel.requestConversationReload()
            }
            .onChange(of: appState.conversationsVersion) { _, _ in
                viewModel.requestConversationReload()
            }
            .onChange(of: appState.connectionState) { _, newState in
                if newState == .disconnected {
                    viewModel.requestConversationReload()
                }
            }
            // Surfaces direct-message and room delete failures; the channel retry alert lives on
            // ChatsConversationSheets, and only one can present since deletes happen one at a time.
            .errorAlert(Binding(
                get: { viewModel.errorMessage },
                set: { viewModel.errorMessage = $0 }
            ))
    }
}
