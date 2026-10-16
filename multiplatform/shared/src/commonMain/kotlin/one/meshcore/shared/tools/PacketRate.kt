package one.meshcore.shared.tools

import kotlin.math.roundToInt

/**
 * Received-packet rate over a trailing window, expressed per minute. Mirrors the iOS
 * `LiveActivityManager.packetsPerMinute`: a full-minute window makes the value the true count for that
 * minute (matching the RX Log) rather than extrapolating a burst. Pure so the rate math is unit-testable.
 */
object PacketRate {
    const val WINDOW_MS: Long = 60_000
    private const val MS_PER_MINUTE: Double = 60_000.0

    fun packetsPerMinute(timestampsMs: List<Long>, nowMs: Long, windowMs: Long = WINDOW_MS): Int {
        val cutoff = nowMs - windowMs
        val count = timestampsMs.count { it >= cutoff }
        return (count.toDouble() * MS_PER_MINUTE / windowMs).roundToInt()
    }
}
