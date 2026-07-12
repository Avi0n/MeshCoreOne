import Foundation
import MeshCore

public enum RemoteOperationTimeoutPolicy {
  static let firmwareRoundTripMultiplier = 2
  static let loginMaximum: Duration = .seconds(20)
  public static let binaryMaximum: Duration = .seconds(15)
  static let cliMaximum: Duration = .seconds(15)
  static let fireAndForgetCLI: Duration = .seconds(2)
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
