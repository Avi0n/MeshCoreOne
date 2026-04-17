import Foundation
@testable import MC1Services

extension BlockedChannelSenderDTO {

    /// Creates a BlockedChannelSenderDTO with sensible test defaults.
    ///
    /// Usage:
    /// ```
    /// let blocked = BlockedChannelSenderDTO.testBlockedSender(radioID: myRadioID)
    /// ```
    static func testBlockedSender(
        id: UUID = UUID(),
        name: String = "SpammerNode",
        radioID: UUID,
        dateBlocked: Date = Date()
    ) -> BlockedChannelSenderDTO {
        BlockedChannelSenderDTO(
            id: id,
            name: name,
            radioID: radioID,
            dateBlocked: dateBlocked
        )
    }
}
