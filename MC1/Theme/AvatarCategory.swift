import Foundation

/// The three avatar categories that get one fixed color per theme (no per-entity variation):
/// channels in the Chats list, and repeater / room-server nodes in the Nodes list.
enum AvatarCategory {
  case channel
  case repeater
  case room

  /// Order the category avatars claim gamut anchors in. On a collision the later category steps to
  /// the next free anchor, so earlier categories keep their preferred color. A channel and a room
  /// can share a Chats list, so their colors must stay distinct.
  static let anchorPriority: [AvatarCategory] = [.channel, .repeater, .room]

  /// Stable seed fed to a theme's `IdentityGamut` so each category resolves to a distinct,
  /// on-theme color. Prefixed to keep it from colliding with a real contact or channel name.
  var gamutSeed: String {
    switch self {
    case .channel: "__avatar_category_channel__"
    case .repeater: "__avatar_category_repeater__"
    case .room: "__avatar_category_room__"
    }
  }
}
