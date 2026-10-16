package one.meshcore.shared.tools.ui

import one.meshcore.shared.trace.HopSnrStats
import one.meshcore.shared.ui.formatTemplate
import kotlin.math.abs

/**
 * One decimal place, with the sign carried separately from the digits. Integer division of the tenths
 * gives a whole part of 0 for everything between -1 and 0, so the minus is lost and a marginal -0.5 dB
 * link reads as a good one.
 */
internal fun formatOneDecimalSigned(value: Double): String {
    val tenths = (value * TENTHS_PER_UNIT).toInt()
    val magnitude = abs(tenths)
    val sign = if (tenths < 0) "-" else ""
    return "$sign${magnitude / TENTHS_PER_UNIT_INT}.${magnitude % TENTHS_PER_UNIT_INT}"
}

/**
 * Localized hop SNR caption for a trace result row. The start node transmitted rather than received,
 * so it has no SNR line. A completed batch uses avg/min/max; a single run uses that hop's own SNR.
 */
internal fun hopSnrCaption(
    isStartNode: Boolean,
    hopSnr: Double,
    stats: HopSnrStats?,
    snrTemplate: String,
    avgTemplate: String,
): String? {
    if (isStartNode) return null
    return if (stats != null) {
        formatTemplate(
            avgTemplate,
            listOf(
                formatOneDecimalSigned(stats.avg),
                formatOneDecimalSigned(stats.min),
                formatOneDecimalSigned(stats.max),
            ),
        )
    } else {
        formatTemplate(snrTemplate, listOf(formatOneDecimalSigned(hopSnr)))
    }
}

private const val TENTHS_PER_UNIT = 10.0
private const val TENTHS_PER_UNIT_INT = 10
