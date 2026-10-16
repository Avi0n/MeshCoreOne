package one.meshcore.shared.tools.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import one.meshcore.shared.backup.SavedTracePathDto
import one.meshcore.shared.pathedit.PathHop
import one.meshcore.shared.pathedit.ui.PATH_EDIT_ADD_HOP_ICON
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_common_cancel
import one.meshcore.shared.resources.contacts_path_edit_add_hop
import one.meshcore.shared.resources.contacts_results_save_path
import one.meshcore.shared.resources.contacts_trace_clear_path
import one.meshcore.shared.resources.contacts_trace_list_copy_path
import one.meshcore.shared.resources.contacts_trace_list_range_warning
import one.meshcore.shared.trace.SavedTracePathStore
import one.meshcore.shared.trace.TracePathBuilder
import one.meshcore.shared.trace.traceHashModeForSize
import one.meshcore.shared.ui.ButtonIconLabel
import one.meshcore.shared.ui.PreferenceHubFooter
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.plainTextClipEntry
import one.meshcore.shared.ui.preferenceHubEdgeFillScroll
import one.meshcore.shared.ui.rememberAppHaptics
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TracePathListPane(
    ui: TracePathUiState,
    scrollState: ScrollState,
    runTraceRequester: BringIntoViewRequester,
    outboundPath: List<PathHop>,
    builder: TracePathBuilder,
    emptyPathHint: String,
    supportsHashOverride: Boolean,
    hashSize: Int,
    canRunTrace: Boolean,
    isRadioReady: Boolean,
    onRun: () -> Unit,
    onCancel: () -> Unit,
    savedPathStore: SavedTracePathStore?,
    hasRadio: Boolean,
    canSave: Boolean,
    onSave: () -> Unit,
    matchingSaved: SavedTracePathDto?,
    resultMatchesPath: Boolean,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val haptics = rememberAppHaptics()
    Column(
        modifier = modifier.preferenceHubEdgeFillScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TraceHopListSection(
            hops = outboundPath,
            enabled = !ui.isRunning,
            emptyPathHint = emptyPathHint,
            onMoveUp = { index -> ui.afterHopEdit { builder.moveHop(index, index - 1) } },
            onMoveDown = { index -> ui.afterHopEdit { builder.moveHop(index, index + 2) } },
            onRemove = { index -> ui.afterHopEdit { builder.removeHop(index) } },
        )
        OutlinedButton(
            onClick = { ui.showHopPicker = true },
            enabled = !ui.isRunning,
            modifier = Modifier.fillMaxWidth(),
        ) {
            ButtonIconLabel(
                title = stringResource(Res.string.contacts_path_edit_add_hop),
                icon = PATH_EDIT_ADD_HOP_ICON,
            )
        }
        if (supportsHashOverride) {
            TraceHashSizeRow(
                hashSize = hashSize,
                enabled = !ui.isRunning,
                onSelect = { size ->
                    ui.afterHopEdit { builder.setTraceHashMode(traceHashModeForSize(size)) }
                },
            )
        }
        PreferenceHubSection(applyHorizontalPadding = false) {
            TraceAutoReturnRow(
                autoReturn = ui.autoReturn,
                enabled = !ui.isRunning,
                onChange = { ui.autoReturn = it },
            )
        }
        if (outboundPath.isNotEmpty()) {
            OutlinedButton(
                onClick = {
                    val text = builder.fullPathString(ui.autoReturn)
                    scope.launch {
                        clipboard.setClipEntry(plainTextClipEntry(text))
                        haptics.success()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.contacts_trace_list_copy_path))
            }
            OutlinedButton(
                onClick = { ui.showingClearPath = true },
                enabled = !ui.isRunning,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(Res.string.contacts_trace_clear_path),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        PreferenceHubSection(applyHorizontalPadding = false) {
            TraceBatchRow(
                enabled = !ui.isRunning,
                batchEnabled = ui.batchEnabled,
                batchCount = ui.batchCount,
                onBatchEnabledChange = { ui.batchEnabled = it },
                onBatchCountChange = { ui.batchCount = it },
            )
        }
        TraceRunButton(
            isRunning = ui.isRunning,
            batchProgress = ui.batchProgress,
            enabled = canRunTrace,
            isRadioReady = isRadioReady,
            batchEnabled = ui.batchEnabled,
            batchSize = ui.batchCount,
            onClick = onRun,
            modifier = Modifier
                .fillMaxWidth()
                .bringIntoViewRequester(runTraceRequester)
                .onGloballyPositioned { coordinates ->
                    val root = coordinates.findRootCoordinates().size
                    val visible = isLaidOutInWindow(
                        bounds = coordinates.boundsInWindow(),
                        windowWidth = root.width.toFloat(),
                        windowHeight = root.height.toFloat(),
                    )
                    ui.showJumpToPath = !visible
                },
        )
        // A trace answers only if the last repeater can still hear this radio, which explains most timeouts.
        if (outboundPath.isNotEmpty()) {
            PreferenceHubFooter(
                stringResource(Res.string.contacts_trace_list_range_warning),
                applyHorizontalPadding = false,
            )
        }
        if (ui.isRunning) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(Res.string.contacts_common_cancel))
            }
        }
        if (savedPathStore != null && hasRadio) {
            OutlinedButton(
                // Saving stores the traced path plus its runs, so it needs a successful result for the
                // path as it stands now; a hop edit since the run invalidates it.
                onClick = onSave,
                enabled = !ui.isRunning && canSave,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.contacts_results_save_path))
            }
        }
        BatchSummarySection(
            results = ui.batchResults,
            batchProgress = ui.batchProgress,
            isBatchInProgress = ui.isRunning,
        )
        matchingSaved?.let { saved ->
            ui.result?.takeIf { it.success }?.let { success ->
                ComparisonAndSparkline(
                    currentMs = success.durationMs,
                    saved = saved,
                    onOpenDetail = { ui.detailPath = saved },
                )
            }
        }
        ui.result?.takeIf { it.success }?.let { success ->
            OutlinedButton(
                onClick = {
                    val text = success.tracedPathString
                    scope.launch {
                        clipboard.setClipEntry(plainTextClipEntry(text))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.contacts_trace_list_copy_path))
            }
        }
        if (
            traceListShowsInlineResult(
                showingSheet = ui.showingListResults,
                resultSucceeded = ui.result?.success == true,
                resultMatchesPath = resultMatchesPath,
            )
        ) {
            TraceResultSection(
                result = ui.result.takeIf { resultMatchesPath },
                batchResults = ui.batchResults,
                isBatchInProgress = ui.isRunning,
                onShowDistanceInfo = { ui.showingDistanceInfo = true },
            )
        }
    }
}

/** A hop edit replaces the path the last error described, so the banner goes away with the edit. */
private inline fun TracePathUiState.afterHopEdit(edit: () -> Unit) {
    edit()
    errorMessage = null
}
