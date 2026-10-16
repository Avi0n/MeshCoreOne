package one.meshcore.chartbench

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import one.meshcore.shared.chat.ui.TimestampFormatting
import one.meshcore.shared.rf.ProfileSample
import one.meshcore.shared.tools.ui.FRESNEL_OBSTRUCTED_ALPHA
import one.meshcore.shared.tools.ui.FresnelTerrainChart
import one.meshcore.shared.tools.ui.LOS_FRESNEL_CLEAR_ROLE
import one.meshcore.shared.tools.ui.LOS_FRESNEL_OBSTRUCTED_ROLE
import one.meshcore.shared.tools.ui.MiniSparkline
import one.meshcore.shared.tools.ui.NoiseFloorChartCanvas
import one.meshcore.shared.tools.ui.SAVED_PATH_ROW_SPARKLINE_HEIGHT
import one.meshcore.shared.tools.ui.SAVED_PATH_ROW_SPARKLINE_WIDTH
import one.meshcore.shared.tools.ui.SavedPathRunChart
import one.meshcore.shared.tools.ui.chartAccessibilityLabel
import one.meshcore.shared.ui.tint

/** Product chart composables. The status description is a sibling so it is not merged into the plot. */
@Composable
internal fun ChartBenchmarkHost(model: ChartBenchmarkModel) {
    TimestampFormatting {
        MaterialTheme {
            Column(Modifier.fillMaxWidth()) {
                Box(Modifier.size(MARK_SIZE_DP.dp).semantics { contentDescription = model.mark })
                ChartBenchmarkPlot(model)
            }
        }
    }
}

@Composable
private fun ChartBenchmarkPlot(model: ChartBenchmarkModel) {
    when (model.workload) {
        WORKLOAD_STRESS_LOS -> LineOfSightPlot(model.terrain, productColors = false)
        WORKLOAD_PRODUCT_LOS -> LineOfSightPlot(model.terrain, productColors = true)
        WORKLOAD_STRESS_TRACE -> StressTracePlot(model)
        WORKLOAD_PRODUCT_HISTORY -> SavedPathRunChart(runs = model.runs, modifier = Modifier.fillMaxWidth())
        WORKLOAD_PRODUCT_SPARKLINE -> ProductSparklinePlot(model)
        WORKLOAD_CONTROL -> ControlPlot(model)
        WORKLOAD_PRODUCT_NOISE -> NoisePlot(model, productLabel = true)
        else -> NoisePlot(model, productLabel = false)
    }
}

@Composable
private fun LineOfSightPlot(samples: List<ProfileSample>, productColors: Boolean) {
    if (productColors) {
        FresnelTerrainChart(
            samples = samples,
            terrainColor = MaterialTheme.colorScheme.outline,
            losColor = MaterialTheme.colorScheme.onSurface,
            fresnelColor = LOS_FRESNEL_CLEAR_ROLE.tint(),
            obstructedColor = LOS_FRESNEL_OBSTRUCTED_ROLE.tint().copy(alpha = FRESNEL_OBSTRUCTED_ALPHA),
        )
    } else {
        FresnelTerrainChart(
            samples = samples,
            terrainColor = MaterialTheme.colorScheme.outline,
            losColor = MaterialTheme.colorScheme.onSurface,
            fresnelColor = MaterialTheme.colorScheme.primary.copy(alpha = STRESS_FRESNEL_ALPHA),
            obstructedColor = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun StressTracePlot(model: ChartBenchmarkModel) {
    SavedPathRunChart(runs = model.runs)
    MiniSparkline(
        values = model.runs.map { it.roundTripMs },
        modifier = Modifier.fillMaxWidth().height(STRESS_SPARKLINE_DP.dp),
    )
}

@Composable
private fun ProductSparklinePlot(model: ChartBenchmarkModel) {
    MiniSparkline(
        values = model.sparkline,
        modifier = Modifier.width(SAVED_PATH_ROW_SPARKLINE_WIDTH.dp).height(SAVED_PATH_ROW_SPARKLINE_HEIGHT.dp),
    )
}

@Composable
private fun NoisePlot(model: ChartBenchmarkModel, productLabel: Boolean) {
    val label = if (productLabel) {
        chartAccessibilityLabel(model.noise)
    } else {
        stressNoiseLabel(model.noise.size)
    }
    NoiseFloorChartCanvas(readings = model.noise, startEpochMs = 0L, label = label)
}

@Composable
private fun ControlPlot(model: ChartBenchmarkModel) {
    Text(text = model.tick.toString(), modifier = Modifier.fillMaxWidth().height(CONTROL_HEIGHT_DP.dp))
}

/** Spoken label the historical stress test passes in. Not the product accessibility sentence. */
private fun stressNoiseLabel(count: Int): String = "Noise floor $count"
