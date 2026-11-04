import Foundation
import SwiftData

@Model
final class RecentlyBlockedMessage {
  #Index<RecentlyBlockedMessage>([\.radioID, \.senderTimestamp])

  @Attribute(.unique)
  var id: UUID
  var radioID: UUID
  var conversationID: UUID?
  var senderPrefix: Data?
  var senderTimestamp: UInt32
  var textTypeRawValue: UInt8?
  var wireText: Data

  var arrivedAt: Date
  var body: String
  var sender: String?
  var conversationName: String?
  var radioName: String?
  var matchData: Data
  var contactID: UUID?

  init(_ entry: RecentlyBlockedMessageDTO) throws {
    id = entry.id
    radioID = entry.identity.radioID
    conversationID = entry.identity.conversationID
    senderPrefix = entry.identity.senderPrefix
    senderTimestamp = entry.identity.senderTimestamp
    textTypeRawValue = entry.identity.textType
    wireText = entry.identity.wireText
    arrivedAt = entry.arrivedAt
    body = entry.body
    sender = entry.sender
    conversationName = entry.conversationName
    radioName = entry.radioName
    matchData = try JSONEncoder().encode(entry.match)
    contactID = entry.contactID
  }

  var identity: RecentlyBlockedMessageDTO.Identity {
    RecentlyBlockedMessageDTO.Identity(
      radioID: radioID,
      conversationID: conversationID,
      senderPrefix: senderPrefix,
      senderTimestamp: senderTimestamp,
      textType: textTypeRawValue,
      wireText: wireText
    )
  }
}
