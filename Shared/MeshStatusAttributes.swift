import ActivityKit
import Foundation

struct MeshStatusAttributes: ActivityAttributes {
  let deviceName: String

  /// Stable per-radio identity used to match a same-device reconnect (deviceName is a
  /// user-editable display value, not an identity axis). Optional so an activity persisted
  /// by an older build, whose attributes had no radioID, still decodes after an app update.
  let radioID: UUID?

  struct ContentState: Codable, Hashable {
    var isConnected: Bool
    var batteryPercent: Int?
    /// `nil` pauses the packet rate display while the device is locked.
    var packetsPerMinute: Int?
    var unreadCount: Int
    var disconnectedDate: Date?
    /// Failed DMs the user has not seen yet. Optional so an activity persisted by an
    /// older build still decodes.
    var failedSends: FailedSendSummary?

    /// An unseen failed DM is worth a line only while the radio is connected.
    var showsFailedSend: Bool {
      isConnected && (failedSends?.count ?? 0) > 0
    }

    var antennaIconName: String {
      isConnected
        ? "antenna.radiowaves.left.and.right"
        : "antenna.radiowaves.left.and.right.slash"
    }
  }
}

/// Unseen failed DMs, shown on the Live Activity as "No ACK".
struct FailedSendSummary: Codable, Hashable {
  var count: Int
  /// Repeaters heard forwarding the newest failed DM. Zero means nothing was heard.
  var repeatsHeard: Int
}
