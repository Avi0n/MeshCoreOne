import MC1Services
import SwiftUI

/// Service sequences for deleting channels and leaving rooms. Navigation updates
/// stay in the caller so both the split host and list column share one route.
enum ChatConversationActions {
  /// A channel deletion that failed, surfaced in a retry alert.
  struct Failure {
    let channel: ChannelDTO
    let message: String
  }

  enum DeleteError: LocalizedError {
    case servicesUnavailable

    var errorDescription: String? {
      switch self {
      case .servicesUnavailable: L10n.Chats.Chats.Error.servicesUnavailable
      }
    }
  }

  /// Clears a channel on the radio, then removes its delivered notifications and refreshes the badge.
  @MainActor
  static func deleteChannel(_ channel: ChannelDTO, appState: AppState) async throws {
    guard let channelService = appState.services?.channelService else {
      throw DeleteError.servicesUnavailable
    }
    try await channelService.clearChannel(radioID: channel.radioID, index: channel.index)
    await appState.services?.notificationService.removeDeliveredNotifications(
      forChannelIndex: channel.index,
      radioID: channel.radioID
    )
    await appState.services?.notificationService.updateBadgeCount()
  }

  /// Leaves a room session and removes its backing contact, then refreshes the badge.
  @MainActor
  static func leaveRoom(_ session: RemoteNodeSessionDTO, appState: AppState) async throws {
    try await appState.services?.roomServerService.leaveRoom(
      sessionID: session.id,
      publicKey: session.publicKey
    )
    try await appState.services?.contactService.removeContact(
      radioID: session.radioID,
      publicKey: session.publicKey
    )
    await appState.services?.notificationService.updateBadgeCount()
  }
}
