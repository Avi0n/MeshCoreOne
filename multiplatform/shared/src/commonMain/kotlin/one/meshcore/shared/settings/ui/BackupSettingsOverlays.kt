package one.meshcore.shared.settings.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import one.meshcore.shared.backup.BackupExportSuccessSummary
import one.meshcore.shared.backup.BackupImportPreview
import one.meshcore.shared.backup.BackupImportResult
import one.meshcore.shared.backup.canCommitBackupImport
import one.meshcore.shared.chat.ui.ThreadErrorDialog
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.localizable_common_ok
import one.meshcore.shared.resources.settings_backup_export_alert_cancel
import one.meshcore.shared.resources.settings_backup_export_alert_export
import one.meshcore.shared.resources.settings_backup_export_alert_message
import one.meshcore.shared.resources.settings_backup_export_alert_title
import one.meshcore.shared.resources.settings_backup_export_progress
import one.meshcore.shared.resources.settings_backup_export_subtitle
import one.meshcore.shared.resources.settings_backup_export_success_title
import one.meshcore.shared.resources.settings_backup_export_title
import one.meshcore.shared.resources.settings_backup_file_backup_header
import one.meshcore.shared.resources.settings_backup_import_disabled_when_connected
import one.meshcore.shared.resources.settings_backup_import_error_dismiss
import one.meshcore.shared.resources.settings_backup_import_parsing
import one.meshcore.shared.resources.settings_backup_import_preview_button
import one.meshcore.shared.resources.settings_backup_import_preview_cancel
import one.meshcore.shared.resources.settings_backup_import_subtitle
import one.meshcore.shared.resources.settings_backup_import_title
import one.meshcore.shared.ui.ConfirmAlertDialog
import one.meshcore.shared.ui.FullScreenOverlay
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.formatTemplate
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.preferenceHubFormScroll
import one.meshcore.shared.ui.preferenceHubOverlayFill
import org.jetbrains.compose.resources.stringResource

/** Spinner diameter matching the iOS export/import ProgressView on the backup rows. */
private const val BACKUP_PROGRESS_SIZE = 20

internal data class BackupImportOverlayState(
    val phase: BackupImportSheetPhase,
    val preview: BackupImportPreview?,
    val importResult: BackupImportResult?,
    val failure: BackupFailure?,
    val isCancellingImport: Boolean,
    val isExporting: Boolean,
    val isParsing: Boolean,
    val isRadioConnected: Boolean,
    val importJson: String,
)

internal data class BackupImportOverlayActions(
    val onDismiss: () -> Unit,
    val onSkip: () -> Unit,
    val onStart: () -> Unit,
    val onImported: (BackupImportResult?) -> Unit,
    val onCancelled: () -> Unit,
    val onFailed: (Exception) -> Unit,
    val onFinished: () -> Unit,
    val onJob: (Job) -> Unit,
    val onCancel: () -> Unit,
    val commitScope: CoroutineScope,
    val onImportConfirmed: suspend (String) -> BackupImportResult?,
)

internal data class BackupFileRowState(
    val isExporting: Boolean,
    val isBusy: Boolean,
    val isParsing: Boolean,
    val isRadioConnected: Boolean,
    val offersFileImport: Boolean,
)

internal data class BackupFileRowActions(val onExportClick: () -> Unit, val onBeginParse: () -> Unit)

@Composable
internal fun backupErrorText(failure: BackupFailure): String {
    val copy = backupErrorCopy(failure)
    return formatTemplate(stringResource(copy.resource), copy.args)
}

@Composable
internal fun BackupExportSuccessOverlay(summary: BackupExportSuccessSummary?, onDismiss: () -> Unit) {
    val ready = summary?.takeIf { showBackupExportSuccess(it) } ?: return
    FullScreenOverlay(
        onDismiss = onDismiss,
        title = stringResource(Res.string.settings_backup_export_success_title),
    ) { innerPadding ->
        Column(Modifier.preferenceHubOverlayFill(innerPadding)) {
            Column(preferenceHubFormScroll()) {
                ExportSuccessSection(summary = ready)
            }
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.localizable_common_ok))
            }
        }
    }
}

@Composable
internal fun BackupImportOverlay(state: BackupImportOverlayState, actions: BackupImportOverlayActions) {
    if (!showBackupImportOverlay(state.phase)) return
    FullScreenOverlay(
        onDismiss = actions.onDismiss,
        title = stringResource(
            backupImportOverlayTitleRes(state.phase, state.importResult, state.isCancellingImport),
        ),
    ) { innerPadding ->
        Column(Modifier.preferenceHubOverlayFill(innerPadding)) {
            Column(preferenceHubFormScroll()) {
                BackupImportSheetBody(
                    phase = state.phase,
                    preview = state.preview,
                    importResult = state.importResult,
                    failure = state.failure,
                    isCancellingImport = state.isCancellingImport,
                )
            }
            BackupImportPhaseActions(state = state, actions = actions)
        }
    }
}

