package one.meshcore.chartbench

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

/**
 * Hosts one chart surface and posts samples after a shell start with [COMMAND_RUN].
 * The next sample is posted so the main thread can draw during the gap.
 */
class ChartBenchmarkActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var model: ChartBenchmarkModel? = null
    private var frames: ChartFrameMetrics? = null
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val chartModel = ChartBenchmarkModel(workload = workloadOf(intent))
        model = chartModel
        setContent { ChartBenchmarkHost(chartModel) }
        if (intent.getStringExtra(EXTRA_COMMAND) == COMMAND_RUN) {
            handler.post { startLoop() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getStringExtra(EXTRA_COMMAND) == COMMAND_RUN) {
            startLoop()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        frames?.abandon(window)
        frames = null
        super.onDestroy()
    }

    private fun startLoop() {
        if (running) return
        val chartModel = model ?: return
        running = true
        val recorder = ChartFrameMetrics(chartModel.workload)
        frames = recorder
        window.addOnFrameMetricsAvailableListener(recorder.listener, recorder.handler)
        chartModel.mark = MARK_RUNNING
        postStep(chartModel, step = 0)
    }

    private fun postStep(chartModel: ChartBenchmarkModel, step: Int) {
        if (step >= chartModel.updateCount) {
            finishLoop(chartModel)
            return
        }
        chartModel.advance(step)
        handler.postDelayed({ postStep(chartModel, step + 1) }, LIVE_INTERVAL_MILLIS)
    }

    private fun finishLoop(chartModel: ChartBenchmarkModel) {
        val summary = frames?.drain(window).orEmpty()
        frames = null
        // The done description carries the totals so the driver can read them from the node it already waits for.
        chartModel.mark = if (summary.isEmpty()) MARK_DONE else "$MARK_DONE $summary"
    }

    private fun workloadOf(intent: Intent): String = intent.getStringExtra(EXTRA_WORKLOAD) ?: WORKLOAD_STRESS_NOISE
}
