import Foundation

extension MockDataProvider {
  /// Seeded channels with varied notification levels and a favorite, so the
  /// channel list exercises all/favorite/muted states. Saved via the DTO-based
  /// `saveChannel(_:)` (the wire `ChannelInfo` carries no notification/favorite state).
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
        lastMessageDate: now.addingTimeInterval(-1200),
        unreadCount: 2,
        notificationLevel: .all,
        isFavorite: false
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
        isFavorite: true
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
        isFavorite: false
      )
    ]
  }

  /// Deterministic 16-byte channel PSK from a seed.
  private static func channelSecret(seed: UInt8) -> Data {
    Data((0..<16).map { UInt8($0) &+ seed })
  }
}
