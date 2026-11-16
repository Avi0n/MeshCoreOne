import Foundation

/// Tuning for the `withPoolBackoff` helper that absorbs short bursts of
/// firmware pool-exhaustion errors before ACK handoff or queue recovery.
struct PoolBackoffConfig {
  /// Maximum in-loop retries before re-throwing the transient `deviceError`.
  let attemptCap: Int

  /// Delay for the first in-loop retry (multiplied by `exponentBase` for
  /// subsequent attempts, then by a value sampled from `jitterRange`).
  let baseDelay: TimeInterval

  /// Exponential growth factor applied to `baseDelay` per attempt.
  let exponentBase: Double

  /// Multiplicative jitter envelope sampled per retry.
  let jitterRange: ClosedRange<Double>

  init(
    attemptCap: Int = 3,
    baseDelay: TimeInterval = 0.5,
    exponentBase: Double = 2.0,
    jitterRange: ClosedRange<Double> = 0.8...1.2
  ) {
    self.attemptCap = attemptCap
    self.baseDelay = baseDelay
    self.exponentBase = exponentBase
    self.jitterRange = jitterRange
  }

  static let `default` = PoolBackoffConfig()
}

/// Configuration for message retry and routing behavior.
///
/// Controls how the message service handles delivery failures and routing fallback.
struct MessageServiceConfig {
  /// Whether to use flood routing when user manually retries a failed message
  let floodFallbackOnRetry: Bool

  /// Minimum timeout in seconds (floor for device-suggested timeout)
  let minTimeout: TimeInterval

  /// Whether to trigger path discovery after successful flood delivery
  let triggerPathDiscoveryAfterFlood: Bool

  /// Floor and post-loop grace for the give-up deadline on fast presets.
  ///
  /// The effective deadline for each pending entry is `max(ackGiveUpWindow,
  /// PendingAck.timeout)`, so on slow high-spreading-factor presets the
  /// deadline follows the attempt's real round-trip and the checker never
  /// fires mid-attempt. On fast presets where `est_timeout` is small this
  /// value is the binding deadline, set large enough for a late ACK to still
  /// reconcile yet short enough to keep the silent "sending" tail brief.
  let ackGiveUpWindow: TimeInterval

  /// Tuning for the in-loop pool-exhaustion backoff (`withPoolBackoff`).
  let poolBackoff: PoolBackoffConfig

  init(
    floodFallbackOnRetry: Bool = true,
    minTimeout: TimeInterval = 0,
    triggerPathDiscoveryAfterFlood: Bool = true,
    ackGiveUpWindow: TimeInterval = 30,
    poolBackoff: PoolBackoffConfig = .default
  ) {
    self.floodFallbackOnRetry = floodFallbackOnRetry
    self.minTimeout = minTimeout
    self.triggerPathDiscoveryAfterFlood = triggerPathDiscoveryAfterFlood
    self.ackGiveUpWindow = ackGiveUpWindow
    self.poolBackoff = poolBackoff
  }

  static let `default` = MessageServiceConfig()
}
