package one.meshcore.shared.connection

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The post-connect resync schedule: how long the manager waits between resync attempts and how many it
 * spends before declaring the connection unusable. Ported from the Swift `ConnectionManager.resyncInterval`
 * and `maxResyncAttempts`.
 *
 * Injected like [ReconnectPolicy] so the loop can be driven on a millisecond scale in tests while
 * production keeps the shipped schedule.
 */
class ResyncPolicy(
    /** Wait between resync attempts. Swift's 2s. */
    val interval: Duration = DEFAULT_INTERVAL,
    /** Attempts spent before the sync-failed pill is raised and the link is dropped. Swift's 3. */
    val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
) {
    companion object {
        val DEFAULT_INTERVAL: Duration = 2.seconds
        const val DEFAULT_MAX_ATTEMPTS = 3
    }
}
