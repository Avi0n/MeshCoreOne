package one.meshcore.shared.remotenode.ui

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.FrameMetrics
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import one.meshcore.shared.chat.ui.AndroidTimestampFormatter
import one.meshcore.shared.chat.ui.LocalTimestampFormatter
import one.meshcore.shared.chat.ui.TimestampFormatter
import one.meshcore.shared.chat.ui.readTimestampSettings
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.remotenode.TelemetryChartPoint
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.android_timestamp_unavailable
import org.jetbrains.compose.resources.getString
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * Physical-device frame cost of the shipped telemetry chart.
 * The pass bar is one 60 Hz frame: median [FrameMetrics.TOTAL_DURATION] during live append
 * and during long-press scrub stays at or under 16.7 ms. Heap is recorded and is not a gate.
 */
class MetricChartBenchmarkInstrumentedTest {
    @Test
    fun chartUpdatesScrubResizeAndHideStayWithinOneFrame() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = readTimestampSettings(target)
        val formatter = AndroidTimestampFormatter(
            settings.locale,
            settings.is24Hour,
            settings.zone,
            runBlocking { getString(Res.string.android_timestamp_unavailable) },
        )
        val model = ChartBenchModel(chartPoints(INITIAL_POINTS, 0), formatter)
        val phase = AtomicInteger(PHASE_WARMUP)
        val samples = Collections.synchronizedList(mutableListOf<FrameSample>())
        val handlerThread = HandlerThread("chart-frame-metrics").apply { start() }
        val handler = Handler(handlerThread.looper)
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            val durations = LongArray(METRIC_COUNT) { index -> metrics.getMetric(index) }
            samples.add(FrameSample(phase.get(), durations))
        }
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario.onActivity { activity ->
            val view = ComposeView(activity).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                setContent { ChartBenchHost(model) }
            }
            activity.setContentView(view)
            activity.window.addOnFrameMetricsAvailableListener(listener, handler)
        }
        Thread.sleep(WARMUP_MILLIS)
        val heapBefore = usedJavaHeap()

        phase.set(PHASE_LIVE)
        repeat(LIVE_APPENDS) { index ->
            scenario.onActivity {
                model.points = model.points + chartPoint(INITIAL_POINTS + index)
            }
            Thread.sleep(LIVE_INTERVAL_MILLIS)
        }

        phase.set(PHASE_SCRUB)
        val bounds = plotBounds(scenario)
        repeat(SCRUB_REPEATS) {
            injectScrub(bounds)
            Thread.sleep(SETTLE_MILLIS)
        }

        phase.set(PHASE_RESIZE)
        repeat(RESIZE_TOGGLES) { index ->
            scenario.onActivity { model.widthFraction = if (index % 2 == 0) NARROW_WIDTH else FULL_WIDTH }
            Thread.sleep(SETTLE_MILLIS)
        }

        phase.set(PHASE_HIDE)
        scenario.onActivity { model.visible = false }
        Thread.sleep(SETTLE_MILLIS)
        scenario.onActivity { model.visible = true }
        Thread.sleep(SETTLE_MILLIS)
        val heapAfter = usedJavaHeap()

        scenario.onActivity { it.window.removeOnFrameMetricsAvailableListener(listener) }
        handlerThread.quitSafely()
        scenario.close()

        val report = buildReport(samples.toList(), heapBefore, heapAfter)
        writeReport(report)
        Log.i(TAG, report)
        assertPhaseCaptured(samples, PHASE_LIVE, "live append")
        assertPhaseCaptured(samples, PHASE_SCRUB, "scrub")
        assertPhaseCaptured(samples, PHASE_RESIZE, "resize")
        assertPhaseCaptured(samples, PHASE_HIDE, "hide/return")
        assertMedian(samples, PHASE_LIVE, "live append")
        assertMedian(samples, PHASE_SCRUB, "scrub")
    }

    private fun assertPhaseCaptured(samples: List<FrameSample>, phase: Int, label: String) {
        assertTrue("No frames captured during $label", samples.any { it.phase == phase })
    }

    private fun assertMedian(samples: List<FrameSample>, phase: Int, label: String) {
        val median = medianMillis(samples, phase)
        assertTrue(
            "$label median ${median}ms exceeds ${FRAME_BUDGET_MS}ms",
            median <= FRAME_BUDGET_MS,
        )
    }

    private fun plotBounds(scenario: ActivityScenario<ComponentActivity>): PlotBounds {
        val bounds = PlotBounds()
        scenario.onActivity { activity ->
            val content = activity.findViewById<View>(android.R.id.content)
            val origin = IntArray(2)
            content.getLocationOnScreen(origin)
            val density = content.resources.displayMetrics.density
            bounds.startX = origin[0] + density * PLOT_INSET_START_DP
            bounds.endX = origin[0] + content.width - density * PLOT_INSET_END_DP
            bounds.y = origin[1] + density * PLOT_Y_DP
        }
        return bounds
    }

    private fun injectScrub(bounds: PlotBounds) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val downTime = SystemClock.uptimeMillis()
        fun event(action: Int, x: Float, y: Float, time: Long) {
            val motion = MotionEvent.obtain(downTime, time, action, x, y, 0)
            instrumentation.sendPointerSync(motion)
            motion.recycle()
        }
        event(MotionEvent.ACTION_DOWN, bounds.startX, bounds.y, downTime)
        Thread.sleep(LONG_PRESS_MILLIS)
        for (step in 1..SCRUB_STEPS) {
            val fraction = step.toFloat() / SCRUB_STEPS
            val x = bounds.startX + (bounds.endX - bounds.startX) * fraction
            val time = downTime + LONG_PRESS_MILLIS + step * SCRUB_STEP_MILLIS
            event(MotionEvent.ACTION_MOVE, x, bounds.y, time)
            Thread.sleep(SCRUB_STEP_MILLIS)
        }
        val upTime = downTime + LONG_PRESS_MILLIS + SCRUB_STEPS * SCRUB_STEP_MILLIS
        event(MotionEvent.ACTION_UP, bounds.endX, bounds.y, upTime)
    }

    private fun usedJavaHeap(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    private fun writeReport(report: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.cacheDir, REPORT_NAME).writeText(report)
    }

    private class ChartBenchModel(initial: List<TelemetryChartPoint>, val formatter: TimestampFormatter) {
        val drawModel = ChartDrawModel()

        // Assigning publishes into the draw model. A snapshot read of this list in the plot
        // would restart composition on every append.
        var points: List<TelemetryChartPoint> = emptyList()
            set(value) {
                field = value
                drawModel.publish(value, emptyList(), BENCH_UNIT, null, formatter)
            }

        var widthFraction by mutableStateOf(FULL_WIDTH)
        var visible by mutableStateOf(true)

        init {
            points = initial
        }
    }

    private class PlotBounds {
        var startX: Float = 0f
        var endX: Float = 0f
        var y: Float = 0f
    }

    @Composable
    private fun ChartBenchHost(model: ChartBenchModel) {
        // The theme stays outside the sample read. Rebuilding it on every append was part of the
        // animation slice, and the shipping chart sits under the activity theme the same way.
        CompositionLocalProvider(LocalTimestampFormatter provides model.formatter) {
            MaterialTheme {
                ChartBenchPlot(model)
            }
        }
    }

    @Composable
    private fun ChartBenchPlot(model: ChartBenchModel) {
        val labels = model.drawModel.axisLabels
        if (model.visible && labels != null) {
            Box(Modifier.fillMaxWidth(model.widthFraction)) {
                ChartCanvas(
                    title = "History",
                    labels = labels,
                    model = model.drawModel,
                    lineColor = MaterialTheme.colorScheme.primary,
                    secondaryLineColor = MaterialTheme.colorScheme.tertiary,
                )
            }
        } else {
            Box(Modifier.fillMaxWidth().height(CHART_PLACEHOLDER_DP.dp))
        }
    }

    private class FrameSample(val phase: Int, val durationsNs: LongArray)

    private companion object {
        const val TAG = "ChartBench"
        const val REPORT_NAME = "chart-frame-metrics.txt"
        const val INITIAL_POINTS = 300
        const val LIVE_APPENDS = 100
        const val LIVE_INTERVAL_MILLIS = 100L
        const val WARMUP_MILLIS = 1_500L
        const val SETTLE_MILLIS = 400L
        const val LONG_PRESS_MILLIS = 700L
        const val SCRUB_REPEATS = 4
        const val SCRUB_STEPS = 20
        const val SCRUB_STEP_MILLIS = 16L
        const val RESIZE_TOGGLES = 4
        const val PLOT_INSET_START_DP = 72f
        const val PLOT_INSET_END_DP = 24f
        const val PLOT_Y_DP = 120f
        const val CHART_PLACEHOLDER_DP = 180
        const val NARROW_WIDTH = 0.55f
        const val FULL_WIDTH = 1f
        const val FRAME_BUDGET_MS = 16.7
        const val BENCH_UNIT = "dBm"
        const val NANOS_PER_MILLI = 1_000_000.0
        const val HOUR_SECONDS = 3_600.0
        const val BASE_TIMESTAMP = 1_700_000_000.0
        const val VALUE_BASE = 3.2
        const val VALUE_STEP = 0.05
        const val VALUE_CYCLE = 17
        const val METRIC_COUNT = FrameMetrics.TOTAL_DURATION + 1
        const val PHASE_WARMUP = 0
        const val PHASE_LIVE = 1
        const val PHASE_SCRUB = 2
        const val PHASE_RESIZE = 3
        const val PHASE_HIDE = 4
        const val HEAP_OBSERVATION_BYTES = 16L * 1024L * 1024L
        val POINT_ID: Uuid = Uuid.normalized("CCCCCCCC-CCCC-CCCC-CCCC-CCCCCCCCCCCC")

        fun chartPoints(count: Int, firstIndex: Int): List<TelemetryChartPoint> =
            List(count) { offset -> chartPoint(firstIndex + offset) }

        fun chartPoint(index: Int): TelemetryChartPoint = TelemetryChartPoint(
            id = POINT_ID,
            timestampSeconds = BASE_TIMESTAMP + index * HOUR_SECONDS,
            value = VALUE_BASE + (index % VALUE_CYCLE) * VALUE_STEP,
        )

        fun medianMillis(samples: List<FrameSample>, phase: Int): Double =
            medianMetric(samples, phase, FrameMetrics.TOTAL_DURATION)

        fun medianMetric(samples: List<FrameSample>, phase: Int, metric: Int): Double {
            val sorted = samples.filter { it.phase == phase }.map { it.durationsNs[metric] / NANOS_PER_MILLI }.sorted()
            if (sorted.isEmpty()) return Double.POSITIVE_INFINITY
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
        }

        private val METRIC_NAMES = listOf(
            "unknown",
            "input",
            "animation",
            "layout",
            "draw",
            "sync",
            "command",
            "swap",
            "total",
        )

        fun buildReport(samples: List<FrameSample>, heapBefore: Long, heapAfter: Long): String {
            val growth = heapAfter - heapBefore
            val header = buildString {
                appendLine("bar_ms=$FRAME_BUDGET_MS")
                appendLine("heap_before=$heapBefore")
                appendLine("heap_after=$heapAfter")
                appendLine("heap_growth=$growth")
                appendLine("heap_observation_bytes=$HEAP_OBSERVATION_BYTES")
                appendLine("median_live_ms=${medianMillis(samples, PHASE_LIVE)}")
                appendLine("median_scrub_ms=${medianMillis(samples, PHASE_SCRUB)}")
                appendLine("median_resize_ms=${medianMillis(samples, PHASE_RESIZE)}")
                appendLine("median_hide_ms=${medianMillis(samples, PHASE_HIDE)}")
                listOf(PHASE_LIVE, PHASE_SCRUB, PHASE_RESIZE, PHASE_HIDE).forEach { phase ->
                    val parts = METRIC_NAMES.mapIndexed { index, name ->
                        "$name=${medianMetric(samples, phase, index)}"
                    }
                    appendLine("metrics_phase_$phase ${parts.joinToString(separator = " ")}")
                }
                appendLine("phase,duration_ns")
            }
            val rows = samples.joinToString(separator = "\n") { sample ->
                "${sample.phase},${sample.durationsNs[FrameMetrics.TOTAL_DURATION]}"
            }
            return header + rows + "\n"
        }
    }
}
