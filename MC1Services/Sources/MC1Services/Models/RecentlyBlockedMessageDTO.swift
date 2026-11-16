import Foundation

public struct RecentlyBlockedMessageDTO: Identifiable, Sendable, Equatable {
  public struct Identity: Sendable, Equatable {
    public let radioID: UUID
    public let conversationID: UUID?
    public let senderPrefix: Data?
    public let senderTimestamp: UInt32
    public let textType: UInt8?
    public let wireText: Data

    public init(
      radioID: UUID,
      conversationID: UUID?,
      senderPrefix: Data?,
      senderTimestamp: UInt32,
      textType: UInt8?,
      wireText: Data
    ) {
      self.radioID = radioID
      self.conversationID = conversationID
      self.senderPrefix = senderPrefix
      self.senderTimestamp = senderTimestamp
      self.textType = textType
      self.wireText = wireText
    }
  }

  public let id: UUID
  public let identity: Identity
  public let arrivedAt: Date
  public let body: String
  public let sender: String?
  public let conversationName: String?
  public let radioName: String?
  public let match: TextRulesPreference.RuleMatch
  public let contactID: UUID?

  public init(
    id: UUID = UUID(),
    identity: Identity,
    arrivedAt: Date,
    body: String,
    sender: String?,
    conversationName: String?,
    radioName: String?,
    match: TextRulesPreference.RuleMatch,
    contactID: UUID? = nil
  ) {
    self.id = id
    self.identity = identity
    self.arrivedAt = arrivedAt
    self.body = body
    self.sender = sender
    self.conversationName = conversationName
    self.radioName = radioName
    self.match = match
    self.contactID = contactID
  }

  init(from entry: RecentlyBlockedMessage) throws {
    id = entry.id
    identity = entry.identity
    arrivedAt = entry.arrivedAt
    body = entry.body
    sender = entry.sender
    conversationName = entry.conversationName
    radioName = entry.radioName
    match = try JSONDecoder().decode(TextRulesPreference.RuleMatch.self, from: entry.matchData)
    contactID = entry.contactID
  }
}
