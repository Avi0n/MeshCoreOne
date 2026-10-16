package one.meshcore.shared.tools.ui

import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.localizable_common_close

/** Compact analysis sheet peeks at a quarter of the host, matching iOS collapsed detent. */
internal const val LOS_COMPACT_SHEET_PEEK_FRACTION = 0.25f

internal const val LOS_MIN_ANALYSIS_WIDTH_DP = 320
internal const val LOS_PREFERRED_ANALYSIS_WIDTH_DP = 400
internal const val LOS_MIN_MAP_WIDTH_DP = 320
internal const val LOS_MIN_ANALYSIS_HEIGHT_DP = 420
internal const val LOS_MIN_MAP_HEIGHT_DP = 280
internal const val LOS_DIVIDER_WIDTH_DP = 1

/** Pair only when the measured workspace fits both surfaces and the Material divider. */
internal fun shouldShowLosSideBySide(widthDp: Float, heightDp: Float): Boolean =
    widthDp >= LOS_MIN_ANALYSIS_WIDTH_DP + LOS_MIN_MAP_WIDTH_DP + LOS_DIVIDER_WIDTH_DP &&
        heightDp >= maxOf(LOS_MIN_ANALYSIS_HEIGHT_DP, LOS_MIN_MAP_HEIGHT_DP)

internal fun losAnalysisPanelWidthDp(containerWidthDp: Float): Int =
    (containerWidthDp - LOS_MIN_MAP_WIDTH_DP - LOS_DIVIDER_WIDTH_DP).toInt()
        .coerceIn(LOS_MIN_ANALYSIS_WIDTH_DP, LOS_PREFERRED_ANALYSIS_WIDTH_DP)

/** iOS hides Analyze until both elevations exist, and replaces it with Retry on error. */
internal fun shouldShowLosAnalyzeButton(canAnalyze: Boolean, hasResult: Boolean, isError: Boolean): Boolean =
    !isError && (canAnalyze || hasResult)

/** iOS shows RF settings with Analyze before the first result; after a result they sit under the chart. */
internal fun shouldShowLosRfSettingsBeforeResult(canAnalyze: Boolean, hasResult: Boolean, isError: Boolean): Boolean =
    canAnalyze && !hasResult && !isError

/** Material Close dismisses relocate mode; iOS Cancel is the wrong TalkBack name. */
internal fun losRelocateDismissSpokenResource() = Res.string.localizable_common_close
