package one.meshcore.shared.tools.ui

import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.FrameMetrics
import android.view.ViewGroup
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import one.meshcore.shared.backup.TracePathRunDto
import one.meshcore.shared.chat.ui.TimestampFormatting
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.rf.ProfileSample
import one.meshcore.shared.tools.NoiseFloorReading
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * Frame cost of the shipped Noise Floor, Line of Sight, and Trace Path charts on a device.
 * Records resize, prolonged live updates, and hide/return. It does not treat 16.7 ms as a pass.
 * Noise floor goes through [NoiseFloorChartCanvas]. The spoken label includes the sample count,
 * as the product accessibility string does. Hide/return is slower because the chart is created
 * again. Line of Sight and the saved-path chart rebuild axis text on each update, and the product
 * does not stream them at 100 ms. Noise-floor samples use the 1500 ms product poll spacing and
 * stop at the 200-reading cap. Appends here are every 100 ms.
 */
class ToolChartBenchmarkInstrumentedTest {
    @Test
    fun toolChartsLogProlongedUpdatesResizeAndHide() {
        val model = ToolChartModel()
        val phase = AtomicInteger(PHASE_WARMUP)
        val samples = Collections.synchronizedList(mutableListOf<FrameSample>())
        val handlerThread = HandlerThread("tool-chart-frames").apply { start() }
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
                setContent { ToolChartHost(model) }
            }
            activity.setContentView(view)
            activity.window.addOnFrameMetricsAvailableListener(listener, handler)
        }
        Thread.sleep(WARMUP_MILLIS)
        val heapBefore = usedJavaHeap()

        measureSurface(scenario, model, phase, SURFACE_NOISE) { index ->
            model.noiseSeries = noiseReadings(NOISE_START + index + 1)
        }
        measureSurface(scenario, model, phase, SURFACE_LOS) { index ->
            model.terrain = terrainSamples(index + 1)
        }
        measureSurface(scenario, model, phase, SURFACE_TRACE) { index ->
            model.runs = traceRuns(TRACE_START + index + 1)
        }
        val heapAfter = usedJavaHeap()

        scenario.onActivity { it.window.removeOnFrameMetricsAvailableListener(listener) }
        handlerThread.quitSafely()
        scenario.close()

        val report = buildReport(samples.toList(), heapBefore, heapAfter)
        writeReport(report)
        Log.i(TAG, report)
        listOf(SURFACE_NOISE, SURFACE_LOS, SURFACE_TRACE).forEach { surface ->
            assertCaptured(samples, phaseId(surface, KIND_LIVE), surfaceName(surface))
            assertCaptured(samples, phaseId(surface, KIND_RESIZE), surfaceName(surface))
            assertCaptured(samples, phaseId(surface, KIND_HIDE), surfaceName(surface))
        }
    }

    private fun measureSurface(
        scenario: ActivityScenario<ComponentActivity>,
        model: ToolChartModel,
        phase: AtomicInteger,
        surface: Int,
        advance: (Int) -> Unit,
    ) {
        scenario.onActivity {
            model.surface = surface
            model.visible = true
            model.widthFraction = FULL_WIDTH
        }
        Thread.sleep(SETTLE_MILLIS)
        phase.set(phaseId(surface, KIND_LIVE))
        val updates = if (surface == SURFACE_NOISE) NOISE_CAP - NOISE_START else LIVE_UPDATES
        repeat(updates) { index ->
            scenario.onActivity { advance(index) }
            Thread.sleep(LIVE_INTERVAL_MILLIS)
        }
        phase.set(phaseId(surface, KIND_RESIZE))
        repeat(RESIZE_TOGGLES) { index ->
            scenario.onActivity { model.widthFraction = if (index % 2 == 0) NARROW_WIDTH else FULL_WIDTH }
            Thread.sleep(SETTLE_MILLIS)
        }
        phase.set(phaseId(surface, KIND_HIDE))
        scenario.onActivity { model.visible = false }
        Thread.sleep(SETTLE_MILLIS)
        scenario.onActivity { model.visible = true }
        Thread.sleep(SETTLE_MILLIS)
    }

    private fun assertCaptured(samples: List<FrameSample>, phase: Int, label: String) {
        assertTrue("No frames captured during $label phase $phase", samples.any { it.phase == phase })
    }

    private class ToolChartModel {
        var noiseSeries by mutableStateOf(noiseReadings(NOISE_START))
        var terrain by mutableStateOf(terrainSamples(0))
        var runs by mutableStateOf(traceRuns(TRACE_START))
        var surface by mutableStateOf(SURFACE_NOISE)
        var widthFraction by mutableStateOf(FULL_WIDTH)
        var visible by mutableStateOf(true)
    }

    @Composable
    private fun ToolChartHost(model: ToolChartModel) {
        TimestampFormatting {
            MaterialTheme {
                if (!model.visible) {
                    Box(Modifier.fillMaxWidth().height(PLACEHOLDER_DP.dp))
                } else {
                    ToolChartPlot(model)
                }
            }
        }
    }

    @Composable
    private fun ToolChartPlot(model: ToolChartModel) {
        Column(Modifier.fillMaxWidth(model.widthFraction)) {
            when (model.surface) {
                SURFACE_LOS -> FresnelTerrainChart(
                    samples = model.terrain,
                    terrainColor = MaterialTheme.colorScheme.outline,
                    losColor = MaterialTheme.colorScheme.onSurface,
                    fresnelColor = MaterialTheme.colorScheme.primary.copy(alpha = FRESNEL_ALPHA),
                    obstructedColor = MaterialTheme.colorScheme.error,
                )
                SURFACE_TRACE -> {
                    SavedPathRunChart(runs = model.runs)
                    MiniSparkline(
                        values = model.runs.map { it.roundTripMs },
                        modifier = Modifier.fillMaxWidth().height(SPARKLINE_DP.dp),
                    )
                }
                else -> NoiseFloorChartCanvas(
                    readings = model.noiseSeries,
                    startEpochMs = 0L,
                    label = noiseSpokenLabel(model.noiseSeries.size),
                )
            }
        }
    }

    private class FrameSample(val phase: Int, val durationsNs: LongArray)

    private companion object {
        const val TAG = "ToolChartBench"
        const val REPORT_NAME = "tool-chart-frame-metrics.txt"
        const val NOISE_START = 2
        const val NOISE_CAP = 200
        const val TRACE_START = 2
        const val LIVE_UPDATES = 40
        const val LIVE_INTERVAL_MILLIS = 100L
        const val PRODUCT_POLL_MILLIS = 1_500L
        const val WARMUP_MILLIS = 1_500L
        const val SETTLE_MILLIS = 400L
        const val RESIZE_TOGGLES = 4
        const val PLACEHOLDER_DP = 180
        const val SPARKLINE_DP = 20
        const val NARROW_WIDTH = 0.55f
        const val FULL_WIDTH = 1f
        const val FRAME_BUDGET_MS = 16.7
        const val FRESNEL_ALPHA = 0.35f
        const val NANOS_PER_MILLI = 1_000_000.0
        const val DAY_SECONDS = 86_400.0
        const val METRIC_COUNT = FrameMetrics.TOTAL_DURATION + 1
        const val PHASE_WARMUP = 0
        const val SURFACE_NOISE = 1
        const val SURFACE_LOS = 2
        const val SURFACE_TRACE = 3
        const val KIND_LIVE = 1
        const val KIND_RESIZE = 2
        const val KIND_HIDE = 3
        val READING_ID: Uuid = Uuid.normalized("AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA")
        val RUN_ID: Uuid = Uuid.normalized("BBBBBBBB-BBBB-BBBB-BBBB-BBBBBBBBBBBB")

        fun noiseSpokenLabel(count: Int): String = "Noise floor $count"

        fun phaseId(surface: Int, kind: Int): Int = surface * 10 + kind

        fun surfaceName(surface: Int): String = when (surface) {
            SURFACE_LOS -> "line_of_sight"
            SURFACE_TRACE -> "trace_path"
            else -> "noise_floor"
        }

        fun noiseReadings(count: Int): List<NoiseFloorReading> = List(count) { index ->
            NoiseFloorReading(
                id = READING_ID,
                timestampEpochMs = index * PRODUCT_POLL_MILLIS,
                noiseFloor = -120 + (index % 40),
                lastRssi = -70,
                lastSnr = 5.0,
            )
        }

        fun terrainSamples(step: Int): List<ProfileSample> = listOf(
            ProfileSample(x = 0.0, yTerrain = 100.0, yLos = 180.0, fresnelRadius = 40.0),
            ProfileSample(x = 400.0, yTerrain = 140.0 + (step % 20), yLos = 190.0, fresnelRadius = 55.0),
            ProfileSample(x = 800.0, yTerrain = 120.0, yLos = 200.0, fresnelRadius = 30.0),
        )

        fun traceRuns(count: Int): List<TracePathRunDto> = List(count) { index ->
            TracePathRunDto(
                id = RUN_ID,
                date = index * DAY_SECONDS,
                success = true,
                roundTripMs = 20 + (index % 15) * 10,
            )
        }

        fun usedJavaHeap(): Long {
            val runtime = Runtime.getRuntime()
            return runtime.totalMemory() - runtime.freeMemory()
        }

        fun writeReport(report: String) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            File(context.cacheDir, REPORT_NAME).writeText(report)
        }

        fun medianMillis(samples: List<FrameSample>, phase: Int): Double =
            medianMetric(samples, phase, FrameMetrics.TOTAL_DURATION)

        fun partsLine(samples: List<FrameSample>, name: String, kind: Int, phase: Int): String {
            val kindName = when (kind) {
                KIND_RESIZE -> "resize"
                KIND_HIDE -> "hide"
                else -> "live"
            }
            val parts = METRIC_NAMES.mapIndexed { index, metric ->
                "$metric=${medianMetric(samples, phase, index)}"
            }
            return "parts_${name}_$kindName ${parts.joinToString(separator = " ")}"
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

        fun medianMetric(samples: List<FrameSample>, phase: Int, metric: Int): Double {
            val sorted = samples.filter { it.phase == phase }.map { it.durationsNs[metric] / NANOS_PER_MILLI }.sorted()
            if (sorted.isEmpty()) return Double.POSITIVE_INFINITY
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
        }

        fun buildReport(samples: List<FrameSample>, heapBefore: Long, heapAfter: Long): String = buildString {
            appendLine("telemetry_bar_ms=$FRAME_BUDGET_MS")
            appendLine("bar_applied=false")
            appendLine("noise_workload=append_${NOISE_START}_to_${NOISE_CAP}_every_${LIVE_INTERVAL_MILLIS}ms")
            appendLine("noise_timestamp_spacing_ms=$PRODUCT_POLL_MILLIS")
            appendLine("los_workload=${LIVE_UPDATES}_terrain_updates_every_${LIVE_INTERVAL_MILLIS}ms")
            val traceEnd = TRACE_START + LIVE_UPDATES
            appendLine("trace_workload=append_${TRACE_START}_to_${traceEnd}_every_${LIVE_INTERVAL_MILLIS}ms")
            appendLine("heap_before=$heapBefore")
            appendLine("heap_after=$heapAfter")
            appendLine("heap_growth=${heapAfter - heapBefore}")
            listOf(SURFACE_NOISE, SURFACE_LOS, SURFACE_TRACE).forEach { surface ->
                val name = surfaceName(surface)
                appendLine("median_${name}_live_ms=${medianMillis(samples, phaseId(surface, KIND_LIVE))}")
                appendLine("median_${name}_resize_ms=${medianMillis(samples, phaseId(surface, KIND_RESIZE))}")
                appendLine("median_${name}_hide_ms=${medianMillis(samples, phaseId(surface, KIND_HIDE))}")
                listOf(KIND_LIVE, KIND_RESIZE, KIND_HIDE).forEach { kind ->
                    appendLine(partsLine(samples, name, kind, phaseId(surface, kind)))
                }
            }
            samples.forEach { sample ->
                appendLine("${sample.phase},${sample.durationsNs[FrameMetrics.TOTAL_DURATION]}")
            }
        }
    }
}
