import Foundation

public struct RadioWiFiResetObservation: Sendable {
  let uptimeSeconds: UInt32
  let startedAt: ContinuousClock.Instant
  let completedAt: ContinuousClock.Instant

  private static let millisecondsPerSecond: UInt64 = 1000
  private static let millisPeriod = UInt64(UInt32.max) + 1
  private static let observationToleranceSeconds = 2.0
  private static let elapsedDriftAllowance = 0.01
  private static let attosecondsPerSecond = 1e18

  func provesReset(since earlier: Self) -> Bool {
    guard startedAt >= earlier.completedAt else { return false }
    let duration = earlier.startedAt.duration(to: completedAt).components
    let elapsed = Double(duration.seconds) + Double(duration.attoseconds) / Self.attosecondsPerSecond
    let upperBound = elapsed + Self.observationToleranceSeconds + elapsed * Self.elapsedDriftAllowance
    let periodSeconds = Double(Self.millisPeriod) / Double(Self.millisecondsPerSecond)
    guard upperBound < periodSeconds, Double(uptimeSeconds) <= upperBound else { return false }
    let earlierLow = UInt64(earlier.uptimeSeconds) * Self.millisecondsPerSecond
    let laterLow = UInt64(uptimeSeconds) * Self.millisecondsPerSecond
    guard earlierLow < Self.millisPeriod, laterLow < Self.millisPeriod else { return false }
    let earlierHigh = min(earlierLow + Self.millisecondsPerSecond - 1, Self.millisPeriod - 1)
    let laterHigh = min(laterLow + Self.millisecondsPerSecond - 1, Self.millisPeriod - 1)
    let minimumProgression: UInt64 = if laterLow <= earlierHigh, laterHigh >= earlierLow {
      0
    } else if laterLow > earlierHigh {
      laterLow - earlierHigh
    } else {
      Self.millisPeriod - earlierHigh + laterLow
    }
    return Double(minimumProgression) / Double(Self.millisecondsPerSecond) > upperBound
  }
}
