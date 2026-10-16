package one.meshcore.shared.connection

import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Inner retry budget for a single BLE [ConnectionManager.connect] call. iOS `connectWithRetry` spends
 * [DEFAULT_MAX_ATTEMPTS] attempts with `0.3 · 2^(attempt-1)` seconds between them, and only trips the
 * circuit breaker after that budget is spent. A user-initiated tap ([forceReconnect]) on a platform
 * without a pairing registry (desktop, or Android without Companion Device Manager) uses
 * [UNVERIFIED_MAX_ATTEMPTS] so an out-of-range radio does not hang the full budget. Each inner
 * attempt's GATT ceiling is 10s (iOS `connectionTimeout`); two user-tap attempts are therefore ~20s,
 * not 2×40s of discovery. Injected so tests can run the loop on a millisecond scale.
 */
class ConnectRetryPolicy(
    val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    val unverifiedAttempts: Int = UNVERIFIED_MAX_ATTEMPTS,
    private val baseBackoff: Duration = DEFAULT_BASE_BACKOFF,
) {
    /**
     * Attempts for this [connect] call. iOS
     * `(forceReconnect && !hasSystemPairingRegistry) ? 2 : 4`. Companion Device Manager is the
     * ASK analog, so a CDM-capable phone keeps the full budget on a user tap.
     */
    fun attemptsFor(forceReconnect: Boolean, hasSystemPairingRegistry: Boolean = false): Int =
        if (forceReconnect && !hasSystemPairingRegistry) unverifiedAttempts else maxAttempts

    /**
     * Delay after failed [attempt] (1-based) before the next try. Zero after the last attempt — the
     * caller throws instead of waiting.
     */
    fun backoff(attempt: Int, forceReconnect: Boolean = false, hasSystemPairingRegistry: Boolean = false): Duration {
        require(attempt >= 1) { "attempt is 1-based" }
        if (attempt >= attemptsFor(forceReconnect, hasSystemPairingRegistry)) return Duration.ZERO
        return baseBackoff * 2.0.pow(attempt - 1)
    }

    companion object {
        /** Swift `ConnectionManager.defaultConnectAttempts`. */
        const val DEFAULT_MAX_ATTEMPTS = 4

        /** Swift `ConnectionManager.unverifiedConnectAttempts`. */
        const val UNVERIFIED_MAX_ATTEMPTS = 2

        /** Swift `connectWithRetry` base `0.3s`. */
        val DEFAULT_BASE_BACKOFF: Duration = 300.milliseconds
    }
}
