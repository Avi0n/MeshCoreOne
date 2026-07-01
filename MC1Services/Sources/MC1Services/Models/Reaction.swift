import Foundation
import SwiftData

/// Represents an emoji reaction to a channel or DM message.
@Model
public final class Reaction {
  #Index<Reaction>(
    [\.messageID],
    [\.radioID, \.contactID, \.messageID],
    [\.messageID, \.senderName, \.emoji]
  )

  @Attribute(.unique)
  public var id: UUID

  /// Target message UUID
  public var messageID: UUID

  /// The emoji used
  public var emoji: String

  /// Sender's node name
  public var senderName: String

  /// Message hash from wire format (8 hex chars)
  public var messageHash: String

  /// Original raw text for fallback display
  public var rawText: String

  /// When we received this reaction
  public var receivedAt: Date

  /// Channel index where received (nil for DM reactions)
  public var channelIndex: UInt8?

  /// Contact ID for DM reactions (nil for channel reactions)
  public var contactID: UUID?

  /// Device ID this belongs to
  @Attribute(originalName: "deviceID")
  public var radioID: UUID

  public init(
    id: UUID = UUID(),
    messageID: UUID,
    emoji: String,
    senderName: String,
    messageHash: String,
    rawText: String,
    receivedAt: Date = Date(),
    channelIndex: UInt8? = nil,
    contactID: UUID? = nil,
    radioID: UUID
  ) {
    self.id = id
    self.messageID = messageID
    self.emoji = emoji
    self.senderName = senderName
    self.messageHash = messageHash
    self.rawText = rawText
    self.receivedAt = receivedAt
    self.channelIndex = channelIndex
    self.contactID = contactID
    self.radioID = radioID
  }

  /// Builds a model instance directly from a DTO.
  public convenience init(dto: ReactionDTO) {
    self.init(
      id: dto.id,
      messageID: dto.messageID,
      emoji: dto.emoji,
      senderName: dto.senderName,
      messageHash: dto.messageHash,
      rawText: dto.rawText,
      receivedAt: dto.receivedAt,
      channelIndex: dto.channelIndex,
      contactID: dto.contactID,
      radioID: dto.radioID
    )
  }
}

// MARK: - Sendable DTO

public struct ReactionDTO: Sendable, Equatable, Hashable, Identifiable, Codable {
  public let id: UUID
  public var messageID: UUID
  public let emoji: String
  public let senderName: String
  public let messageHash: String
  public let rawText: String
  public let receivedAt: Date
  /// Mutable so backup import can rewrite it in lockstep with the parent channel
  /// message when a channel relocates to a different local slot.
  public var channelIndex: UInt8?
  public var contactID: UUID?
  public var radioID: UUID

  public init(from reaction: Reaction) {
    id = reaction.id
    messageID = reaction.messageID
    emoji = reaction.emoji
    senderName = reaction.senderName
    messageHash = reaction.messageHash
    rawText = reaction.rawText
    receivedAt = reaction.receivedAt
    channelIndex = reaction.channelIndex
    contactID = reaction.contactID
    radioID = reaction.radioID
  }

  public init(
    id: UUID = UUID(),
    messageID: UUID,
    emoji: String,
    senderName: String,
    messageHash: String,
    rawText: String,
    receivedAt: Date = Date(),
    channelIndex: UInt8? = nil,
    contactID: UUID? = nil,
    radioID: UUID
  ) {
    self.id = id
    self.messageID = messageID
    self.emoji = emoji
    self.senderName = senderName
    self.messageHash = messageHash
    self.rawText = rawText
    self.receivedAt = receivedAt
    self.channelIndex = channelIndex
    self.contactID = contactID
    self.radioID = radioID
  }
}
