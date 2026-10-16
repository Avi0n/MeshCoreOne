package one.meshcore.chartbench.macro

import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Release-equivalent frame timing for the tool charts.
 * The target package is one.meshcore.chartbench, minified and not debuggable.
 * FrameTimingMetric records CPU frame time, deadline overrun, and percentiles.
 * The host also records FrameMetrics total duration. The 16.7 ms bar is each iteration's median.
 * The test fails when that median exceeds the bar. Deadline overruns stay recorded.
 * Compilation is partial, not full speed.
 */
@RunWith(AndroidJUnit4::class)
class ToolChartMacrobenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun stressNoise() = benchmarkRule.measureChart(WORKLOAD_STRESS_NOISE, NOISE_TIMEOUT_MILLIS)

    @Test
    fun stressLineOfSight() = benchmarkRule.measureChart(WORKLOAD_STRESS_LOS, SHORT_TIMEOUT_MILLIS)

    @Test
    fun stressTrace() = benchmarkRule.measureChart(WORKLOAD_STRESS_TRACE, SHORT_TIMEOUT_MILLIS)

    @Test
    fun productNoise() = benchmarkRule.measureChart(WORKLOAD_PRODUCT_NOISE, NOISE_TIMEOUT_MILLIS)

    @Test
    fun productLineOfSight() = benchmarkRule.measureChart(WORKLOAD_PRODUCT_LOS, SHORT_TIMEOUT_MILLIS)

    @Test
    fun productHistory() = benchmarkRule.measureChart(WORKLOAD_PRODUCT_HISTORY, SHORT_TIMEOUT_MILLIS)

    @Test
    fun productSparkline() = benchmarkRule.measureChart(WORKLOAD_PRODUCT_SPARKLINE, SHORT_TIMEOUT_MILLIS)

    @Test
    fun controlText() = benchmarkRule.measureChart(WORKLOAD_CONTROL, SHORT_TIMEOUT_MILLIS)

    private companion object {
        const val WORKLOAD_STRESS_NOISE = "stress_noise"
        const val WORKLOAD_STRESS_LOS = "stress_los"
        const val WORKLOAD_STRESS_TRACE = "stress_trace"
        const val WORKLOAD_PRODUCT_NOISE = "product_noise"
        const val WORKLOAD_PRODUCT_LOS = "product_los"
        const val WORKLOAD_PRODUCT_HISTORY = "product_history"
        const val WORKLOAD_PRODUCT_SPARKLINE = "product_sparkline"
        const val WORKLOAD_CONTROL = "control"
        const val NOISE_TIMEOUT_MILLIS = 90_000L
        const val SHORT_TIMEOUT_MILLIS = 45_000L
    }
}
