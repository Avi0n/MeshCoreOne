import Foundation

struct MeshResendPolicy: Sendable {
  let kind: MeshExchangeKind
  let priority: MeshRequestPriority
  let retransmitFloor: TimeInterval?

  func maxSends(route: UInt8) -> Int {
    if kind == .pathDiscovery || retransmitFloor == nil {
      return SessionConfiguration.singleSendMaxSends
    }
    if kind == .login {
      return SessionConfiguration.loginMaxSends
    }
    if route == MessageSentInfo.floodRoute || priority == .background {
      return SessionConfiguration.floodOrBackgroundMaxSends
    }
    return Int.max
  }

  func resendSpacing(suggestedTimeoutMs: UInt32, route: UInt8) -> TimeInterval {
    let floor = retransmitFloor ?? MeshRequestGate.minimumSameContactHold
    let estimate = TimeInterval(suggestedTimeoutMs) / SessionConfiguration.millisecondsPerSecond
    if kind == .login {
      let effectiveFloor = route == MessageSentInfo.floodRoute
        ? max(floor, SessionConfiguration.loginFloodRetransmitFloor)
        : floor
      return max(effectiveFloor, estimate * SessionConfiguration.loginRetransmitRTTMultiplier)
    }
    return max(floor, estimate * SessionConfiguration.binaryRetransmitRTTHeadroom)
  }

  func holdDuration(suggestedTimeoutMs: UInt32) -> TimeInterval {
    let floor = retransmitFloor ?? MeshRequestGate.minimumSameContactHold
    let estimate = TimeInterval(suggestedTimeoutMs) / SessionConfiguration.millisecondsPerSecond
    return max(floor, estimate * SessionConfiguration.binaryRetransmitRTTHeadroom)
  }

  func hasUsefulTailAfterResend(nextResend: Duration, deadline: Duration, spacing: TimeInterval) -> Bool {
    (deadline - nextResend) >= .seconds(spacing * SessionConfiguration.minimumUsefulResendTailRatio)
  }

  func shouldTrimAfterFinalSend(route: UInt8, suggestedTimeoutMs: UInt32) -> Bool {
    guard kind != .login, kind != .pathDiscovery, suggestedTimeoutMs > 0 else {
      return false
    }
    return route == MessageSentInfo.floodRoute || priority == .background
  }
}
