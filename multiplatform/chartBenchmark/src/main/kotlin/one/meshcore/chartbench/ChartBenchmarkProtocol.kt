package one.meshcore.chartbench

/** Intent extra selecting which chart surface the activity keeps on screen. */
internal const val EXTRA_WORKLOAD = "workload"

/** Intent extra that starts the measured update loop. Absent during setup. */
internal const val EXTRA_COMMAND = "command"

/** [EXTRA_COMMAND] value that posts the 100 ms updates. */
internal const val COMMAND_RUN = "run"

/** Accessibility description while the initial frame is up and the loop has not started. */
internal const val MARK_READY = "chart-benchmark-ready"

/** Accessibility description while samples are being posted. */
internal const val MARK_RUNNING = "chart-benchmark-running"

/** Accessibility description after the last posted sample. */
internal const val MARK_DONE = "chart-benchmark-done"

/** Small node so the status description has bounds an accessibility client can find. */
internal const val MARK_SIZE_DP = 1

internal const val WORKLOAD_STRESS_NOISE = "stress_noise"
internal const val WORKLOAD_STRESS_LOS = "stress_los"
internal const val WORKLOAD_STRESS_TRACE = "stress_trace"
internal const val WORKLOAD_PRODUCT_NOISE = "product_noise"
internal const val WORKLOAD_PRODUCT_LOS = "product_los"
internal const val WORKLOAD_PRODUCT_HISTORY = "product_history"
internal const val WORKLOAD_PRODUCT_SPARKLINE = "product_sparkline"
internal const val WORKLOAD_CONTROL = "control"

/** Same gap the device stress test sleeps between main-thread writes. */
internal const val LIVE_INTERVAL_MILLIS = 100L

/** Samples on screen before the first measured noise append. */
internal const val NOISE_START = 2

/** Noise series cap, matching the product history limit used by the stress test. */
internal const val NOISE_CAP = 200

/** Trace runs on screen before the first measured append. */
internal const val TRACE_START = 2

/** Appends for every workload except the 2-to-200 noise series. */
internal const val LIVE_UPDATES = 40

/** Timestamp spacing of noise samples. Appends still happen every [LIVE_INTERVAL_MILLIS]. */
internal const val PRODUCT_POLL_MILLIS = 1_500L

internal const val DAY_SECONDS = 86_400.0

/** Stress-test fresnel tint. The product chart uses the role tint instead. */
internal const val STRESS_FRESNEL_ALPHA = 0.35f

/** Height of the changing-text control, close to the terrain plot. */
internal const val CONTROL_HEIGHT_DP = 200

/** Combined stress sparkline height. The product row uses the saved-path constants. */
internal const val STRESS_SPARKLINE_DP = 20

internal const val NOISE_FLOOR_BASE_DBM = -120
internal const val NOISE_FLOOR_VARIATION = 40
internal const val NOISE_RSSI_DBM = -70
internal const val NOISE_SNR_DB = 5.0

internal const val TERRAIN_START_X_M = 0.0
internal const val TERRAIN_MID_X_M = 400.0
internal const val TERRAIN_END_X_M = 800.0
internal const val TERRAIN_START_Y_M = 100.0
internal const val TERRAIN_MID_BASE_Y_M = 140.0
internal const val TERRAIN_END_Y_M = 120.0
internal const val TERRAIN_START_LOS_M = 180.0
internal const val TERRAIN_MID_LOS_M = 190.0
internal const val TERRAIN_END_LOS_M = 200.0
internal const val TERRAIN_START_FRESNEL_M = 40.0
internal const val TERRAIN_MID_FRESNEL_M = 55.0
internal const val TERRAIN_END_FRESNEL_M = 30.0
internal const val TERRAIN_STEP_MODULUS = 20

internal const val TRACE_RTT_BASE_MS = 20
internal const val TRACE_RTT_STEP_MS = 10
internal const val TRACE_RTT_CYCLE = 15
