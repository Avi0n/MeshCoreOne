package one.meshcore.shared.chat

/**
 * Tuning for [withPoolBackoff], which absorbs short firmware pool-exhaustion bursts before parking the
 * send. Mirrors the Swift `PoolBackoffConfig`.
 */
data class PoolBackoffConfig(
    /** Maximum in-loop retries before re-throwing the transient device error. */
    val attemptCap: Int = ChatConstants.POOL_BACKOFF_ATTEMPT_CAP,
    /** Delay for the first in-loop retry, in seconds (grown by [exponentBase] per attempt). */
    val baseDelaySeconds: Double = ChatConstants.POOL_BACKOFF_BASE_DELAY_SECONDS,
    /** Exponential growth factor applied to [baseDelaySeconds] per attempt. */
    val exponentBase: Double = ChatConstants.POOL_BACKOFF_EXPONENT_BASE,
    /** Inclusive multiplicative jitter envelope sampled per retry. */
    val jitterRange: ClosedFloatingPointRange<Double> =
        ChatConstants.POOL_BACKOFF_JITTER_MIN..ChatConstants.POOL_BACKOFF_JITTER_MAX,
)

/**
 * Configuration for DM retry and routing behavior. Mirrors the Swift `MessageServiceConfig`;
 * [maxAttempts] must stay `<= 5` (4 direct + 1 flood) so the attempt index cannot over-wrap the
 * `& 0x03` ACK mask (see [AckCodeBuilder]).
 */
data class MessageServiceConfig(
    val floodFallbackOnRetry: Boolean = true,
    val maxAttempts: Int = ChatConstants.MAX_ATTEMPTS,
    val maxFloodAttempts: Int = ChatConstants.MAX_FLOOD_ATTEMPTS,
    val floodAfter: Int = ChatConstants.FLOOD_AFTER,
    val minTimeoutSeconds: Double = ChatConstants.MIN_TIMEOUT_SECONDS,
    val triggerPathDiscoveryAfterFlood: Boolean = true,
    /**
     * Floor and post-loop grace for the give-up deadline on fast presets. The effective deadline for a
     * pending entry is `max(ackGiveUpWindowSeconds, PendingAck.timeoutSeconds)`, so on slow
     * high-spreading-factor presets the deadline follows the attempt's real round-trip.
     */
    val ackGiveUpWindowSeconds: Double = ChatConstants.ACK_GIVE_UP_WINDOW_SECONDS,
    val poolBackoff: PoolBackoffConfig = PoolBackoffConfig(),
) {
    init {
        require(maxAttempts <= ChatConstants.MAX_ATTEMPTS) {
            "maxAttempts must be <= ${ChatConstants.MAX_ATTEMPTS} (4 direct + 1 flood)"
        }
    }
}
