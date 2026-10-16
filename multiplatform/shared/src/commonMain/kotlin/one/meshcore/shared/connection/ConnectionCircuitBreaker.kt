package one.meshcore.shared.connection

/**
 * Prevents rapid auto-reconnect after a failed connect. Closed → open (30s cooldown) → half-open
 * (one probe). A user-initiated connect passes [force] and is never blocked. Matches Swift
 * `ConnectionManager` circuit breaker.
 */
class ConnectionCircuitBreaker(
    private val cooldownSeconds: Double = DEFAULT_COOLDOWN_SECONDS,
    private val nowEpochSeconds: () -> Double,
) {
    sealed class State {
        data object Closed : State()
        data class Open(val sinceEpochSeconds: Double) : State()
        data object HalfOpen : State()
    }

    var state: State = State.Closed
        private set

    fun shouldAllow(force: Boolean): Boolean {
        if (force) return true
        return when (val current = state) {
            is State.Closed -> true
            is State.Open -> {
                if (nowEpochSeconds() - current.sinceEpochSeconds >= cooldownSeconds) {
                    state = State.HalfOpen
                    true
                } else {
                    false
                }
            }
            is State.HalfOpen -> true
        }
    }

    fun recordFailure() {
        when (state) {
            is State.Closed, is State.HalfOpen -> state = State.Open(nowEpochSeconds())
            is State.Open -> Unit
        }
    }

    fun recordSuccess() {
        if (state is State.Closed) return
        state = State.Closed
    }

    fun setOpenForTesting(sinceEpochSeconds: Double) {
        state = State.Open(sinceEpochSeconds)
    }

    companion object {
        const val DEFAULT_COOLDOWN_SECONDS = 30.0
        const val BLOCKED_MESSAGE = "Connection blocked by circuit breaker (cooling down)"
    }
}
