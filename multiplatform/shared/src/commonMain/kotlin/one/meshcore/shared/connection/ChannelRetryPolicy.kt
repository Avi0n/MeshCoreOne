package one.meshcore.shared.connection

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The channel-only retry schedule: how long the manager waits before each attempt at the slots a connect
 * pass left unread, and how many attempts it spends. Ported from the Swift
 * `ConnectionManager.maxChannelRetryAttempts` and `channelRetryInitialDelay`.
 *
 * The schedule is separate from [ResyncPolicy] because the two retry different things: a resync replays the
 * whole sync because the radio has no usable identity graph, while this one re-reads a handful of channel
 * slots on a connection that is otherwise healthy.
 *
 * Injected like [ReconnectPolicy] so the loop can be driven on a millisecond scale in tests while production
 * keeps the shipped schedule.
 */
class ChannelRetryPolicy(
    /** Attempts spent on the outstanding slots before the manager stops re-reading them. Swift's 2. */
    val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val initialDelay: Duration = DEFAULT_INITIAL_DELAY,
) {
    /**
     * The delay before channel retry [attempt] (1-based): `initial · 2^(attempt-1)` → 2s then 4s on the
     * shipped values, matching Swift's `max(channelRetryInitialDelay, .seconds(2 << (attempt - 1)))`.
     */
    fun backoff(attempt: Int): Duration {
        require(attempt >= 1) { "attempt is 1-based" }
        return initialDelay * (1 shl (attempt - 1))
    }

    companion object {
        /** Swift's `maxChannelRetryAttempts`. */
        const val DEFAULT_MAX_ATTEMPTS = 2

        /** Swift's `channelRetryInitialDelay`. */
        val DEFAULT_INITIAL_DELAY: Duration = 2.seconds
    }
}
