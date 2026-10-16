package one.meshcore.shared.tools.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.tools_line_of_sight_assumptions
import one.meshcore.shared.resources.tools_line_of_sight_clearance
import one.meshcore.shared.resources.tools_line_of_sight_clearance_percent
import one.meshcore.shared.resources.tools_line_of_sight_diffraction_loss
import one.meshcore.shared.resources.tools_line_of_sight_distance
import one.meshcore.shared.resources.tools_line_of_sight_free_space_loss
import one.meshcore.shared.resources.tools_line_of_sight_obstructions_found
import one.meshcore.shared.resources.tools_line_of_sight_path_loss_breakdown
import one.meshcore.shared.resources.tools_line_of_sight_results
import one.meshcore.shared.resources.tools_line_of_sight_status
import one.meshcore.shared.resources.tools_line_of_sight_status_blocked_subtitle
import one.meshcore.shared.resources.tools_line_of_sight_total
import one.meshcore.shared.resources.tools_line_of_sight_worst_clearance_short
import one.meshcore.shared.rf.ClearanceStatus
import one.meshcore.shared.rf.PathAnalysisResult
import one.meshcore.shared.rf.RelayPathAnalysisResult
import one.meshcore.shared.rf.SegmentAnalysisResult
import one.meshcore.shared.tools.formatFrequencyMHz
import one.meshcore.shared.tools.formatKFactor
import one.meshcore.shared.ui.PathHopChipRow
import one.meshcore.shared.ui.PreferenceHubCardPadding
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.PreferenceHubValueRow
import one.meshcore.shared.ui.SignalChromeRole
import one.meshcore.shared.ui.formatTemplate
import one.meshcore.shared.ui.formattedStringResource
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.tint
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

