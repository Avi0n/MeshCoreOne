import SwiftUI
import MC1Services
import OSLog

private let chatListActionsLogger = Logger(subsystem: "com.mc1", category: "ChatListActions")

/// Layout-independent chat-list actions shared by the compact `ChatsView` (stack) and the iPad
/// `ChatsContentColumn` (split). Both run identical delete, pending-navigation, and offline-announce
/// sequences; only `navigate` and `clearNavigationIfActive` differ between the stack and split
/// paths, so those are injected. Built fresh per body evaluation; the bindings point
/// at each view's own `@State`, so the captured state stays live.
@MainActor
struct ChatListActions {
    let viewModel: ChatViewModel
    let appState: AppState
    let roomToDelete: Binding<RemoteNodeSessionDTO?>
    let showRoomDeleteAlert: Binding<Bool>
    let channelDeleteFailure: Binding<ChatConversationActions.Failure?>
    let showChannelDeleteFailed: Binding<Bool>
    let roomToAuthenticate: Binding<RemoteNodeSessionDTO?>
    let navigate: (ChatRoute) -> Void
    let clearNavigationIfActive: (ChatRoute) -> Void

    func handleDeleteConversation(_ conversation: Conversation) {
        switch conversation {
        case .direct(let contact):
            deleteDirectConversation(contact)

        case .channel(let channel):
            deleteChannelConversation(channel)

        case .room(let session):
            roomToDelete.wrappedValue = session
            showRoomDeleteAlert.wrappedValue = true
        }
    }

    /// Direct conversations clear via a local SwiftData write only, so the row is hidden
    /// optimistically and restored if the write throws.
    func deleteDirectConversation(_ contact: ContactDTO) {
        guard !viewModel.isDeletePending(contact.id) else { return }
        clearNavigationIfActive(.direct(contact))
        viewModel.removeConversation(.direct(contact))      // optimistic hide, animated

        Task {
            do {
                try await viewModel.deleteDirectConversation(for: contact)
                // The local clear is authoritative, so drop the mask and purge the buffer now
                // rather than waiting for the reload to observe absence. An inbound message that
                // re-sets lastMessageDate mid-delete would otherwise read the row present at every
                // reload and mask it forever; clearing here lets such a row correctly reappear.
                viewModel.confirmDirectRemoval(contact)
                viewModel.requestConversationReload()
            } catch {
                viewModel.restoreConversation(.direct(contact))  // re-admit the held DTO, animated
                viewModel.errorMessage = error.localizedDescription
            }
        }
    }

    /// Channel deletion sends a radio command. The row stays put with a spinner until the
    /// command acks, then is hidden once; a failure or timeout leaves it in place with a retry alert.
    func deleteChannelConversation(_ channel: ChannelDTO) {
        guard !viewModel.isDeletePending(channel.id) else { return }
        viewModel.deletingIDs.insert(channel.id)
        Task {
            defer { viewModel.deletingIDs.remove(channel.id) }
            do {
                try await withTimeout(RadioCommandTimeout.delete, operationName: "clearChannel") {
                    try await ChatConversationActions.deleteChannel(channel, appState: appState)
                }
                clearNavigationIfActive(.channel(channel))
                viewModel.removeConversation(.channel(channel))  // single animated removal after ack
            } catch {
                channelDeleteFailure.wrappedValue = ChatConversationActions.Failure(
                    channel: channel,
                    message: error.localizedDescription
                )
                showChannelDeleteFailed.wrappedValue = true
            }
            viewModel.requestConversationReload()
        }
    }

    /// Room leave sends radio commands (logout, remove contact). The row stays put with a
    /// spinner until they ack, then is hidden once; a failure leaves it in place. A partial
    /// failure is reconciled by the trailing reload rather than a blind re-insert, so a row
    /// already gone from the database is not re-admitted.
    func deleteRoom(_ session: RemoteNodeSessionDTO) async {
        guard !viewModel.isDeletePending(session.id) else { return }
        viewModel.deletingIDs.insert(session.id)
        defer { viewModel.deletingIDs.remove(session.id) }
        do {
            try await withTimeout(RadioCommandTimeout.delete, operationName: "leaveRoom") {
                try await ChatConversationActions.leaveRoom(session, appState: appState)
            }
            clearNavigationIfActive(.room(session))
            viewModel.removeConversation(.room(session))     // hide after the database is mutated, animated
        } catch {
            chatListActionsLogger.error("Failed to delete room: \(error)")
            viewModel.errorMessage = error.localizedDescription
        }
        viewModel.requestConversationReload()
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
