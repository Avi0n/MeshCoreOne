import SwiftUI
import MC1Services
import OSLog

private let chatListActionsLogger = Logger(subsystem: "com.mc1", category: "ChatListActions")

/// Layout-independent chat-list actions shared by the compact `ChatsView` (stack) and the iPad
/// `ChatsContentColumn` (split). Both run identical delete, pending-navigation, and offline-announce
/// sequences; only `navigate`, `clearNavigationIfActive`, and `loadConversations` differ between the
/// stack and split paths, so those are injected. Built fresh per body evaluation; the bindings point
/// at each view's own `@State`, so the captured state stays live.
@MainActor
struct ChatListActions {
    let viewModel: ChatViewModel
    let appState: AppState
    let routeBeingDeleted: Binding<ChatRoute?>
    let roomToDelete: Binding<RemoteNodeSessionDTO?>
    let showRoomDeleteAlert: Binding<Bool>
    let channelDeleteFailure: Binding<ChatConversationActions.Failure?>
    let showChannelDeleteFailed: Binding<Bool>
    let roomToAuthenticate: Binding<RemoteNodeSessionDTO?>
    let navigate: (ChatRoute) -> Void
    let clearNavigationIfActive: (ChatRoute) -> Void
    let loadConversations: () async -> Void

    func handleDeleteConversation(_ conversation: Conversation) {
        switch conversation {
        case .direct(let contact):
            routeBeingDeleted.wrappedValue = .direct(contact)
            deleteDirectConversation(contact)

        case .channel(let channel):
            deleteChannelConversation(channel)

        case .room(let session):
            roomToDelete.wrappedValue = session
            showRoomDeleteAlert.wrappedValue = true
        }
    }

    func deleteDirectConversation(_ contact: ContactDTO) {
        clearNavigationIfActive(.direct(contact))
        viewModel.removeConversation(.direct(contact))

        Task {
            try? await viewModel.deleteDirectConversation(for: contact)
            await loadConversations()
            routeBeingDeleted.wrappedValue = nil
        }
    }

    func deleteChannelConversation(_ channel: ChannelDTO) {
        Task {
            do {
                try await ChatConversationActions.deleteChannel(channel, appState: appState)
                clearNavigationIfActive(.channel(channel))
                await loadConversations()
            } catch {
                channelDeleteFailure.wrappedValue = ChatConversationActions.Failure(
                    channel: channel,
                    message: error.localizedDescription
                )
                showChannelDeleteFailed.wrappedValue = true
            }
        }
    }

    func deleteRoom(_ session: RemoteNodeSessionDTO) async {
        do {
            try await ChatConversationActions.leaveRoom(session, appState: appState)
            clearNavigationIfActive(.room(session))
            viewModel.removeConversation(.room(session))
        } catch {
            chatListActionsLogger.error("Failed to delete room: \(error)")
        }
    }

    func handlePendingNavigation() {
        guard let contact = appState.navigation.pendingChatContact else { return }
        navigate(.direct(contact))
        appState.navigation.clearPendingNavigation()
    }

    func handlePendingChannelNavigation() {
        guard let channel = appState.navigation.pendingChannel else { return }
        navigate(.channel(channel))
        appState.navigation.clearPendingChannelNavigation()
    }

    func handlePendingRoomNavigation() {
        guard let session = appState.navigation.pendingRoomSession else { return }
        navigate(.room(session))
        appState.navigation.clearPendingRoomNavigation()
    }

    /// Presents the room auth sheet for a disconnected room a notification tap
    /// wants to open, reusing the same sheet a disconnected-room list tap uses.
    func consumePendingRoomAuthentication() {
        guard let session = appState.navigation.pendingRoomAuthentication else { return }
        roomToAuthenticate.wrappedValue = session
        appState.navigation.clearPendingRoomAuthentication()
    }

    func announceOfflineStateIfNeeded() {
        guard appState.connectionState == .disconnected,
              appState.currentRadioID != nil else { return }

        AccessibilityNotification.Announcement(L10n.Chats.Chats.Accessibility.offlineAnnouncement).post()
    }
}
