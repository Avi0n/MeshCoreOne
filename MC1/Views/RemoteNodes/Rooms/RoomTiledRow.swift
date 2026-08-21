import Foundation
import MC1Services

/// Tiled room item. Chrome flags live here so `ChatTiledView` reconfigures
/// when a cluster follow-up hides the previous avatar (DTO equality would not).
struct RoomTiledRow: Identifiable, Hashable, Sendable {
  let message: RoomMessageDTO
  let showTimestamp: Bool
  let showSenderName: Bool
  let showAvatar: Bool

  var id: UUID {
    message.id
  }
}
