package one.meshcore.chartbench

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import one.meshcore.shared.backup.TracePathRunDto
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.rf.ProfileSample
import one.meshcore.shared.tools.NoiseFloorReading
import one.meshcore.shared.trace.RECENT_RTT_LIMIT

/**
 * State the measured composables read. Writes happen on the main thread, one posted sample at a time.
 */
internal class ChartBenchmarkModel(val workload: String) {
    var mark by mutableStateOf(MARK_READY)
    var noise by mutableStateOf(noiseReadings(NOISE_START))
    var terrain by mutableStateOf(terrainSamples(0))
    var runs by mutableStateOf(traceRuns(TRACE_START))
    var sparkline by mutableStateOf(sparklineWindow(RECENT_RTT_LIMIT))
    var tick by mutableStateOf(0)

    val updateCount: Int =
        if (workload == WORKLOAD_STRESS_NOISE || workload == WORKLOAD_PRODUCT_NOISE) {
            NOISE_CAP - NOISE_START
        } else {
            LIVE_UPDATES
        }

    fun advance(step: Int) {
        when (workload) {
            WORKLOAD_STRESS_NOISE, WORKLOAD_PRODUCT_NOISE -> noise = noiseReadings(NOISE_START + step + 1)
            WORKLOAD_STRESS_LOS, WORKLOAD_PRODUCT_LOS -> terrain = terrainSamples(step + 1)
            WORKLOAD_STRESS_TRACE, WORKLOAD_PRODUCT_HISTORY -> runs = traceRuns(TRACE_START + step + 1)
            WORKLOAD_PRODUCT_SPARKLINE -> sparkline = sparklineWindow(RECENT_RTT_LIMIT + step + 1)
            WORKLOAD_CONTROL -> tick = step + 1
        }
    }
}

private const val READING_ID_TEXT = "AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA"
private const val RUN_ID_TEXT = "BBBBBBBB-BBBB-BBBB-BBBB-BBBBBBBBBBBB"

private fun noiseReadings(count: Int): List<NoiseFloorReading> {
    val id = Uuid.normalized(READING_ID_TEXT)
    return List(count) { index ->
        NoiseFloorReading(
            id = id,
            timestampEpochMs = index * PRODUCT_POLL_MILLIS,
            noiseFloor = NOISE_FLOOR_BASE_DBM + (index % NOISE_FLOOR_VARIATION),
            lastRssi = NOISE_RSSI_DBM,
            lastSnr = NOISE_SNR_DB,
        )
    }
}

private fun terrainSamples(step: Int): List<ProfileSample> = listOf(
    ProfileSample(
        x = TERRAIN_START_X_M,
        yTerrain = TERRAIN_START_Y_M,
        yLos = TERRAIN_START_LOS_M,
        fresnelRadius = TERRAIN_START_FRESNEL_M,
    ),
    ProfileSample(
        x = TERRAIN_MID_X_M,
        yTerrain = TERRAIN_MID_BASE_Y_M + (step % TERRAIN_STEP_MODULUS),
        yLos = TERRAIN_MID_LOS_M,
        fresnelRadius = TERRAIN_MID_FRESNEL_M,
    ),
    ProfileSample(
        x = TERRAIN_END_X_M,
        yTerrain = TERRAIN_END_Y_M,
        yLos = TERRAIN_END_LOS_M,
        fresnelRadius = TERRAIN_END_FRESNEL_M,
    ),
)

private fun traceRuns(count: Int): List<TracePathRunDto> {
    val id = Uuid.normalized(RUN_ID_TEXT)
    return List(count) { index ->
        TracePathRunDto(
            id = id,
            date = index * DAY_SECONDS,
            success = true,
            roundTripMs = TRACE_RTT_BASE_MS + (index % TRACE_RTT_CYCLE) * TRACE_RTT_STEP_MS,
        )
    }
}

/** Last [RECENT_RTT_LIMIT] successful RTTs ending at [endExclusive], oldest first. */
private fun sparklineWindow(endExclusive: Int): List<Int> {
    val start = (endExclusive - RECENT_RTT_LIMIT).coerceAtLeast(0)
    return (start until endExclusive).map { index ->
        TRACE_RTT_BASE_MS + (index % TRACE_RTT_CYCLE) * TRACE_RTT_STEP_MS
    }
}
