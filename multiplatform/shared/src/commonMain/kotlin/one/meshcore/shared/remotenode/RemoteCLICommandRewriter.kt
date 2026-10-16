package one.meshcore.shared.remotenode

import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Rewrites remote `clock sync` to `time <host-epoch>`. Companion firmware restamps CLI packets
 * to its own RTC, so the packet timestamp is not the phone clock. Mirrors iOS
 * `RemoteCLICommandRewriter`.
 */
object RemoteCLICommandRewriter {
    const val CLOCK_SYNC_COMMAND: String = "clock sync"
    const val TIME_COMMAND_PREFIX: String = "time "

    private const val UINT32_MAX: Long = 4_294_967_295L

    @OptIn(ExperimentalTime::class)
    fun rewrite(command: String, nowSeconds: Long = Clock.System.now().epochSeconds): String {
        val normalized = command.trim().split(Regex("\\s+")).joinToString(" ").lowercase()
        if (normalized != CLOCK_SYNC_COMMAND) return command
        return TIME_COMMAND_PREFIX + epochSeconds32(nowSeconds).toString()
    }

    /** Saturates pre-1970 and post-2106 dates instead of wrapping. */
    private fun epochSeconds32(seconds: Long): Long = when {
        seconds <= 0L -> 0L
        seconds >= UINT32_MAX -> UINT32_MAX
        else -> seconds
    }
}
