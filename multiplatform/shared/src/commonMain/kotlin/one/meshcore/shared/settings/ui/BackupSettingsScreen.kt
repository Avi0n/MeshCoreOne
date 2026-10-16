package one.meshcore.shared.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import one.meshcore.shared.backup.BackupExportSuccessSummary
import one.meshcore.shared.backup.BackupImportResult
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.settings_backup_file_backup_footer
import one.meshcore.shared.resources.settings_backup_file_backup_radio_config
import one.meshcore.shared.ui.PreferenceHubFooter
import one.meshcore.shared.ui.preferenceHubEdgeScroll
import one.meshcore.shared.ui.rememberAppHaptics
import one.meshcore.shared.ui.rememberDetachedScope
import org.jetbrains.compose.resources.stringResource

/**
 * Settings → Backup: file export/import. Export confirm is an `AlertDialog`; preview, import
 * result, and export success are nested destinations. A picked file that cannot be parsed uses
 * `ThreadErrorDialog`, not a nested error screen.
 */
@Composable
fun BackupSettingsScreen(
    isRadioConnected: Boolean,
    onExportConfirmed: suspend () -> BackupExportSuccessSummary? = { null },
    /**
     * Product path: pick a `.mc1backup` file and return uncompressed JSON. Null hides the file
     * button. Merge waits for the preview confirm, matching iOS `ImportPreviewSheet`.
     */
    onImportFile: (suspend () -> String?)? = null,
    /** Secondary path: paste uncompressed JSON (debug / advanced). */
    onImportConfirmed: suspend (String) -> BackupImportResult? = { null },
    workspace: BackupSettingsWorkspace? = null,
    modifier: Modifier = Modifier,
) {
    val state = workspace?.state ?: remember { BackupSettingsState() }
    // iOS disables both rows while export, import, or parse is live so the two cannot share the store.
    val isBusy = backupSettingsBusy(state.isExporting, state.isImporting, state.isParsing)
    // A restore writes many rows with no enclosing transaction, so an abort halfway leaves the database
    // partly overwritten; an aborted export leaves a truncated share file. iOS holds the import in a
    // view-model `Task` that survives its view for the same reason.
    val commitScope = workspace?.scope ?: rememberDetachedScope()
    val haptics = rememberAppHaptics()

    // The iOS `ImportPreviewSheet` / `ExportSuccessContent` sensory feedback: a success touch when an
    // import restores changes (export fires inline on completion), a failure touch on any backup error.
    LaunchedEffect(state.importResult) {
        if (backupImportRestored(state.importResult)) haptics.success()
    }
    LaunchedEffect(state.failure) { if (state.failure != null) haptics.failure() }

    val importPhase = backupImportSheetPhase(
        isImporting = state.isImporting,
        preview = state.preview,
        importResult = state.importResult,
        importCancelled = state.importCancelled,
        failure = state.failure,
    )
    if (showBackupExportSuccess(state.exportSummary)) {
        BackupExportSuccessOverlay(summary = state.exportSummary, onDismiss = { state.exportSummary = null })
        return
    }
    if (showBackupImportOverlay(importPhase)) {
        BackupImportOverlay(
            state = BackupImportOverlayState(
                phase = importPhase,
                preview = state.preview,
                importResult = state.importResult,
                failure = state.failure,
                isCancellingImport = state.isCancellingImport,
                isExporting = state.isExporting,
                isParsing = state.isParsing,
                isRadioConnected = isRadioConnected,
                importJson = state.importJson,
            ),
            actions = BackupImportOverlayActions(
                onDismiss = { state.dismissImportSheet() },
                onSkip = { state.clearImportOverlay() },
                onStart = {
                    state.isImporting = true
                    state.isCancellingImport = false
                    state.importCancelled = false
                },
                onImported = { result ->
                    state.preview = null
                    state.importJson = ""
                    state.importResult = result
                },
                onCancelled = {
                    state.preview = null
                    state.importJson = ""
                    state.importCancelled = true
                },
                onFailed = { error -> state.failure = BackupFailure(error) },
                onFinished = {
                    state.isImporting = false
                    state.isCancellingImport = false
                    state.importJob = null
                },
                onJob = { job -> state.importJob = job },
                onCancel = { state.cancelImport() },
                commitScope = commitScope,
                onImportConfirmed = onImportConfirmed,
            ),
        )
        return
    }

    Column(
        modifier.preferenceHubEdgeScroll(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BackupFileRows(
            state = BackupFileRowState(
                isExporting = state.isExporting,
                isBusy = isBusy,
                isParsing = state.isParsing,
                isRadioConnected = isRadioConnected,
                offersFileImport = onImportFile != null,
            ),
            actions = BackupFileRowActions(
                onExportClick = { state.showExportNotice = true },
                onBeginParse = { state.beginParse(onImportFile, commitScope) },
            ),
        )
        PreferenceHubFooter(
            stringResource(Res.string.settings_backup_file_backup_footer),
            applyHorizontalPadding = false,
        )
        PreferenceHubFooter(
            stringResource(Res.string.settings_backup_file_backup_radio_config),
            applyHorizontalPadding = false,
        )
    }
    BackupExportConfirmDialog(
        visible = state.showExportNotice,
        isBusy = isBusy,
        onDismiss = { state.showExportNotice = false },
        onConfirm = {
            state.showExportNotice = false
            state.beginExport(commitScope, onExportConfirmed) { haptics.success() }
        },
    )
    BackupFailureDialog(
        failure = state.failure,
        preview = state.preview,
        onDismiss = { state.failure = null },
    )
}
