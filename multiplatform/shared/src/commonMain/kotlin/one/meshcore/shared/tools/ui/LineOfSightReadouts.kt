package one.meshcore.shared.tools.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import one.meshcore.shared.remotenode.LPPDisplay
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.tools_line_of_sight
import one.meshcore.shared.resources.tools_line_of_sight_clearance
import one.meshcore.shared.resources.tools_line_of_sight_diffraction_loss
import one.meshcore.shared.resources.tools_line_of_sight_distance
import one.meshcore.shared.resources.tools_line_of_sight_free_space_loss
import one.meshcore.shared.resources.tools_line_of_sight_frequency
import one.meshcore.shared.resources.tools_line_of_sight_refraction
import one.meshcore.shared.resources.tools_line_of_sight_status_blocked
import one.meshcore.shared.resources.tools_line_of_sight_status_clear
import one.meshcore.shared.resources.tools_line_of_sight_status_marginal
import one.meshcore.shared.resources.tools_line_of_sight_status_partial_obstruction
import one.meshcore.shared.resources.tools_line_of_sight_total
import one.meshcore.shared.rf.ClearanceStatus
import one.meshcore.shared.rf.LineOfSightShareLabels
import one.meshcore.shared.tools.formatLosDistanceMeters
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

@Composable
internal fun clearanceLabel(status: ClearanceStatus): String = when (status) {
    ClearanceStatus.Clear -> stringResource(Res.string.tools_line_of_sight_status_clear)
    ClearanceStatus.Marginal -> stringResource(Res.string.tools_line_of_sight_status_marginal)
    ClearanceStatus.PartialObstruction ->
        stringResource(Res.string.tools_line_of_sight_status_partial_obstruction)
    ClearanceStatus.Blocked -> stringResource(Res.string.tools_line_of_sight_status_blocked)
}

@Composable
internal fun clearanceLabels(): Map<ClearanceStatus, String> = mapOf(
    ClearanceStatus.Clear to stringResource(Res.string.tools_line_of_sight_status_clear),
    ClearanceStatus.Marginal to stringResource(Res.string.tools_line_of_sight_status_marginal),
    ClearanceStatus.PartialObstruction to stringResource(Res.string.tools_line_of_sight_status_partial_obstruction),
    ClearanceStatus.Blocked to stringResource(Res.string.tools_line_of_sight_status_blocked),
)

@Composable
internal fun lineOfSightShareLabels(): LineOfSightShareLabels = LineOfSightShareLabels(
    title = stringResource(Res.string.tools_line_of_sight),
    distance = stringResource(Res.string.tools_line_of_sight_distance),
    clearance = stringResource(Res.string.tools_line_of_sight_clearance),
    freeSpaceLoss = stringResource(Res.string.tools_line_of_sight_free_space_loss),
    diffractionLoss = stringResource(Res.string.tools_line_of_sight_diffraction_loss),
    totalLoss = stringResource(Res.string.tools_line_of_sight_total),
    frequency = stringResource(Res.string.tools_line_of_sight_frequency),
    refraction = stringResource(Res.string.tools_line_of_sight_refraction),
)

@Composable
internal fun formatLosDistance(meters: Double): String =
    formatLosDistanceMeters(meters, LPPDisplay.currentMeasurementSystem(), LPPDisplay.currentLengthSymbols())

internal fun formatDb(value: Double): String {
    val rounded = (value * ONE_DECIMAL_SCALE).toLong() / ONE_DECIMAL_SCALE
    return "$rounded dB"
}

/** iOS `LOSFormatters.formatDiffractionLoss`: omit when under 0.1 dB, otherwise "+ N.N dB". */
internal fun formatDiffractionLossDisplay(loss: Double): String? {
    if (kotlin.math.abs(loss) < DIFFRACTION_LOSS_VISIBLE_DB) return null
    return "+ ${formatDb(loss)}"
}

/** iOS `LOSFormatters.formatClearancePercent`: whole percent clamped to 0...100. */
internal fun formatClearancePercent(percent: Double): Int = percent.roundToInt().coerceIn(0, FULL_CLEARANCE_PERCENT)

private const val DIFFRACTION_LOSS_VISIBLE_DB = 0.1

private const val FULL_CLEARANCE_PERCENT = 100

private const val ONE_DECIMAL_SCALE = 10.0
