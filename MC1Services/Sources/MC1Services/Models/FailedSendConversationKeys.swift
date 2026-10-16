import Foundation

/// Conversation identities with at least one unseen outgoing `.failed` send.
/// Channel IDs are resolved under the query `radioID` so list mapping cannot attach another radio's slot.
public struct FailedSendConversationKeys: Sendable, Equatable {
  public let contactIDs: Set<UUID>
  public let channelIDs: Set<UUID>
  public let roomSessionIDs: Set<UUID>

  public init(
    contactIDs: Set<UUID> = [],
    channelIDs: Set<UUID> = [],
    roomSessionIDs: Set<UUID> = []
  ) {
    self.contactIDs = contactIDs
    self.channelIDs = channelIDs
    self.roomSessionIDs = roomSessionIDs
  }

  public static let empty = FailedSendConversationKeys()
}
