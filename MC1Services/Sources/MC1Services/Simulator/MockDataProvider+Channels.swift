import Foundation

extension MockDataProvider {
  /// Seeded channels with varied notification levels and a pin, so the
  /// channel list exercises all/pinned/muted states. Saved via the DTO-based
  /// `saveChannel(_:)` (the wire `ChannelInfo` carries no notification/pin state).
  public static var channels: [ChannelDTO] {
    let now = Date()
    return [
      ChannelDTO(
        id: publicChannelID,
        radioID: simulatorDeviceID,
        index: publicChannelIndex,
        name: "Public",
        secret: channelSecret(seed: 0xA0),
        isEnabled: true,
        lastMessageDate: now.addingTimeInterval(-20),
        unreadCount: 2,
        notificationLevel: .all,
        isPinned: false
      ),
      ChannelDTO(
        id: bayAreaChannelID,
        radioID: simulatorDeviceID,
        index: bayAreaChannelIndex,
        name: "Bay Area",
        secret: channelSecret(seed: 0xB0),
        isEnabled: true,
        lastMessageDate: now.addingTimeInterval(-3600),
        unreadCount: 1,
        unreadMentionCount: 1,
        notificationLevel: .all,
        isPinned: true
      ),
      ChannelDTO(
        id: trailCrewChannelID,
        radioID: simulatorDeviceID,
        index: trailCrewChannelIndex,
        name: "Trail Crew",
        secret: channelSecret(seed: 0xC0),
        isEnabled: true,
        lastMessageDate: now.addingTimeInterval(-7200),
        unreadCount: 0,
        notificationLevel: .muted,
        isPinned: false
      ),
      // Long backlog whose unread count exceeds one page (pageSize is 50), so the
      // first-unread message — where the "New Messages" divider belongs — only
      // exists once the initial load is sized to cover all unread. Exercises both
      // the jump-to-divider scroll and the all-unread-in-one-page load sizing.
      ChannelDTO(
        id: meshHQChannelID,
        radioID: simulatorDeviceID,
        index: meshHQChannelIndex,
        name: "Mesh HQ",
        secret: channelSecret(seed: 0xD0),
        isEnabled: true,
        lastMessageDate: now.addingTimeInterval(-90),
        unreadCount: meshHQUnreadCount,
        notificationLevel: .all,
        isPinned: false
      )
    ]
  }

  /// Deterministic 16-byte channel PSK from a seed.
  private static func channelSecret(seed: UInt8) -> Data {
    Data((0..<16).map { UInt8($0) &+ seed })
  }
}
