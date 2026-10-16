package one.meshcore.chartbench

import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Records [FrameMetrics.TOTAL_DURATION] for one measured loop.
 * The median matches the historical live-update statistic: sort the delivered totals and average the two
 * middle values when the count is even. Nearest-rank P95 and P99 are reported beside it.
 * Dropped callbacks are counted and are not filled in, because a drop has no duration to include.
 */
internal class ChartFrameMetrics(private val workload: String) {
    private val thread = HandlerThread(THREAD_NAME).apply { start() }
    private val totalsNs = mutableListOf<Long>()
    private var dropped = 0
    private var closed = false

    val handler: Handler = Handler(thread.looper)

    val listener = Window.OnFrameMetricsAvailableListener { _, metrics, dropCount ->
        dropped += dropCount
        totalsNs.add(metrics.getMetric(FrameMetrics.TOTAL_DURATION))
    }

    /** Removes the listener, then formats the frames already queued on [handler]. */
    fun drain(window: Window): String {
        if (closed) return ""
        closed = true
        window.removeOnFrameMetricsAvailableListener(listener)
        val latch = CountDownLatch(1)
        var line = ""
        handler.post {
            line = formatLine(workload, totalsNs.toList(), dropped)
            latch.countDown()
        }
        val finished = latch.await(DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        thread.quitSafely()
        return if (finished) line else ""
    }

    fun abandon(window: Window) {
        if (closed) return
        closed = true
        window.removeOnFrameMetricsAvailableListener(listener)
        thread.quitSafely()
    }

    private companion object {
        const val THREAD_NAME = "chart-frame-metrics"
        const val DRAIN_TIMEOUT_SECONDS = 2L
        const val NANOS_PER_MILLI = 1_000_000.0
        const val FRAME_BUDGET_MS = 16.7
        const val P95_FRACTION = 0.95
        const val P99_FRACTION = 0.99
        const val EVEN_SPLIT = 2.0

        fun formatLine(workload: String, totalsNs: List<Long>, dropped: Int): String {
            val sorted = totalsNs.map { it / NANOS_PER_MILLI }.sorted()
            val overBudget = sorted.count { it > FRAME_BUDGET_MS }
            return listOf(
                "chart-frame-metrics",
                "workload=$workload",
                "frames=${sorted.size}",
                "dropped=$dropped",
                "median_total_ms=${formatMillis(median(sorted))}",
                "p95_total_ms=${formatMillis(nearestRank(sorted, P95_FRACTION))}",
                "p99_total_ms=${formatMillis(nearestRank(sorted, P99_FRACTION))}",
                "max_total_ms=${formatMillis(sorted.lastOrNull())}",
                "over_16_7=$overBudget",
            ).joinToString(separator = " ")
        }

        fun median(sorted: List<Double>): Double? {
            if (sorted.isEmpty()) return null
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / EVEN_SPLIT
        }

        fun nearestRank(sorted: List<Double>, fraction: Double): Double? {
            if (sorted.isEmpty()) return null
            val rank = kotlin.math.ceil(fraction * sorted.size).toInt().coerceAtLeast(1)
            return sorted[rank - 1]
        }

        fun formatMillis(value: Double?): String = if (value == null) {
            "na"
        } else {
            String.format(Locale.US, "%.6f", value)
        }
    }
}
