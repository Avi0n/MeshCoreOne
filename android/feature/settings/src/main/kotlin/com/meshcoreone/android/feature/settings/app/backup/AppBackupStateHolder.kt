// PortedFrom: MC1/Views/Settings/AppBackupViewModel.swift@db14559b39d32322b06477c6ae676112f583db50
// Native adaptation: the system file picker (SAF) sits behind BackupDocumentPort; state is one immutable value published through StateFlow.
package com.meshcoreone.android.feature.settings.app.backup

import com.meshcoreone.android.core.model.BackupContract
import java.time.Clock
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Import flow: one enum collapses the overlapping booleans so invalid combinations are unrepresentable. */
sealed interface ImportState {
    data object Idle : ImportState
    data object Parsing : ImportState
    data class Preview(val backup: ParsedBackup) : ImportState
    data object Importing : ImportState
    data class Success(val result: ImportResult) : ImportState
    /** Import-phase failure surfaced inside the sheet (not the top-level alert). */
    data class Failed(val message: String) : ImportState
    data object Cancelled : ImportState
}

class PendingExport(val data: ByteArray, val manifest: BackupManifest)

class ExportSuccessSummary(val id: UUID, val filename: String, val byteCount: Int, val manifest: BackupManifest)

sealed interface ExportState {
    data object Idle : ExportState
    data object Exporting : ExportState
    data class Pending(val pending: PendingExport) : ExportState
    data class Success(val summary: ExportSuccessSummary) : ExportState
}

data class AppBackupState(
    val export: ExportState = ExportState.Idle,
    val import: ImportState = ImportState.Idle,
    /** Top-level alert text (reserved for when no sheet is active). */
    val errorMessage: String? = null,
    /** Latched the instant Cancel is tapped so the UI can acknowledge before the job unwinds. */
    val isCancellingImport: Boolean = false,
) {
    val isExporting: Boolean get() = export is ExportState.Exporting
    val isParsing: Boolean get() = import is ImportState.Parsing
    val isImporting: Boolean get() = import is ImportState.Importing
    val isBusy: Boolean get() = isExporting || isParsing || isImporting || export is ExportState.Pending

    /** `.parsing` is pre-sheet: the spinner in the import row is the only cue until a preview is ready. */
    val isImportSheetActive: Boolean get() = when (import) {
        ImportState.Idle, ImportState.Parsing -> false
        else -> true
    }
    val exportSummary: ExportSuccessSummary? get() = (export as? ExportState.Success)?.summary
}