@Composable
private fun BackupImportPhaseActions(state: BackupImportOverlayState, actions: BackupImportOverlayActions) {
    when (state.phase) {
        BackupImportSheetPhase.PREVIEW -> {
            val previewForCommit = state.preview
            if (previewForCommit != null) {
                Button(
                    onClick = { launchBackupImport(previewForCommit, state, actions) },
                    enabled = backupImportCommitEnabled(
                        manifestValid = previewForCommit.manifestValid,
                        isRadioConnected = state.isRadioConnected,
                        isExporting = state.isExporting,
                        isParsing = state.isParsing,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(Res.string.settings_backup_import_preview_button))
                }
            }
        }
        BackupImportSheetPhase.IMPORTING -> {
            OutlinedButton(
                onClick = { requestBackupImportCancel(state.isCancellingImport, actions.onCancel) },
                enabled = !state.isCancellingImport,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.settings_backup_import_preview_cancel))
            }
        }
        BackupImportSheetPhase.SUCCESS, BackupImportSheetPhase.CANCELLED -> {
            Button(
                onClick = actions.onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.localizable_common_ok))
            }
        }
        BackupImportSheetPhase.FAILED -> {
            Button(
                onClick = actions.onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.settings_backup_import_error_dismiss))
            }
        }
        BackupImportSheetPhase.NONE -> Unit
    }
}

@Suppress("TooGenericExceptionCaught")
internal fun launchBackupImport(
    preview: BackupImportPreview,
    state: BackupImportOverlayState,
    actions: BackupImportOverlayActions,
) {
    if (!canCommitBackupImport(preview.manifestValid, state.isRadioConnected)) {
        actions.onSkip()
        return
    }
    actions.onStart()
    val job = actions.commitScope.launch {
        try {
            val result = actions.onImportConfirmed(state.importJson)
            actions.onImported(result)
        } catch (cancelled: CancellationException) {
            actions.onCancelled()
            throw cancelled
        } catch (error: Exception) {
            actions.onFailed(error)
        } finally {
            actions.onFinished()
        }
    }
    actions.onJob(job)
}

internal fun requestBackupImportCancel(isCancelling: Boolean, cancel: () -> Unit) {
    if (!isCancelling) cancel()
}

internal fun backupImportCommitEnabled(
    manifestValid: Boolean,
    isRadioConnected: Boolean,
    isExporting: Boolean,
    isParsing: Boolean,
): Boolean = canCommitBackupImport(manifestValid, isRadioConnected) && !isExporting && !isParsing

@Composable
internal fun BackupFileRows(state: BackupFileRowState, actions: BackupFileRowActions) {
    PreferenceHubSection(
        title = stringResource(Res.string.settings_backup_file_backup_header),
        applyHorizontalPadding = false,
    ) {
        if (state.isExporting) {
            ListItem(
                headlineContent = {
                    Text(stringResource(Res.string.settings_backup_export_progress))
                },
                trailingContent = {
                    CircularProgressIndicator(
                        modifier = Modifier.size(BACKUP_PROGRESS_SIZE.dp),
                        strokeWidth = 2.dp,
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        } else {
            ListItem(
                headlineContent = { Text(stringResource(Res.string.settings_backup_export_title)) },
                supportingContent = {
                    Text(stringResource(Res.string.settings_backup_export_subtitle))
                },
                leadingContent = {
                    Icon(imageVector = AppIcons.Share, contentDescription = null)
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !state.isBusy) { actions.onExportClick() },
            )
        }
        if (state.offersFileImport) {
            val importEnabled = !state.isRadioConnected && !state.isBusy
            ListItem(
                headlineContent = {
                    Text(
                        if (state.isParsing) {
                            stringResource(Res.string.settings_backup_import_parsing)
                        } else {
                            stringResource(Res.string.settings_backup_import_title)
                        },
                    )
                },
                supportingContent = {
                    Text(
                        if (state.isRadioConnected) {
                            stringResource(Res.string.settings_backup_import_disabled_when_connected)
                        } else {
                            stringResource(Res.string.settings_backup_import_subtitle)
                        },
                    )
                },
                leadingContent = {
                    Icon(imageVector = AppIcons.CloudDownload, contentDescription = null)
                },
                trailingContent = if (state.isParsing) {
                    {
                        CircularProgressIndicator(
                            modifier = Modifier.size(BACKUP_PROGRESS_SIZE.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                } else {
                    null
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = importEnabled) { actions.onBeginParse() },
            )
        }
    }
}

@Composable
internal fun BackupExportConfirmDialog(
    visible: Boolean,
    isBusy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    if (!visible) return
    ConfirmAlertDialog(
        title = stringResource(Res.string.settings_backup_export_alert_title),
        message = stringResource(Res.string.settings_backup_export_alert_message),
        confirmLabel = stringResource(Res.string.settings_backup_export_alert_export),
        dismissLabel = stringResource(Res.string.settings_backup_export_alert_cancel),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        confirmEnabled = !isBusy,
    )
}

@Composable
internal fun BackupFailureDialog(failure: BackupFailure?, preview: BackupImportPreview?, onDismiss: () -> Unit) {
    val shown = backupFailureForDialog(failure, preview) ?: return
    ThreadErrorDialog(message = backupErrorText(shown), onDismiss = onDismiss)
}
