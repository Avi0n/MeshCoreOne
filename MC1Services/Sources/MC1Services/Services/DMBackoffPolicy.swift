import Foundation

/// Schedule for the optional backoff rounds that follow a DM's normal retry budget.
/// Each round sends once on a fresh timestamp. The wait doubles every round.
public struct DMBackoffPolicy: Sendable, Equatable {
  /// Rounds a message opts into when backoff is on.
  public static let defaultRounds = 3

  /// Wait before the first round; later rounds double it.
  public var baseInterval: TimeInterval

  public init(baseInterval: TimeInterval = 120) {
    self.baseInterval = baseInterval
  }

  /// Wait before `round` (1-based): x, 2x, 4x, ...
  public func delay(forRound round: Int) -> TimeInterval {
    baseInterval * pow(2, Double(max(round, 1) - 1))
  }

  public static let `default` = DMBackoffPolicy()
}

/// A backoff round that was just scheduled.
public struct DMBackoffPlan: Sendable, Equatable {
  public let round: Int
  public let maxRounds: Int
  public let notBefore: Date
  /// Footer retry index and total, continuing the burst's count (see `Message.retryAttempt`).
  public let retryAttempt: Int
  public let maxRetryAttempts: Int
}
