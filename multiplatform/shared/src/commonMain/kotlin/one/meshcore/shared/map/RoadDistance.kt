package one.meshcore.shared.map

import one.meshcore.shared.remotenode.LPPDisplay
import one.meshcore.shared.remotenode.LPPSensorType
import one.meshcore.shared.remotenode.LocalizedLengthSymbols
import one.meshcore.shared.remotenode.MeasurementSystem

/**
 * Locale road distance, matching iOS `Measurement` `.road` usage: km/m on metric, mi (or ft under
 * 0.1 mi) otherwise. Shared by Line of Sight results and map SNR/path badges.
 */
fun formatRoadDistanceMeters(
    meters: Double,
    system: MeasurementSystem,
    symbols: LocalizedLengthSymbols = LocalizedLengthSymbols.LATIN,
): String = when {
    system == MeasurementSystem.IMPERIAL && meters / METERS_PER_MILE >= ROAD_MILES_MIN -> {
        "${LPPDisplay.formatFixed(meters / METERS_PER_MILE, 1)} ${symbols.mile}"
    }
    system == MeasurementSystem.IMPERIAL -> {
        val converted = LPPSensorType.ALTITUDE.convertedValue(meters, system)
        val digits = LPPSensorType.ALTITUDE.convertedFractionLength(system)
        "${LPPDisplay.formatFixed(converted, digits)} ${symbols.foot}"
    }
    meters >= METERS_PER_KM -> "${LPPDisplay.formatFixed(meters / METERS_PER_KM, 1)} ${symbols.kilometer}"
    else -> "${meters.toLong()} ${symbols.meter}"
}

private const val METERS_PER_MILE = 1609.344
private const val METERS_PER_KM = 1000.0
private const val ROAD_MILES_MIN = 0.1
