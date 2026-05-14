import Foundation

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

    public init(
        floodFallbackOnRetry: Bool = true,
        maxAttempts: Int = 4,
        maxFloodAttempts: Int = 2,
        floodAfter: Int = 2,
        minTimeout: TimeInterval = 0,
        triggerPathDiscoveryAfterFlood: Bool = true
    ) {
        precondition(maxAttempts <= 4, "firmware AckCodeBuilder masks attempt & 0x03 — values > 4 produce ambiguous ACKs")
        self.floodFallbackOnRetry = floodFallbackOnRetry
        self.maxAttempts = maxAttempts
        self.maxFloodAttempts = maxFloodAttempts
        self.floodAfter = floodAfter
        self.minTimeout = minTimeout
        self.triggerPathDiscoveryAfterFlood = triggerPathDiscoveryAfterFlood
    }

    public static let `default` = MessageServiceConfig()
}
