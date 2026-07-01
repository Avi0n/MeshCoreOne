import Foundation
@testable import MC1Services

extension ReactionDTO {
  /// Creates a ReactionDTO with sensible test defaults.
  ///
  /// Usage:
  /// ```
  /// let reaction = ReactionDTO.testReaction(messageID: myMessageID, radioID: myRadioID)
  /// ```
  static func testReaction(
    id: UUID = UUID(),
    messageID: UUID,
    radioID: UUID,
    emoji: String = "👍",
    senderName: String = "TestSender",
    messageHash: String = "a1b2c3d4",
    rawText: String = "+m:a1b2c3d4:👍",
    receivedAt: Date = Date(),
    channelIndex: UInt8? = 0,
    contactID: UUID? = nil
  ) -> ReactionDTO {
    ReactionDTO(
      id: id,
      messageID: messageID,
      emoji: emoji,
      senderName: senderName,
      messageHash: messageHash,
      rawText: rawText,
      receivedAt: receivedAt,
      channelIndex: channelIndex,
      contactID: contactID,
      radioID: radioID
    )
  }
}
