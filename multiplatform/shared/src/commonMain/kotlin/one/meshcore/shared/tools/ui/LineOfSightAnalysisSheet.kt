package one.meshcore.shared.tools.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.tools_line_of_sight_analysis_failed
import one.meshcore.shared.resources.tools_line_of_sight_clear
import one.meshcore.shared.resources.tools_line_of_sight_point_a
import one.meshcore.shared.resources.tools_line_of_sight_point_b
import one.meshcore.shared.resources.tools_line_of_sight_points
import one.meshcore.shared.resources.tools_line_of_sight_retry
import one.meshcore.shared.resources.tools_line_of_sight_share
import one.meshcore.shared.resources.tools_line_of_sight_tap_map_instruction
import one.meshcore.shared.resources.tools_line_of_sight_terrain_profile
import one.meshcore.shared.rf.ClearanceStatus
import one.meshcore.shared.rf.formatLineOfSightShareText
import one.meshcore.shared.tools.LineOfSightViewModel
import one.meshcore.shared.tools.LosAnalysisStatus
import one.meshcore.shared.tools.LosPointId
import one.meshcore.shared.tools.analyze
import one.meshcore.shared.tools.analyzeWithRepeater
import one.meshcore.shared.tools.clear
import one.meshcore.shared.tools.clearPointA
import one.meshcore.shared.tools.clearPointB
import one.meshcore.shared.tools.offPathSegmentArDistanceMeters
import one.meshcore.shared.tools.repeaterVisualizationPathFraction
import one.meshcore.shared.tools.updateAdditionalHeight
import one.meshcore.shared.tools.updateRepeaterPosition
import one.meshcore.shared.ui.PreferenceHubCardPadding
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.icons.CloseIconButton
import one.meshcore.shared.ui.tint
import org.jetbrains.compose.resources.stringResource

/** Retapping the selected point clears it; tapping a different point selects that one. */
internal fun toggledLosPoint(current: LosPointId?, tapped: LosPointId): LosPointId? =
    if (current == tapped) null else tapped

private fun runLosAnalysis(viewModel: LineOfSightViewModel) {
    if (viewModel.repeaterPoint != null) {
        viewModel.analyzeWithRepeater()
    } else {
        viewModel.analyze()
    }
}

@Composable
internal fun AnalysisSheetContent(
    viewModel: LineOfSightViewModel,
    clearanceLabels: Map<ClearanceStatus, String>,
    seaLevelNote: String,
    shareText: (String) -> Unit,
    showMapHints: Boolean,
    showDragHint: Boolean,
) {
    val revision by viewModel.revision.collectAsStateWithLifecycle()
    check(revision >= 0)

    LosPointsSection(
        viewModel = viewModel,
        seaLevelNote = seaLevelNote,
        shareText = shareText,
        showMapHints = showMapHints,
        showDragHint = showDragHint,
    )
    val hasResult = viewModel.analysisStatus is LosAnalysisStatus.Result ||
        viewModel.analysisStatus is LosAnalysisStatus.RelayResult
    val isError = viewModel.analysisStatus is LosAnalysisStatus.Error
    val terrainTitle = stringResource(Res.string.tools_line_of_sight_terrain_profile)

    if (shouldShowLosAnalyzeButton(viewModel.canAnalyze, hasResult, isError)) {
        AnalyzeLosButton(
            isAnalyzing = viewModel.isAnalyzing,
            onClick = { runLosAnalysis(viewModel) },
        )
    }
    if (shouldShowLosRfSettingsBeforeResult(viewModel.canAnalyze, hasResult, isError)) {
        RfSettingsSection(viewModel = viewModel)
    }

    when (val status = viewModel.analysisStatus) {
        LosAnalysisStatus.Idle -> Unit
        is LosAnalysisStatus.Error -> LosAnalysisError(status, viewModel)
        is LosAnalysisStatus.Result -> LosAnalysisResult(
            status = status,
            viewModel = viewModel,
            clearanceLabels = clearanceLabels,
            seaLevelNote = seaLevelNote,
            terrainTitle = terrainTitle,
            shareText = shareText,
            showDragHint = showDragHint,
        )
        is LosAnalysisStatus.RelayResult -> LosAnalysisRelay(
            status = status,
            viewModel = viewModel,
            terrainTitle = terrainTitle,
            showDragHint = showDragHint,
        )
    }

    if (hasResult) {
        OutlinedButton(onClick = { viewModel.clear() }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(Res.string.tools_line_of_sight_clear))
        }
    }
}

