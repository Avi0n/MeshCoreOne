import Foundation

/// Result of inserting a reaction and refreshing the parent summary from current rows.
public struct ReactionAdmission: Sendable, Equatable {
  public let messageID: UUID
  public let summary: String
  public let inserted: Bool

  public init(messageID: UUID, summary: String, inserted: Bool) {
    self.messageID = messageID
    self.summary = summary
    self.inserted = inserted
  }
}
