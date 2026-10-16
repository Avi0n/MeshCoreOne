package one.meshcore.shared.battery

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The pure low-battery notification state machine, ported from the iOS `BatteryMonitor` threshold logic.
 * It tracks which warning thresholds have already fired so each crossing notifies exactly once, and
 * re-arms a threshold when the level rises back above it.
 *
 * The battery percentage is computed elsewhere (the OCV curve lives with the device model); this class
 * takes an already-resolved percentage and reports whether the caller should post a low-battery
 * notification. It holds no clock — the sampling cadence constants are here for the caller's refresh
 * loop, and staleness is a pure comparison in [isFetchOverdue].
 */
class BatteryThresholdMonitor {
    private val notifiedThresholds = mutableSetOf<Int>()

    /** The thresholds already notified since the last reset — exposed for assertions and diagnostics. */
    val notified: Set<Int>
        get() = notifiedThresholds.toSet()

    /**
     * Seeds the notified set on a fresh connection: marks every currently-crossed threshold as already
     * notified so a reconnect at a low level does not re-alert. Returns whether the caller should post a
     * single notification because the level is already below a threshold.
     *
     * A missing percentage or an absent battery clears the set and reports no notification.
     */
    fun initialize(percentage: Int?, isBatteryPresent: Boolean): Boolean {
        if (percentage == null || !isBatteryPresent) {
            notifiedThresholds.clear()
            return false
        }
        val crossed = WARNING_THRESHOLDS.filter { percentage <= it }
        notifiedThresholds.clear()
        notifiedThresholds.addAll(crossed)
        return crossed.isNotEmpty()
    }

    /**
     * Checks a fresh percentage against the thresholds. Reports whether to post one notification: fires
     * for the first crossed, not-yet-notified threshold (highest first) and marks it; along the way it
     * re-arms any threshold the level has risen back above. At most one notification per call.
     */
    fun check(percentage: Int, isBatteryPresent: Boolean): Boolean {
        if (!isBatteryPresent) return false
        for (threshold in WARNING_THRESHOLDS) {
            if (percentage <= threshold && threshold !in notifiedThresholds) {
                notifiedThresholds.add(threshold)
                return true
            } else if (percentage > threshold && threshold in notifiedThresholds) {
                notifiedThresholds.remove(threshold)
            }
        }
        return false
    }

    /**
     * Foreground catch-up: marks every threshold missed while backgrounded as notified and reports
     * whether to post a single notification for them. Unlike [check] it does not re-arm risen thresholds.
     */
    fun checkMissed(percentage: Int, isBatteryPresent: Boolean): Boolean {
        if (!isBatteryPresent) return false
        val missed = WARNING_THRESHOLDS.filter { percentage <= it && it !in notifiedThresholds }
        if (missed.isEmpty()) return false
        notifiedThresholds.addAll(missed)
        return true
    }

    /** Resets the notified set on a fresh connection. */
    fun clearThresholds() {
        notifiedThresholds.clear()
    }

    companion object {
        /** Warning thresholds in percent, highest first (one notification per crossing). */
        val WARNING_THRESHOLDS: List<Int> = listOf(20, 10, 5)

        /** Foreground refresh cadence: re-fetch battery every 120s while connected. */
        val REFRESH_INTERVAL: Duration = 120.seconds

        /**
         * Background/overdue cadence: only re-fetch when at least this long since the last success,
         * piggybacking on BLE wake-ups when a Live Activity is visible.
         */
        val BACKGROUND_INTERVAL: Duration = 900.seconds

        /** Whether a battery fetch is overdue: never fetched, or at least [BACKGROUND_INTERVAL] ago. */
        fun isFetchOverdue(elapsedSinceLastSuccess: Duration?): Boolean =
            elapsedSinceLastSuccess == null || elapsedSinceLastSuccess >= BACKGROUND_INTERVAL
    }
}