@Composable
private fun LosPointsSection(
    viewModel: LineOfSightViewModel,
    seaLevelNote: String,
    shareText: (String) -> Unit,
    showMapHints: Boolean,
    showDragHint: Boolean,
) {
    val revision by viewModel.revision.collectAsStateWithLifecycle()
    check(revision >= 0)

    var editingPoint by remember { mutableStateOf<LosPointId?>(null) }
    PreferenceHubSection(
        title = stringResource(Res.string.tools_line_of_sight_points),
        applyHorizontalPadding = false,
    ) {
        val relocatingPoint = viewModel.relocatingPoint
        if (relocatingPoint != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(PreferenceHubCardPadding),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RelocatingBanner(
                    pointName = stringResource(relocatingPoint.labelResource()),
                    modifier = Modifier.weight(1f),
                )
                CloseIconButton(
                    onClick = { viewModel.relocatingPoint = null },
                    contentDescription = stringResource(losRelocateDismissSpokenResource()),
                )
            }
        } else {
            if (showMapHints && viewModel.pointA == null && viewModel.pointB == null) {
                Text(
                    text = stringResource(Res.string.tools_line_of_sight_tap_map_instruction),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(PreferenceHubCardPadding),
                )
            }

            PointSummaryRow(
                title = stringResource(Res.string.tools_line_of_sight_point_a),
                markerLabel = TERRAIN_ENDPOINT_A,
                markerRole = LOS_ENDPOINT_A_ROLE,
                point = viewModel.pointA,
                shareText = shareText,
                isEditing = editingPoint == LosPointId.POINT_A,
                onToggleEdit = { editingPoint = toggledLosPoint(editingPoint, LosPointId.POINT_A) },
                onClear = { viewModel.clearPointA() },
                onRelocate = {
                    viewModel.relocatingPoint = toggledLosPoint(viewModel.relocatingPoint, LosPointId.POINT_A)
                },
                relocateEnabled = viewModel.relocatingPoint == null ||
                    viewModel.relocatingPoint == LosPointId.POINT_A,
                onHeight = { viewModel.updateAdditionalHeight(LosPointId.POINT_A, it) },
            )
            if (viewModel.shouldShowRepeaterRow) {
                RepeaterSection(
                    viewModel = viewModel,
                    shareText = shareText,
                    showDragHint = showDragHint,
                    isEditing = editingPoint == LosPointId.REPEATER,
                    onToggleEdit = { editingPoint = toggledLosPoint(editingPoint, LosPointId.REPEATER) },
                )
            }
            PointSummaryRow(
                title = stringResource(Res.string.tools_line_of_sight_point_b),
                markerLabel = TERRAIN_ENDPOINT_B,
                markerRole = LOS_ENDPOINT_B_ROLE,
                point = viewModel.pointB,
                shareText = shareText,
                isEditing = editingPoint == LosPointId.POINT_B,
                onToggleEdit = { editingPoint = toggledLosPoint(editingPoint, LosPointId.POINT_B) },
                onClear = { viewModel.clearPointB() },
                onRelocate = {
                    viewModel.relocatingPoint = toggledLosPoint(viewModel.relocatingPoint, LosPointId.POINT_B)
                },
                relocateEnabled = viewModel.relocatingPoint == null ||
                    viewModel.relocatingPoint == LosPointId.POINT_B,
                onHeight = { viewModel.updateAdditionalHeight(LosPointId.POINT_B, it) },
            )
            if (viewModel.elevationFetchFailed) {
                Row(
                    modifier = Modifier.padding(PreferenceHubCardPadding),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(LOS_SECTION_ICON_GAP),
                ) {
                    Icon(
                        imageVector = LOS_ELEVATION_UNAVAILABLE_ICON,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(LOS_SECTION_ICON_DP.dp),
                    )
                    Text(
                        text = seaLevelNote,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun LosAnalysisError(status: LosAnalysisStatus.Error, viewModel: LineOfSightViewModel) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(
            imageVector = AppIcons.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(LOS_ANALYSIS_FAILED_ICON_SIZE),
        )
        Text(
            text = stringResource(Res.string.tools_line_of_sight_analysis_failed),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = status.failure.message(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FilledTonalButton(
            onClick = { runLosAnalysis(viewModel) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(Res.string.tools_line_of_sight_retry))
        }
    }
}

@Composable
private fun LosAnalysisResult(
    status: LosAnalysisStatus.Result,
    viewModel: LineOfSightViewModel,
    clearanceLabels: Map<ClearanceStatus, String>,
    seaLevelNote: String,
    terrainTitle: String,
    shareText: (String) -> Unit,
    showDragHint: Boolean,
) {
    val shareLabels = lineOfSightShareLabels()
    ResultsCard(status.value)
    LosTerrainProfile(
        viewModel = viewModel,
        title = terrainTitle,
        showDragHint = showDragHint,
    )
    OutlinedButton(
        onClick = {
            val clearance = clearanceLabels[status.value.clearanceStatus].orEmpty()
            val note = if (viewModel.elevationFetchFailed) seaLevelNote else terrainTitle
            shareText(
                formatLineOfSightShareText(
                    analysis = status.value,
                    clearanceLabel = clearance,
                    elevationNote = note,
                    labels = shareLabels,
                ),
            )
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(Res.string.tools_line_of_sight_share))
    }
    RfSettingsSection(viewModel = viewModel)
}

@Composable
private fun LosAnalysisRelay(
    status: LosAnalysisStatus.RelayResult,
    viewModel: LineOfSightViewModel,
    terrainTitle: String,
    showDragHint: Boolean,
) {
    RelayResultsCard(status.value)
    LosTerrainProfile(
        viewModel = viewModel,
        title = terrainTitle,
        showDragHint = showDragHint,
    )
    RfSettingsSection(viewModel = viewModel)
}

@Composable
private fun LosTerrainProfile(viewModel: LineOfSightViewModel, title: String, showDragHint: Boolean) {
    val revision by viewModel.revision.collectAsStateWithLifecycle()
    check(revision >= 0)

    val onPathRepeater = viewModel.repeaterPoint?.takeIf { it.isOnPath }
    val visualizationFraction = repeaterVisualizationPathFraction(
        viewModel.repeaterPoint,
        viewModel.elevationProfileAr,
        viewModel.elevationProfileRb,
    )
    val junctionDistanceMeters = offPathSegmentArDistanceMeters(
        viewModel.repeaterPoint,
        viewModel.analysisStatus,
    )
    TerrainProfileHeader(title, viewModel.refractionK)
    FresnelTerrainChart(
        samples = viewModel.terrainProfileSamples,
        terrainColor = MaterialTheme.colorScheme.outline,
        losColor = MaterialTheme.colorScheme.onSurface,
        fresnelColor = LOS_FRESNEL_CLEAR_ROLE.tint(),
        obstructedColor = LOS_FRESNEL_OBSTRUCTED_ROLE.tint().copy(alpha = FRESNEL_OBSTRUCTED_ALPHA),
        repeaterPathFraction = visualizationFraction,
        junctionDistanceMeters = junctionDistanceMeters,
        onRepeaterDrag = if (onPathRepeater != null) {
            viewModel::updateRepeaterPosition
        } else {
            null
        },
        showDragHint = showDragHint && onPathRepeater != null,
    )
    TerrainProfileLegend(showRepeater = viewModel.repeaterPoint != null)
    IndirectRouteCaption(offPath = viewModel.repeaterPoint?.isOnPath == false)
}