@Composable
internal fun ResultsCard(analysis: PathAnalysisResult) {
    PreferenceHubSection(
        title = stringResource(Res.string.tools_line_of_sight_results),
        applyHorizontalPadding = false,
    ) {
        ClearanceStatusHeader(
            status = analysis.clearanceStatus,
            clearancePercent = analysis.worstClearancePercent,
        )
        PreferenceHubValueRow(
            stringResource(Res.string.tools_line_of_sight_distance),
            formatLosDistance(analysis.distanceMeters),
        )
        PreferenceHubValueRow(
            stringResource(Res.string.tools_line_of_sight_total),
            formatDb(analysis.totalPathLoss),
        )
    }
    PreferenceHubSection(
        title = stringResource(Res.string.tools_line_of_sight_path_loss_breakdown),
        applyHorizontalPadding = false,
    ) {
        PreferenceHubValueRow(
            stringResource(Res.string.tools_line_of_sight_free_space_loss),
            formatDb(analysis.freeSpacePathLoss),
        )
        formatDiffractionLossDisplay(analysis.peakDiffractionLoss)?.let { diffraction ->
            PreferenceHubValueRow(stringResource(Res.string.tools_line_of_sight_diffraction_loss), diffraction)
        }
        PreferenceHubValueRow(stringResource(Res.string.tools_line_of_sight_total), formatDb(analysis.totalPathLoss))
    }
    PreferenceHubSection(
        title = stringResource(Res.string.tools_line_of_sight_clearance),
        applyHorizontalPadding = false,
    ) {
        PreferenceHubValueRow(
            stringResource(Res.string.tools_line_of_sight_worst_clearance_short),
            "${formatClearancePercent(analysis.worstClearancePercent)}%",
        )
        PreferenceHubValueRow(
            stringResource(Res.string.tools_line_of_sight_obstructions_found),
            analysis.obstructionPoints.size.toString(),
        )
    }
    // Every number above depends on the frequency and k-factor the run used, and neither is otherwise
    // visible beside the result. iOS footnotes the card with both for the same reason.
    Text(
        text = formattedStringResource(
            Res.string.tools_line_of_sight_assumptions,
            formatFrequencyMHz(analysis.frequencyMHz),
            formatKFactor(analysis.refractionK),
        ),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun RelayResultsCard(result: RelayPathAnalysisResult) {
    PreferenceHubSection(
        title = stringResource(Res.string.tools_line_of_sight_results),
        applyHorizontalPadding = false,
    ) {
        ClearanceStatusHeader(
            status = result.overallStatus,
            clearancePercent = minOf(
                result.segmentAR.worstClearancePercent,
                result.segmentRB.worstClearancePercent,
            ),
        )
        SegmentRow(result.segmentAR)
        SegmentRow(result.segmentRB)
        PreferenceHubValueRow(
            stringResource(Res.string.tools_line_of_sight_total),
            formatLosDistance(result.totalDistanceMeters),
        )
    }
    SegmentDetail(result.segmentAR)
    SegmentDetail(result.segmentRB)
}

/** Status, distance, and worst clearance for one relay leg, shown when the card is expanded. */
@Composable
private fun SegmentDetail(segment: SegmentAnalysisResult) {
    PreferenceHubSection(
        applyHorizontalPadding = false,
    ) {
        PathHopChipRow(
            listOf(segment.startLabel, segment.endLabel),
            modifier = Modifier.padding(horizontal = PreferenceHubCardPadding, vertical = 8.dp),
        )
        PreferenceHubValueRow(
            stringResource(Res.string.tools_line_of_sight_status),
            clearanceLabel(segment.clearanceStatus),
        )
        PreferenceHubValueRow(
            stringResource(Res.string.tools_line_of_sight_distance),
            formatLosDistance(segment.distanceMeters),
        )
        PreferenceHubValueRow(
            stringResource(Res.string.tools_line_of_sight_worst_clearance_short),
            "${formatClearancePercent(segment.worstClearancePercent)}%",
        )
    }
}

/** Status and optional percent for a LoS result card. */
@Composable
private fun ClearanceStatusHeader(status: ClearanceStatus, clearancePercent: Double) {
    ListItem(
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = status.statusIcon(),
                    contentDescription = null,
                    tint = status.statusTint(),
                    modifier = Modifier.size(CLEARANCE_STATUS_ICON_DP.dp),
                )
                Text(
                    text = clearanceLabel(status),
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        },
        supportingContent = {
            Text(
                text = if (status == ClearanceStatus.Blocked) {
                    stringResource(Res.string.tools_line_of_sight_status_blocked_subtitle)
                } else {
                    clearancePercentText(clearancePercent)
                },
            )
        },
        modifier = Modifier.fillMaxWidth(),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

private fun ClearanceStatus.statusIcon() = when (this) {
    ClearanceStatus.Clear -> AppIcons.CheckCircle
    ClearanceStatus.Marginal, ClearanceStatus.PartialObstruction -> AppIcons.Warning
    ClearanceStatus.Blocked -> AppIcons.Block
}

internal fun ClearanceStatus.signalChromeRole(): SignalChromeRole = when (this) {
    ClearanceStatus.Clear -> SignalChromeRole.PRIMARY
    ClearanceStatus.Marginal, ClearanceStatus.PartialObstruction -> SignalChromeRole.TERTIARY
    ClearanceStatus.Blocked -> SignalChromeRole.ERROR
}

@Composable
private fun ClearanceStatus.statusTint(): Color = signalChromeRole().tint()

/** One relay leg: status, clearance figure and distance, so the two legs can be compared. */
@Composable
private fun SegmentRow(segment: SegmentAnalysisResult) {
    ListItem(
        headlineContent = {
            PathHopChipRow(listOf(segment.startLabel, segment.endLabel))
        },
        supportingContent = {
            Text(
                text = clearanceLabel(segment.clearanceStatus) + ", " +
                    clearancePercentText(segment.worstClearancePercent) + ", " +
                    formatLosDistance(segment.distanceMeters),
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** The worst Fresnel clearance as a whole percent, from the localized "%d%% clearance" template. */
@Composable
private fun clearancePercentText(percent: Double): String = formatTemplate(
    stringResource(Res.string.tools_line_of_sight_clearance_percent),
    listOf(percent.roundToInt()),
)

private const val CLEARANCE_STATUS_ICON_DP = 20
