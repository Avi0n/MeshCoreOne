package one.meshcore.shared.settings.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import one.meshcore.shared.backup.BackupExportSuccessSummary
import one.meshcore.shared.backup.BackupImportPreview
import one.meshcore.shared.backup.BackupImportResult
import one.meshcore.shared.backup.previewPickedBackupJson

/**
 * Backup sheet state retained by its workspace. Export and parse use the same scope as confirmed
 * imports, so a remounted destination can observe completion and cancel pending work.
 */
internal class BackupSettingsState {
    var showExportNotice by mutableStateOf(false)
    var importJson by mutableStateOf("")
    var preview by mutableStateOf<BackupImportPreview?>(null)
    var failure by mutableStateOf<BackupFailure?>(null)
    var isExporting by mutableStateOf(false)
    var isParsing by mutableStateOf(false)
    var importResult by mutableStateOf<BackupImportResult?>(null)
    var exportSummary by mutableStateOf<BackupExportSuccessSummary?>(null)
    var importJob by mutableStateOf<Job?>(null)
    var isImporting by mutableStateOf(false)
    var isCancellingImport by mutableStateOf(false)
    var importCancelled by mutableStateOf(false)
}

internal fun BackupSettingsState.clearImportOverlay() {
    preview = null
    importResult = null
    importCancelled = false
    importJson = ""
    if (failure?.isExport == false) failure = null
}

internal fun BackupSettingsState.dismissImportSheet() {
    if (isImporting) {
        if (!isCancellingImport) cancelImport()
        return
    }
    clearImportOverlay()
}

@Suppress("TooGenericExceptionCaught")
internal fun BackupSettingsState.beginExport(
    scope: CoroutineScope,
    onExportConfirmed: suspend () -> BackupExportSuccessSummary?,
    onExported: () -> Unit,
) {
    if (backupSettingsBusy(isExporting, isImporting, isParsing)) return
    isExporting = true
    scope.launch {
        try {
            val summary = onExportConfirmed()
            // A null return is a cancelled save picker, matching iOS
            // handleExportResult(.failure(userCancelled)): stay idle.
            if (summary != null) {
                exportSummary = summary
                onExported()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failure = BackupFailure(error, isExport = true)
        } finally {
            isExporting = false
        }
    }
}

@Suppress("TooGenericExceptionCaught")
internal fun BackupSettingsState.beginParse(pick: (suspend () -> String?)?, scope: CoroutineScope) {
    if (backupSettingsBusy(isExporting, isImporting, isParsing)) return
    val choose = pick ?: return
    failure = null
    preview = null
    isParsing = true
    scope.launch {
        try {
            val json = choose.invoke()
            if (json != null) {
                importJson = json
                preview = previewPickedBackupJson(json)
                importResult = null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failure = BackupFailure(error)
            preview = null
        } finally {
            isParsing = false
        }
    }
}

/** Cancel control on the importing overlay. The button already ignores a second press. */
internal fun BackupSettingsState.cancelImport() {
    isCancellingImport = true
    importJob?.cancel()
}

/** Success haptic fires only when the import wrote or refreshed something. */
internal fun backupImportRestored(result: BackupImportResult?): Boolean = result?.hasRestoredChanges == true
