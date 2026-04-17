import Foundation
@testable import MC1Services

extension ChannelDTO {

    /// Creates a ChannelDTO with sensible test defaults.
    ///
    /// Usage:
    /// ```
    /// let channel = ChannelDTO.testChannel(radioID: myRadioID)
    /// let private = ChannelDTO.testChannel(radioID: myRadioID, index: 1, name: "Private")
    /// ```
    static func testChannel(
        id: UUID = UUID(),
        radioID: UUID,
        index: UInt8 = 0,
        name: String = "General",
        secret: Data = Data(repeating: 0, count: 16),
        isEnabled: Bool = true,
        lastMessageDate: Date? = nil,
        unreadCount: Int = 0,
        unreadMentionCount: Int = 0,
        notificationLevel: NotificationLevel = .all,
        isFavorite: Bool = false,
        regionScope: String? = nil
    ) -> ChannelDTO {
        ChannelDTO(
            id: id,
            radioID: radioID,
            index: index,
            name: name,
            secret: secret,
            isEnabled: isEnabled,
            lastMessageDate: lastMessageDate,
            unreadCount: unreadCount,
            unreadMentionCount: unreadMentionCount,
            notificationLevel: notificationLevel,
            isFavorite: isFavorite,
            regionScope: regionScope
        )
    }
}
