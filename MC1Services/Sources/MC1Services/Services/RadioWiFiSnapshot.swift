import Foundation

public enum RadioWiFiSnapshot: Sendable {
  static let enabledValue = "1"
  static let disabledValue = "0"
  static let connectedStatus = "connected"

  public struct Probe: Sendable {
    public let enabled: Bool
    let contextID: UUID
    public let revision: UInt64
  }

  public enum Capability: Sendable {
    case supported(Probe)
    case unavailable
    case busy
  }
}
