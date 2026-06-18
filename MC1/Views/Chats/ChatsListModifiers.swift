import SwiftUI
import MC1Services

/// Shared modifiers applied to the conversation list in both stack and split layouts.
struct ChatsListModifiers: ViewModifier {
    @Environment(\.appState) private var appState
    @Environment(\.appTheme) private var theme

    let viewModel: ChatViewModel

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
                bleStatusToolbarItem()
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
                viewModel.configure(
                    dependencies: ChatViewModel.Dependencies(
                        dataStore: { appState.offlineDataStore },
                        messageService: { appState.services?.messageService },
                        notificationService: { appState.services?.notificationService },
                        channelService: { appState.services?.channelService },
                        roomServerService: { appState.services?.roomServerService },
                        contactService: { appState.services?.contactService },
                        syncCoordinator: { appState.syncCoordinator },
                        connectionState: { appState.connectionState },
                        connectedDevice: { appState.connectedDevice },
                        currentRadioID: { appState.currentRadioID },
                        session: { appState.services?.session },
                        reactionService: { appState.services?.reactionService },
                        chatSendQueueService: { appState.services?.chatSendQueueService },
                        inlineImageDimensionsStore: { nil },
                        prefetchDataStore: { nil }
                    ),
                    onNavigateToMap: { appState.navigation.navigateToMap(coordinate: $0) },
                    linkPreviewCache: nil,
                    chatCoordinatorRegistry: nil,
                    conversation: nil
                )
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