class AppBackupStateHolder(
    private val dependencies: BackupFeatureDependencies,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val mutableState = MutableStateFlow(AppBackupState())
    val state: StateFlow<AppBackupState> = mutableState

    private var parseJob: Job? = null
    private var currentParseId: UUID? = null
    private var importJob: Job? = null

    private val strings get() = dependencies.strings

    // region Export

    /** Builds the backup then hands it to the save picker; the result resolves through [handleSaveOutcome]. */
    fun performExport(): Job? {
        if (mutableState.value.export != ExportState.Idle) return null
        mutableState.update { it.copy(export = ExportState.Exporting, errorMessage = null) }
        return scope.launch {
            try {
                val built = dependencies.engine.export()
                mutableState.update { it.copy(export = ExportState.Pending(PendingExport(built.data, built.manifest))) }
                handleSaveOutcome(dependencies.documents.save(defaultExportFilename(), built.data))
            } catch (cancelled: CancellationException) {
                mutableState.update { it.copy(export = ExportState.Idle) }
                throw cancelled
            } catch (failure: Exception) {
                handleExportFailure(failure)
            }
        }
    }

    /** Idempotent: a result with no pending export is ignored. */
    fun handleSaveOutcome(outcome: BackupSaveOutcome) {
        val pending = (mutableState.value.export as? ExportState.Pending)?.pending ?: return
        when (outcome) {
            is BackupSaveOutcome.Saved -> mutableState.update {
                it.copy(export = ExportState.Success(ExportSuccessSummary(UUID.randomUUID(), outcome.displayName, pending.data.size, pending.manifest)))
            }
            BackupSaveOutcome.Cancelled -> mutableState.update { it.copy(export = ExportState.Idle) }
        }
    }

    fun handleExportFailure(failure: Throwable) {
        dependencies.diagnostics.report("Export failed", failure)
        mutableState.update { it.copy(export = ExportState.Idle, errorMessage = backupUserFacingMessage(failure, strings)) }
    }

    fun dismissExportSuccess() {
        if (mutableState.value.export !is ExportState.Success) return
        mutableState.update { it.copy(export = ExportState.Idle) }
    }

    // endregion

    // region Import

    /** Opens the system picker; a dismissed picker is not an error. */
    fun selectFileToImport(): Job = scope.launch {
        try {
            when (val outcome = dependencies.documents.pick()) {
                BackupPickOutcome.Cancelled -> Unit
                is BackupPickOutcome.Picked -> loadAndParse(outcome.source)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            mutableState.update { it.copy(errorMessage = backupUserFacingMessage(failure, strings)) }
        }
    }

    fun loadAndParse(source: BackupSource): Job {
        parseJob?.cancel()
        val taskId = UUID.randomUUID()
        currentParseId = taskId
        mutableState.update { it.copy(import = ImportState.Parsing) }
        return scope.launch {
            try {
                source.sizeBytes?.let(BackupContract::validateCompressedSize)
                val parsed = withContext(io) {
                    val bytes = source.readBytes()
                    BackupContract.validateCompressedSize(bytes.size.toLong())
                    dependencies.engine.parse(bytes)
                }
                applyParseSuccess(parsed, taskId)
            } catch (cancelled: CancellationException) {
                // A newer invocation owns the state; do not clobber it.
                throw cancelled
            } catch (failure: Exception) {
                applyParseFailure(failure, taskId)
            }
        }.also { parseJob = it }
    }

    private fun applyParseSuccess(parsed: ParsedBackup, taskId: UUID) {
        if (currentParseId != taskId) return
        mutableState.update { it.copy(import = ImportState.Preview(parsed), errorMessage = null) }
    }

    /** Parse failures happen before the sheet opens, so they use the top-level alert. */
    private fun applyParseFailure(failure: Throwable, taskId: UUID) {
        if (currentParseId != taskId) return
        dependencies.diagnostics.report("Backup parse failed", failure)
        mutableState.update { it.copy(import = ImportState.Idle, errorMessage = backupUserFacingMessage(failure, strings)) }
    }

    fun performImport(): Job? {
        val preview = mutableState.value.import as? ImportState.Preview ?: return null
        // Re-check at the commit point: a foreground auto-reconnect can connect the radio while the preview is read.
        if (dependencies.connection.isRadioConnected.value) {
            dismissImportSheet()
            return null
        }
        mutableState.update { it.copy(isCancellingImport = false, import = ImportState.Importing) }
        return scope.launch {
            var importCompleted = false
            try {
                val result = dependencies.engine.importBackup(preview.backup)
                importCompleted = true
                mutableState.update { it.copy(import = ImportState.Success(result)) }
                applyRestoreEffects(result)
            } catch (cancelled: CancellationException) {
                // Later UI effects cannot turn a committed restore into a rollback-shaped outcome.
                if (!importCompleted) mutableState.update { it.copy(import = ImportState.Cancelled) }
                throw cancelled
            } catch (failure: Exception) {
                dependencies.diagnostics.report("Import failed", failure)
                mutableState.update { it.copy(import = ImportState.Failed(backupUserFacingMessage(failure, strings))) }
            }
        }.also { job ->
            importJob = job
            job.invokeOnCompletion { if (importJob === job) importJob = null }
        }
    }

    private suspend fun applyRestoreEffects(result: ImportResult) {
        val effects = dependencies.effects
        if (result.channelSlotsAffectedByImport.isNotEmpty()) effects.channelSlotsAffected(result.channelSlotsAffectedByImport)
        if (!result.hasRestoredChanges) return
        effects.restoredDataChanged()
        try {
            effects.refreshBlockedContactsCache()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            dependencies.diagnostics.report("Blocked-contact cache refresh failed after restore", failure)
        }
    }

    fun cancelImport() {
        if (mutableState.value.isCancellingImport) return
        mutableState.update { it.copy(isCancellingImport = true) }
        importJob?.cancel()
    }

    fun dismissImportSheet() {
        parseJob?.cancel()
        parseJob = null
        currentParseId = null
        mutableState.update { it.copy(import = ImportState.Idle, isCancellingImport = false, errorMessage = null) }
    }

    fun dismissError() = mutableState.update { it.copy(errorMessage = null) }

    // endregion

    /** `MC1 Backup <yyyy-MM-dd HHmmss>.mc1backup`, timestamp in UTC like the ISO-8601 format style it mirrors. */
    fun defaultExportFilename(): String = strings.defaultExportFilename(TIMESTAMP.format(clock.instant().atZone(ZoneOffset.UTC)))

    private companion object {
        val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HHmmss")
    }
}
