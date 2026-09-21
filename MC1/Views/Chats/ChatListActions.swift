import MC1Services
import OSLog
import SwiftUI

private let chatListActionsLogger = Logger(subsystem: "com.mc1", category: "ChatListActions")

/// Shared chat-list delete and pending-navigation sequences. Injected `navigate`
/// keeps the split host and the list column on one route.
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
    case let .direct(contact):
      deleteDirectConversation(contact)

    case let .channel(channel):
      deleteChannelConversation(channel)

    case let .room(session):
      roomToDelete.wrappedValue = session
      showRoomDeleteAlert.wrappedValue = true
    }
  }

  /// Direct conversations clear via a local SwiftData write only, so the row is hidden
  /// optimistically and restored if the write throws.
  func deleteDirectConversation(_ contact: ContactDTO) {
    guard !viewModel.isDeletePending(contact.id) else { return }
    clearNavigationIfActive(.direct(contact))
    viewModel.removeConversation(.direct(contact))

    Task {
      do {
        try await viewModel.deleteDirectConversation(for: contact)
        // Confirm immediately rather than waiting for the reload; an inbound message that
        // re-sets lastMessageDate mid-delete would otherwise keep the row masked forever.
        viewModel.confirmDirectRemoval(contact)
        viewModel.requestConversationReload()
      } catch {
        viewModel.restoreConversation(.direct(contact))
        viewModel.errorMessage = error.userFacingMessage
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
        viewModel.removeConversation(.channel(channel))
      } catch {
        channelDeleteFailure.wrappedValue = ChatConversationActions.Failure(
          channel: channel,
          message: error.userFacingMessage
        )
        showChannelDeleteFailed.wrappedValue = true
      }
      viewModel.requestConversationReload()
    }
  }

  /// Room leave sends radio commands (logout, remove contact). The row keeps its spinner until
  /// they ack, then is hidden once; a failure leaves it in place and the trailing reload
  /// reconciles a partial failure rather than blindly re-inserting.
  func deleteRoom(_ session: RemoteNodeSessionDTO) async {
    guard !viewModel.isDeletePending(session.id) else { return }
    viewModel.deletingIDs.insert(session.id)
    defer { viewModel.deletingIDs.remove(session.id) }
    do {
      try await withTimeout(RadioCommandTimeout.delete, operationName: "leaveRoom") {
        try await ChatConversationActions.leaveRoom(session, appState: appState)
      }
      clearNavigationIfActive(.room(session))
      viewModel.removeConversation(.room(session))
    } catch {
      chatListActionsLogger.error("Failed to delete room: \(error)")
      viewModel.errorMessage = error.userFacingMessage
    }
    viewModel.requestConversationReload()
  }

  func handlePendingNavigation() {
    consumePending(route: appState.navigation.pendingChatContact.map { .direct($0) }) {
      appState.navigation.clearPendingNavigation()
    }
  }

  func handlePendingChannelNavigation() {
    consumePending(route: appState.navigation.pendingChannel.map { .channel($0) }) {
      appState.navigation.clearPendingChannelNavigation()
    }
  }

  func handlePendingRoomNavigation() {
    consumePending(route: appState.navigation.pendingRoomSession.map { .room($0) }) {
      appState.navigation.clearPendingRoomNavigation()
    }
  }

  /// Selects the pending route through the same `navigate` entry as a row tap,
  /// then clears only the intent this consumer handled.
  private func consumePending(route: ChatRoute?, clear: () -> Void) {
    guard let route else { return }
    navigate(route)
    clear()
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
