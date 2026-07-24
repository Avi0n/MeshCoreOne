import Foundation
import MeshCore

public enum RemoteOperationTimeoutPolicy {
  static let firmwareRoundTripMultiplier = 2
  static let loginMaximum: Duration = .seconds(20)
  /// Floor for login retransmit spacing while waiting for `loginSuccess`.
  /// Raised to firmware `suggestedTimeoutMs` when larger.
  static let loginRetransmitInterval: Duration = .seconds(1)
  /// Outer cap for remote binary / status / telemetry waits: one
  /// `binaryRequestOverallTimeout` exchange plus a small BLE margin.
  public static let binaryMaximum: Duration = .seconds(45)
  static let cliMaximum: Duration = .seconds(15)
  /// Default wait for a CLI reply. Generous because LoRa replies routinely
  /// take several seconds and waiting costs no airtime, unlike a resend.
  public static let defaultCLITimeout: Duration = .seconds(10)
  /// Wait for commands that get no reply by design (`reboot`); long enough to
  /// catch an error reply, short enough not to read as a failure to the user.
  public static let fireAndForgetCLI: Duration = .seconds(2)
  static let pollInterval: Duration = .milliseconds(500)

  static func firmwareRoundTripTimeout(from sentInfo: MessageSentInfo) -> Duration {
    .milliseconds(Int(sentInfo.suggestedTimeoutMs) * firmwareRoundTripMultiplier)
  }

  static func loginTimeout(for sentInfo: MessageSentInfo, pathLength: UInt8) -> Duration {
    min(max(firmwareRoundTripTimeout(from: sentInfo), LoginTimeoutConfig.timeout(forPathLength: pathLength)), loginMaximum)
  }

  static func cliTimeout(for sentInfo: MessageSentInfo, requestedTimeout: Duration) -> Duration {
    min(max(requestedTimeout, firmwareRoundTripTimeout(from: sentInfo)), cliMaximum)
  }
}
