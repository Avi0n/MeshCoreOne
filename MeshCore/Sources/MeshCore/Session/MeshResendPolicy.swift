import Foundation

struct MeshResendPolicy: Sendable {
  let kind: MeshExchangeKind
  let priority: MeshRequestPriority
  let retransmitFloor: TimeInterval?

  func maxSends(route: UInt8) -> Int {
    if kind == .pathDiscovery || retransmitFloor == nil {
      return SessionConfiguration.singleSendMaxSends
    }
    if route == MessageSentInfo.floodRoute || priority == .background {
      return SessionConfiguration.floodOrBackgroundMaxSends
    }
    return Int.max
  }

  func spacing(suggestedTimeoutMs: UInt32) -> TimeInterval {
    let floor = retransmitFloor ?? MeshRequestGate.minimumSameContactHold
    let estimate = TimeInterval(suggestedTimeoutMs) / SessionConfiguration.millisecondsPerSecond
    return max(floor, estimate * SessionConfiguration.binaryRetransmitRTTHeadroom)
  }

  func shouldTrimAfterFinalSend(route: UInt8, suggestedTimeoutMs: UInt32) -> Bool {
    guard kind != .login, kind != .pathDiscovery, suggestedTimeoutMs > 0 else {
      return false
    }
    return route == MessageSentInfo.floodRoute || priority == .background
  }
}
