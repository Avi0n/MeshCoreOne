package one.meshcore.shared.connection

import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The WiFi reconnection schedule, a deliberately separate variant from the BLE [ReconnectPolicy]. The
 * companion protocol over TCP flaps differently from BLE, so Swift's `ConnectionManager+WiFi` gives it
 * its own timings: a fast capped exponential backoff, a short cooldown between reconnect *runs*, and a
 * bounded total window per run.
 *
 * Backoff for `attempt` (1-based, as Swift counts): `min(0.5 · 2^(attempt-1), 4)` seconds →
 * 0.5, 1, 2, 4, 4, … capped at 4s. The heartbeat interval lives here too because the same file owns the
 * WiFi liveness cadence.
 */
class WiFiReconnectPolicy(
    /** Minimum interval between reconnect *runs* — anti-flap. Swift's `wifiReconnectCooldown`. */
    val cooldown: Duration = DEFAULT_COOLDOWN,
    /** Total budget for a single reconnect run before giving up. Swift's `wifiMaxReconnectDuration`. */
    val window: Duration = DEFAULT_WINDOW,
    /** App-level liveness probe cadence (ESP32 ignores TCP keepalives). Swift's `wifiHeartbeatInterval`. */
    val heartbeatInterval: Duration = DEFAULT_HEARTBEAT_INTERVAL,
    private val baseBackoff: Duration = DEFAULT_BASE_BACKOFF,
    private val maxBackoff: Duration = DEFAULT_MAX_BACKOFF,
) {
    /**
     * The delay before reconnect [attempt] (1-based): `min(base · 2^(attempt-1), max)`. Attempt 1 waits
     * one base interval; each subsequent attempt doubles up to the cap.
     */
    fun backoff(attempt: Int): Duration {
        require(attempt >= 1) { "attempt is 1-based" }
        return minOf(baseBackoff * 2.0.pow(attempt - 1), maxBackoff)
    }

    companion object {
        private val DEFAULT_COOLDOWN = 35.seconds
        private val DEFAULT_WINDOW = 30.seconds
        private val DEFAULT_HEARTBEAT_INTERVAL = 30.seconds
        private val DEFAULT_BASE_BACKOFF = 500.milliseconds
        private val DEFAULT_MAX_BACKOFF = 4.seconds
    }
}
