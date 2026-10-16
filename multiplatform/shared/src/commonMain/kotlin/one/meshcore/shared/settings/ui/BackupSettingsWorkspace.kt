package one.meshcore.shared.settings.ui

import kotlinx.coroutines.CoroutineScope
import one.meshcore.shared.backup.BackupExportSuccessSummary

/** Keeps pending backup work and its result reachable when the destination is remounted. */
class BackupSettingsWorkspace(internal val scope: CoroutineScope) {
    internal val state = BackupSettingsState()

    fun recoverPickedBackup(readSelected: suspend () -> String): Boolean {
        if (!canRecoverDocument()) return false
        state.beginParse(readSelected, scope)
        return true
    }

    fun recoverCreatedBackup(exportSelected: suspend () -> BackupExportSuccessSummary?): Boolean {
        if (!canRecoverDocument()) return false
        state.beginExport(scope, exportSelected) {}
        return true
    }

    private fun canRecoverDocument(): Boolean {
        if (backupSettingsBusy(state.isExporting, state.isImporting, state.isParsing)) return false
        if (state.showExportNotice || state.exportSummary != null || state.failure != null) return false
        if (state.preview != null || state.importResult != null || state.importCancelled) return false
        return true
    }
}
