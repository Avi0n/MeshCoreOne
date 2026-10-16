package one.meshcore.chartbench.macro

import org.junit.Assert.assertThrows
import org.junit.Test

class ChartFrameAcceptanceTest {
    @Test
    fun acceptsAllIterationsAtOrBelowOriginalBudget() {
        requireChartFrameAcceptance(WORKLOAD, listOf(report("0"), report("13.010959"), report("16.7")))
    }

    @Test
    fun rejectsAnySlowIteration() {
        rejects(listOf(report(), report("16.700001"), report()))
    }

    @Test
    fun rejectsMissingOrExtraIterations() {
        listOf(0, 1, 2, 4).forEach { count -> rejects(List(count) { report() }) }
    }

    @Test
    fun rejectsInvalidMedian() {
        listOf("-1", "-Infinity", "Infinity", "NaN", "na", "", "bad").forEach { value ->
            rejects(List(CHART_ITERATIONS) { report(value) })
        }
        rejects(List(CHART_ITERATIONS) { report().replace("median_total_ms=10.0", "") })
    }

    @Test
    fun rejectsEmptyOrInvalidFrameCounts() {
        listOf("0", "-1", "na", "", "1.5", "2147483648").forEach { count ->
            rejects(List(CHART_ITERATIONS) { report().replace("frames=40", "frames=$count") })
        }
        rejects(List(CHART_ITERATIONS) { report().replace("frames=40", "") })
    }

    @Test
    fun rejectsInvalidDropCountsButReportsRealDrops() {
        listOf("-1", "na", "", "1.5").forEach { count ->
            rejects(List(CHART_ITERATIONS) { report().replace("dropped=0", "dropped=$count") })
        }
        rejects(List(CHART_ITERATIONS) { report().replace("dropped=0", "") })
        requireChartFrameAcceptance(WORKLOAD, List(CHART_ITERATIONS) { report().replace("dropped=0", "dropped=2") })
    }

    @Test
    fun rejectsMismatchedMissingOrUnsupportedWorkload() {
        rejects(List(CHART_ITERATIONS) { report().replace(WORKLOAD, "control") })
        rejects(List(CHART_ITERATIONS) { report().replace("workload=$WORKLOAD", "") })
        assertThrows(IllegalArgumentException::class.java) {
            requireChartFrameAcceptance("unknown", List(CHART_ITERATIONS) { report().replace(WORKLOAD, "unknown") })
        }
    }

    @Test
    fun rejectsDuplicateAcceptanceFieldsAndMissingMarker() {
        listOf("frames=40", "dropped=0", "median_total_ms=10.0", "workload=$WORKLOAD").forEach { token ->
            rejects(List(CHART_ITERATIONS) { "${report()} $token" })
        }
        rejects(List(CHART_ITERATIONS) { report().replace("chart-frame-metrics", "") })
    }

    private fun rejects(lines: List<String>) {
        assertThrows(IllegalArgumentException::class.java) { requireChartFrameAcceptance(WORKLOAD, lines) }
    }

    private fun report(median: String = "10.0"): String =
        "chart-benchmark-done chart-frame-metrics workload=$WORKLOAD frames=40 dropped=0 " +
            "median_total_ms=$median p95_total_ms=18.0 p99_total_ms=22.0 max_total_ms=23.0 over_16_7=2"

    private companion object {
        const val WORKLOAD = "stress_noise"
    }
}
