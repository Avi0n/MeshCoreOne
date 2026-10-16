import Foundation

extension MockDataProvider {
  /// Messages that carry seeded reactions, paired with the denormalized badge
  /// summary. `saveMessage` drops `reactionSummary`, so the summary is written via
  /// `updateMessageReactionSummary`; the per-reactor rows render the detail list.
  /// Each summary's counts match the rows returned by `reactions(for:)`.
  static let reactedMessages: [(messageID: UUID, summary: String)] = [
    (aliceReactedMessageID, "👍:2,❤️:1"),
    (bayAreaReactedMessageID, "🎉:2"),
    (bayAreaMentionMessageID, "👍:1")
  ]

  /// Per-reactor reaction rows for the reactor-detail list.
  static func reactions(for messageID: UUID) -> [ReactionDTO] {
    switch messageID {
    case aliceReactedMessageID:
      [
        reaction("A0000000-0000-0000-0000-000000000001", messageID, "👍", "You", contactID: aliceChenID),
        reaction("A0000000-0000-0000-0000-000000000002", messageID, "👍", "Bob Martinez", contactID: aliceChenID),
        reaction("A0000000-0000-0000-0000-000000000003", messageID, "❤️", "Alice Chen", contactID: aliceChenID)
      ]
    case bayAreaReactedMessageID:
      [
        reaction("A1000000-0000-0000-0000-000000000001", messageID, "🎉", "Alice Chen", channelIndex: bayAreaChannelIndex),
        reaction("A1000000-0000-0000-0000-000000000002", messageID, "🎉", "Bob Martinez", channelIndex: bayAreaChannelIndex)
      ]
    case bayAreaMentionMessageID:
      [
        reaction("A1000000-0000-0000-0000-000000000003", messageID, "👍", "Sim", channelIndex: bayAreaChannelIndex)
      ]
    default:
      []
    }
  }

  private static func reaction(
    _ id: String,
    _ messageID: UUID,
    _ emoji: String,
    _ sender: String,
    contactID: UUID? = nil,
    channelIndex: UInt8? = nil
  ) -> ReactionDTO {
    ReactionDTO(
      id: UUID(uuidString: id)!,
      messageID: messageID,
      emoji: emoji,
      senderName: sender,
      messageHash: String(messageID.uuidString.prefix(8)),
      rawText: emoji,
      channelIndex: channelIndex,
      contactID: contactID,
      radioID: simulatorDeviceID
    )
  }
}
