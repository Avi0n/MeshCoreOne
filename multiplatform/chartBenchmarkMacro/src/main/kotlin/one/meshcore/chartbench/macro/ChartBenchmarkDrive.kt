package one.meshcore.chartbench.macro

import android.content.ComponentName
import android.content.Intent
import androidx.benchmark.Outputs
import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue

/**
 * Starts the already-running chart host. The workload extra is repeated so a recreated
 * activity still opens the surface setup selected, instead of the stress-noise default.
 *
 * Compilation is partial. UseIfAvailable applies the packaged baseline profile when the APK contains one,
 * and stays defined when it does not. Warmup stays zero so the iterations are not an after-use profile.
 * [CompilationMode.Full] remains the controlled calibration mode.
 */
internal fun MacrobenchmarkRule.measureChart(workload: String, timeoutMillis: Long) {
    val lines = mutableListOf<String>()
    try {
        measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = SHIPPING_COMPILATION,
            startupMode = null,
            iterations = CHART_ITERATIONS,
            setupBlock = { prepareChart(workload) },
        ) {
            lines += driveChart(workload, timeoutMillis)
        }
        requireChartFrameAcceptance(workload, lines)
    } finally {
        // The runner relaunches IsolationActivity after the test if it is not resumed. Its finish() is a
        // no-op, and startActivitySync does not complete when the existing task is only brought forward.
        // Leave that activity resumed so the runner skips the launch.
        try {
            writeFrameMetrics(workload, lines)
        } finally {
            dismissChartHost()
        }
    }
}

private fun MacrobenchmarkScope.prepareChart(workload: String) {
    pressHome()
    killProcess()
    // The block overload requires a launcher activity. This host is exported and stays off the home screen.
    val launch = Intent(Intent.ACTION_MAIN).apply {
        component = ComponentName(TARGET_PACKAGE, ACTIVITY_CLASS)
        putExtra(EXTRA_WORKLOAD, workload)
    }
    startActivityAndWait(launch)
    val ready = device.wait(Until.hasObject(By.desc(MARK_READY)), READY_TIMEOUT_MILLIS)
    assertTrue("Chart host did not become ready for $workload", ready == true)
}

private fun MacrobenchmarkScope.driveChart(workload: String, timeoutMillis: Long): String {
    device.executeShellCommand(runCommand(workload))
    val finished = device.wait(Until.findObject(By.descContains(MARK_DONE)), timeoutMillis)
    check(finished != null) { "Chart workload $workload did not finish" }
    val description = finished.contentDescription
    check(description.contains(FRAME_METRICS_PREFIX)) {
        "Chart workload $workload finished without $FRAME_METRICS_PREFIX"
    }
    return description
}

private fun writeFrameMetrics(workload: String, lines: List<String>) {
    val body = buildString {
        appendLine("bar_applied=true")
        appendLine("median_budget_ms=$CHART_MEDIAN_BUDGET_MS")
        appendLine("recorded_iterations=${lines.size}")
        appendLine("required_iterations=$CHART_ITERATIONS")
        appendLine("compilation=Partial")
        appendLine("baselineProfileMode=UseIfAvailable")
        appendLine("warmupIterations=0")
        appendLine("acceptance=FrameMetrics.TOTAL_DURATION")
        appendLine("median=even_odd_average")
        appendLine("percentile=nearest_rank")
        lines.forEach { appendLine(it) }
    }
    Outputs.writeFile(frameMetricsFileName(workload)) { file ->
        file.writeText(body)
    }
}

private fun dismissChartHost() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val device = UiDevice.getInstance(instrumentation)
    device.executeShellCommand("am force-stop $TARGET_PACKAGE")
    // SINGLE_TOP matches the runner. A plain start creates a second instance and IsolationActivity throws.
    val intent = Intent(Intent.ACTION_MAIN).apply {
        component = ComponentName(MACRO_PACKAGE, ISOLATION_ACTIVITY)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
    instrumentation.targetContext.startActivity(intent)
    val shown = device.wait(Until.hasObject(By.pkg(MACRO_PACKAGE)), ISOLATION_TIMEOUT_MILLIS)
    check(shown == true) { "IsolationActivity did not resume before teardown" }
}

private fun frameMetricsFileName(workload: String): String = "frame-metrics-$workload.txt"

private fun runCommand(workload: String): String =
    "am start -n $TARGET_PACKAGE/$ACTIVITY_CLASS --es $EXTRA_WORKLOAD $workload --es $EXTRA_COMMAND $COMMAND_RUN"

private const val TARGET_PACKAGE = "one.meshcore.chartbench"
private const val MACRO_PACKAGE = "one.meshcore.chartbench.macro"
private const val ISOLATION_ACTIVITY = "androidx.benchmark.IsolationActivity"
private const val ACTIVITY_CLASS = "one.meshcore.chartbench.ChartBenchmarkActivity"
private const val EXTRA_WORKLOAD = "workload"
private const val EXTRA_COMMAND = "command"
private const val COMMAND_RUN = "run"
private const val MARK_READY = "chart-benchmark-ready"
private const val MARK_DONE = "chart-benchmark-done"
private const val READY_TIMEOUT_MILLIS = 15_000L
private const val ISOLATION_TIMEOUT_MILLIS = 10_000L
private const val FRAME_METRICS_PREFIX = "chart-frame-metrics"

/**
 * Fresh-install compilation. UseIfAvailable applies the packaged baseline profile when one is present.
 * Warmup stays zero so the iterations are not an after-use profile.
 */
private val SHIPPING_COMPILATION = CompilationMode.Partial(
    baselineProfileMode = BaselineProfileMode.UseIfAvailable,
    warmupIterations = 0,
)
