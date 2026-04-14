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
}

// MARK: - Sendable DTO

public struct ReactionDTO: Sendable, Equatable, Hashable, Identifiable {
    public let id: UUID
    public let messageID: UUID
    public let emoji: String
    public let senderName: String
    public let messageHash: String
    public let rawText: String
    public let receivedAt: Date
    public let channelIndex: UInt8?
    public let contactID: UUID?
    public var radioID: UUID

    public init(from reaction: Reaction) {
        self.id = reaction.id
        self.messageID = reaction.messageID
        self.emoji = reaction.emoji
        self.senderName = reaction.senderName
        self.messageHash = reaction.messageHash
        self.rawText = reaction.rawText
        self.receivedAt = reaction.receivedAt
        self.channelIndex = reaction.channelIndex
        self.contactID = reaction.contactID
        self.radioID = reaction.radioID
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
