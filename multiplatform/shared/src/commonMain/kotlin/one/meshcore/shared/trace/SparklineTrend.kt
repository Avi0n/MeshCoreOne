package one.meshcore.shared.trace

import androidx.compose.runtime.Composable
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_saved_paths_no_response_data
import one.meshcore.shared.resources.contacts_saved_paths_response_times
import one.meshcore.shared.resources.contacts_saved_paths_trend_decreasing
import one.meshcore.shared.resources.contacts_saved_paths_trend_increasing
import one.meshcore.shared.resources.contacts_saved_paths_trend_stable
import one.meshcore.shared.ui.formatTemplate
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Which way a path's round-trip times are moving across its recent runs. */
enum class SparklineTrend {
    INCREASING,
    DECREASING,
    STABLE,
}

/**
 * How far the two halves' means must differ, in ms, before the trend is called. Below it the run-to-run
 * noise on a LoRa path would flip the answer on every trace.
 */
const val TREND_THRESHOLD_MS: Int = 50

/**
 * The trend across [rtts], oldest-first: the mean of the older half against the mean of the newer half.
 * Fewer than two samples has no direction, so it reads as [SparklineTrend.STABLE].
 */
fun sparklineTrend(rtts: List<Int>): SparklineTrend {
    if (rtts.size < 2) return SparklineTrend.STABLE
    val half = rtts.size / 2
    val older = rtts.take(half).sumOf { it.toLong() } / half
    val newer = rtts.takeLast(half).sumOf { it.toLong() } / half
    return when {
        newer > older + TREND_THRESHOLD_MS -> SparklineTrend.INCREASING
        newer < older - TREND_THRESHOLD_MS -> SparklineTrend.DECREASING
        else -> SparklineTrend.STABLE
    }
}

internal fun meanRoundTripMs(rtts: List<Int>): Int? {
    if (rtts.isEmpty()) return null
    return (rtts.sumOf { it.toLong() } / rtts.size).toInt()
}

/** The shipped word for a trend. */
fun SparklineTrend.wordResource(): StringResource = when (this) {
    SparklineTrend.INCREASING -> Res.string.contacts_saved_paths_trend_increasing
    SparklineTrend.DECREASING -> Res.string.contacts_saved_paths_trend_decreasing
    SparklineTrend.STABLE -> Res.string.contacts_saved_paths_trend_stable
}

/**
 * What a screen reader says for a round-trip sparkline. The chart is drawn on a canvas and carries no text
 * of its own, so without this the whole of a path's response history is silent.
 */
@Composable
fun sparklineDescription(rtts: List<Int>): String {
    val average = meanRoundTripMs(rtts) ?: return stringResource(Res.string.contacts_saved_paths_no_response_data)
    val trend = stringResource(sparklineTrend(rtts).wordResource())
    return formatTemplate(
        stringResource(Res.string.contacts_saved_paths_response_times),
        listOf(average, trend),
    )
}
