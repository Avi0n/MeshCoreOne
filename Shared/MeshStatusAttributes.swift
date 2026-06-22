import ActivityKit
import Foundation

struct MeshStatusAttributes: ActivityAttributes, Sendable {
    let deviceName: String

    /// Stable per-radio identity used to match a same-device reconnect (deviceName is a
    /// user-editable display value, not an identity axis). Optional so an activity persisted
    /// by an older build, whose attributes had no radioID, still decodes after an app update.
    let radioID: UUID?

    struct ContentState: Codable, Hashable, Sendable {
        var isConnected: Bool
        var batteryPercent: Int?
        var packetsPerMinute: Int
        var unreadCount: Int
        var disconnectedDate: Date?

        var antennaIconName: String {
            isConnected
                ? "antenna.radiowaves.left.and.right"
                : "antenna.radiowaves.left.and.right.slash"
        }
    }
}
