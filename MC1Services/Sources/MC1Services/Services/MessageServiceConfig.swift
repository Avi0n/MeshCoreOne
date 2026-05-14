import Foundation

/// Tuning for the `withPoolBackoff` helper that absorbs short bursts of
/// firmware pool-exhaustion errors before parking the envelope.
public struct PoolBackoffConfig: Sendable {
    /// Maximum in-loop retries before re-throwing the transient `deviceError`.
    public let attemptCap: Int

    /// Delay for the first in-loop retry (multiplied by `exponentBase` for
    /// subsequent attempts, then by a value sampled from `jitterRange`).
    public let baseDelay: TimeInterval

    /// Exponential growth factor applied to `baseDelay` per attempt.
    public let exponentBase: Double

    /// Multiplicative jitter envelope sampled per retry.
    public let jitterRange: ClosedRange<Double>

    public init(
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

    public static let `default` = PoolBackoffConfig()
}

/// Configuration for message retry and routing behavior.
///
/// Controls how the message service handles delivery failures and routing fallback.
public struct MessageServiceConfig: Sendable {
    /// Whether to use flood routing when user manually retries a failed message
    public let floodFallbackOnRetry: Bool

    /// Maximum total send attempts for automatic retry
    public let maxAttempts: Int

    /// Maximum attempts to make after switching to flood routing
    public let maxFloodAttempts: Int

    /// Number of direct attempts before switching to flood routing
    public let floodAfter: Int

    /// Minimum timeout in seconds (floor for device-suggested timeout)
    public let minTimeout: TimeInterval

    /// Whether to trigger path discovery after successful flood delivery
    public let triggerPathDiscoveryAfterFlood: Bool

    /// Tuning for the in-loop pool-exhaustion backoff (`withPoolBackoff`).
    public let poolBackoff: PoolBackoffConfig

    public init(
        floodFallbackOnRetry: Bool = true,
        maxAttempts: Int = 4,
        maxFloodAttempts: Int = 2,
        floodAfter: Int = 2,
        minTimeout: TimeInterval = 0,
        triggerPathDiscoveryAfterFlood: Bool = true,
        poolBackoff: PoolBackoffConfig = .default
    ) {
        precondition(maxAttempts <= 4, "firmware AckCodeBuilder masks attempt & 0x03 — values > 4 produce ambiguous ACKs")
        self.floodFallbackOnRetry = floodFallbackOnRetry
        self.maxAttempts = maxAttempts
        self.maxFloodAttempts = maxFloodAttempts
        self.floodAfter = floodAfter
        self.minTimeout = minTimeout
        self.triggerPathDiscoveryAfterFlood = triggerPathDiscoveryAfterFlood
        self.poolBackoff = poolBackoff
    }

    public static let `default` = MessageServiceConfig()
}
