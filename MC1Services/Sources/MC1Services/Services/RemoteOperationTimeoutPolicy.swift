import Foundation
import MeshCore

public enum RemoteOperationTimeoutPolicy {
  static let millisecondsPerSecond: TimeInterval = 1000
  static let firmwareRoundTripMultiplier = 2
  static let loginMaximum: Duration = .seconds(40)
  static let loginMinimumDirect: Duration = .seconds(5)
  static let loginPerHopDirect: Duration = .seconds(5)
  static let loginBudgetMultiplier: Double = 2.5
  /// Outer cap for remote binary / status / telemetry waits: one
  /// `binaryRequestOverallTimeout` exchange plus a small BLE margin.
  public static let binaryMaximum: Duration = .seconds(45)
  static let cliMaximum: Duration = .seconds(40)
  /// Default wait for a CLI reply. Generous because LoRa replies routinely
  /// take several seconds and waiting costs no airtime, unlike a resend.
  public static let defaultCLITimeout: Duration = .seconds(10)
  static let pathDiscoveryRoomSyncMinimum: TimeInterval = 10
  static let pathDiscoveryRoomSyncDefault: TimeInterval = 20
  static let pathDiscoveryRoomSyncMaximum: TimeInterval = 40
  static let pathDiscoveryRoomSyncMultiplier: Double = 1.2
  static let pathDiscoveryRoomSyncGrace: TimeInterval = 8
  /// Wait for commands that get no reply by design (`reboot`); long enough to
  /// catch an error reply, short enough not to read as a failure to the user.
  public static let fireAndForgetCLI: Duration = .seconds(2)
  static let pollInterval: Duration = .milliseconds(500)
  /// Pause after a repair flood probe succeeds so the remote node's 500 ms
  /// reciprocal-path delay and direct-route delivery settle before CLI retry.
  public static let reciprocalPathSettle: Duration = .seconds(2)
  public static let failedRepairWindow: Duration = .seconds(120)

  static func firmwareRoundTripTimeout(from sentInfo: MessageSentInfo) -> Duration {
    .milliseconds(Int(sentInfo.suggestedTimeoutMs) * firmwareRoundTripMultiplier)
  }

  static func roomSyncPathDiscoveryBudget(for sentInfo: MessageSentInfo) -> TimeInterval {
    guard sentInfo.suggestedTimeoutMs > 0 else {
      return pathDiscoveryRoomSyncDefault
    }
    let seconds = Double(sentInfo.suggestedTimeoutMs) / millisecondsPerSecond
    let scaled = seconds * pathDiscoveryRoomSyncMultiplier + pathDiscoveryRoomSyncGrace
    return min(max(scaled, pathDiscoveryRoomSyncMinimum), pathDiscoveryRoomSyncMaximum)
  }

  static func loginTimeout(for sentInfo: MessageSentInfo, pathLength: UInt8) -> Duration {
    let isFlood = sentInfo.route == MessageSentInfo.floodRoute
    let hopFloor: Duration = if !isFlood, let decoded = decodePathLen(pathLength) {
      loginMinimumDirect + loginPerHopDirect * decoded.hopCount
    } else {
      loginMaximum
    }
    let hintBudget = Duration.milliseconds(
      Int((Double(sentInfo.suggestedTimeoutMs) * loginBudgetMultiplier).rounded(.up))
    )
    return min(max(hintBudget, hopFloor), loginMaximum)
  }

  static func cliTimeout(
    for sentInfo: MessageSentInfo,
    requestedTimeout: Duration,
    allowFirmwareExpansion: Bool = true
  ) -> Duration {
    guard allowFirmwareExpansion else {
      return min(requestedTimeout, cliMaximum)
    }
    return min(max(requestedTimeout, firmwareRoundTripTimeout(from: sentInfo)), cliMaximum)
  }
}
