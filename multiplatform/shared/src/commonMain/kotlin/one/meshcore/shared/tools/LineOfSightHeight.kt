package one.meshcore.shared.tools

import one.meshcore.shared.map.formatRoadDistanceMeters
import one.meshcore.shared.remotenode.LPPDisplay
import one.meshcore.shared.remotenode.LPPSensorType
import one.meshcore.shared.remotenode.LocalizedLengthSymbols
import one.meshcore.shared.remotenode.MeasurementSystem

/** iOS `HeightEditorGrid` stepper range `0.0...200.0`. */
const val ADDITIONAL_HEIGHT_MIN_M = 0.0
const val ADDITIONAL_HEIGHT_MAX_M = 200.0
const val ADDITIONAL_HEIGHT_STEP_M = 1.0

/** iOS non-metric `HeightEditorGrid` step: one foot in metres. */
const val ADDITIONAL_HEIGHT_STEP_IMPERIAL_M = 0.3048

fun clampAdditionalHeightMeters(meters: Double): Double =
    meters.coerceIn(ADDITIONAL_HEIGHT_MIN_M, ADDITIONAL_HEIGHT_MAX_M)

/** One stepper increment, matching iOS `heightStep` (1 m metric, 1 ft otherwise). */
fun additionalHeightStepMeters(system: MeasurementSystem): Double =
    if (system == MeasurementSystem.IMPERIAL) ADDITIONAL_HEIGHT_STEP_IMPERIAL_M else ADDITIONAL_HEIGHT_STEP_M

/**
 * Ground / additional / total height for the LoS editor, matching iOS `Measurement` abbreviated length.
 * Analysis still uses metres; only the readout converts.
 */
fun formatLosHeightMeters(
    meters: Double,
    system: MeasurementSystem,
    symbols: LocalizedLengthSymbols = LocalizedLengthSymbols.LATIN,
): String {
    val converted = LPPSensorType.ALTITUDE.convertedValue(meters, system)
    val unit = LPPSensorType.ALTITUDE.localizedUnitSymbol(system, symbols)
    val digits = LPPSensorType.ALTITUDE.convertedFractionLength(system)
    return "${LPPDisplay.formatFixed(converted, digits)} $unit"
}

/**
 * Path distance for LoS results, matching iOS `LOSFormatters.formatDistance` road usage.
 */
fun formatLosDistanceMeters(
    meters: Double,
    system: MeasurementSystem,
    symbols: LocalizedLengthSymbols = LocalizedLengthSymbols.LATIN,
): String = formatRoadDistanceMeters(meters, system, symbols)

/** How long the first-time on-path repeater drag hint stays up. */
const val REPEATER_DRAG_HINT_MS = 5_000L

/**
 * iOS shows the drag hint once: the first time a repeater appears on the path.
 * [hadRepeater] is the previous composition's presence so a later on-path tweak does not re-fire.
 */
fun shouldShowRepeaterDragHint(hadRepeater: Boolean, repeaterOnPath: Boolean, hasSeenHint: Boolean): Boolean =
    !hadRepeater && repeaterOnPath && !hasSeenHint
