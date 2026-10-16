package one.meshcore.shared.chat

import kotlinx.coroutines.delay
import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

private const val MILLISECONDS_PER_SECOND = 1000.0

/** The backoff delay for in-loop [attempt] (0-based), in milliseconds, given a sampled [jitter]. */
internal fun poolBackoffDelayMillis(attempt: Int, config: PoolBackoffConfig, jitter: Double): Long {
    val base = config.baseDelaySeconds * config.exponentBase.pow(attempt)
    return (base * jitter * MILLISECONDS_PER_SECOND).toLong()
}

/**
 * In-loop absorption of short pool-exhaustion bursts. The firmware briefly throws a
 * [ChatDeviceException] with the transient [transientCode] when its outbound pool is full; this sleeps
 * per [config] (default 500ms / 1s / 2s with +/-20% jitter) and re-throws once [PoolBackoffConfig.attemptCap]
 * is reached, so the caller (or the send queue) parks. Non-transient errors and other device codes
 * propagate immediately. Mirrors the Swift `withPoolBackoff`. [nextJitter] is injectable for
 * deterministic timing tests.
 */
internal fun poolBackoffExhaustedLine(attempt: Int, code: Int): String =
    "Pool backoff exhausted after $attempt attempts; re-throwing transient deviceError($code)"

suspend fun <T> withPoolBackoff(
    transientCode: Int,
    config: PoolBackoffConfig,
    nextJitter: () -> Double = { Random.nextDouble(config.jitterRange.start, config.jitterRange.endInclusive) },
    log: ((String) -> Unit)? = null,
    operation: suspend () -> T,
): T {
    var attempt = 0
    while (true) {
        try {
            return operation()
        } catch (error: ChatDeviceException) {
            if (error.firmwareCode != transientCode) throw error
            if (attempt >= config.attemptCap) {
                log?.invoke(poolBackoffExhaustedLine(attempt, transientCode))
                throw error
            }
            delay(poolBackoffDelayMillis(attempt, config, nextJitter()).milliseconds)
            attempt += 1
        }
    }
}
