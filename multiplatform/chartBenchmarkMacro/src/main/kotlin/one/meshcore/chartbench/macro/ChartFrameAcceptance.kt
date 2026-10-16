package one.meshcore.chartbench.macro

internal const val CHART_MEDIAN_BUDGET_MS = 16.7
internal const val CHART_ITERATIONS = 3

internal fun requireChartFrameAcceptance(workload: String, lines: List<String>) {
    require(workload in CHART_WORKLOADS) { "Unknown chart workload $workload" }
    require(lines.size == CHART_ITERATIONS) { "$workload recorded ${lines.size} iterations" }
    lines.forEachIndexed { index, line ->
        val tokens = line.split(' ')
        require("chart-frame-metrics" in tokens) { "$workload iteration $index lacks frame metrics" }
        val pairs = tokens.filter { '=' in it }.map { it.substringBefore('=') to it.substringAfter('=') }
        require(
            pairs.map {
                it.first
            }.distinct().size == pairs.size,
        ) { "$workload iteration $index has duplicate fields" }
        val fields = pairs.toMap()
        require(fields["workload"] == workload) { "$workload iteration $index reports ${fields["workload"]}" }
        val frames = fields["frames"]?.toIntOrNull()
        require(frames != null && frames > 0) { "$workload iteration $index has invalid frame count" }
        val dropped = fields["dropped"]?.toIntOrNull()
        require(dropped != null && dropped >= 0) { "$workload iteration $index has invalid callback drop count" }
        val median = fields["median_total_ms"]?.toDoubleOrNull()
        require(median != null && median.isFinite() && median in 0.0..CHART_MEDIAN_BUDGET_MS) {
            "$workload iteration $index median ${median ?: "missing"}ms exceeds ${CHART_MEDIAN_BUDGET_MS}ms"
        }
    }
}

private val CHART_WORKLOADS = setOf(
    "stress_noise",
    "stress_los",
    "stress_trace",
    "product_noise",
    "product_los",
    "product_history",
    "product_sparkline",
    "control",
)
