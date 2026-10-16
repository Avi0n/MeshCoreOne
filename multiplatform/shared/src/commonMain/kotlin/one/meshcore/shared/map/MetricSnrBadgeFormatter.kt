package one.meshcore.shared.map

import one.meshcore.shared.remotenode.LocalizedLengthSymbols
import one.meshcore.shared.remotenode.MeasurementSystem
import kotlin.math.round

/**
 * SNR badge formatter using locale road distances (km/m or mi/ft). Tests default to metric.
 */
class MetricSnrBadgeFormatter(
    override val snrUnit: String = "dB",
    private val system: MeasurementSystem = MeasurementSystem.METRIC,
    private val symbols: LocalizedLengthSymbols = LocalizedLengthSymbols.LATIN,
) : SnrBadgeFormatter {
    override fun formatDistance(meters: Double): String = formatRoadDistanceMeters(meters, system, symbols)

    override fun formatSnr(snr: Double): String = formatOneDecimal(snr)

    private fun formatOneDecimal(value: Double): String {
        val scaled = round(value * ONE_DECIMAL_SCALE) / ONE_DECIMAL_SCALE
        val asInt = scaled.toInt()
        return if (scaled == asInt.toDouble()) {
            "$asInt.0"
        } else {
            scaled.toString()
        }
    }

    companion object {
        private const val ONE_DECIMAL_SCALE = 10.0
    }
}
