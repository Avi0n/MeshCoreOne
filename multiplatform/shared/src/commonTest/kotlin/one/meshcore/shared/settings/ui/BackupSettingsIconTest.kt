package one.meshcore.shared.settings.ui

import one.meshcore.shared.backup.BackupImportResult
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.settings_backup_import_cancelling
import one.meshcore.shared.resources.settings_backup_import_nothing_to_import_title
import one.meshcore.shared.resources.settings_backup_import_preview_title
import one.meshcore.shared.resources.settings_backup_import_progress
import one.meshcore.shared.resources.settings_backup_import_success_title
import kotlin.test.Test
import kotlin.test.assertEquals

class BackupSettingsIconTest {

    @Test
    fun importOverlayTitleFollowsPhase() {
        assertEquals(
            Res.string.settings_backup_import_progress,
            backupImportOverlayTitleRes(BackupImportSheetPhase.IMPORTING),
        )
        assertEquals(
            Res.string.settings_backup_import_cancelling,
            backupImportOverlayTitleRes(
                BackupImportSheetPhase.IMPORTING,
                isCancellingImport = true,
            ),
        )
        assertEquals(
            Res.string.settings_backup_import_success_title,
            backupImportOverlayTitleRes(BackupImportSheetPhase.SUCCESS),
        )
        assertEquals(
            Res.string.settings_backup_import_nothing_to_import_title,
            backupImportOverlayTitleRes(
                BackupImportSheetPhase.SUCCESS,
                BackupImportResult(),
            ),
        )
        assertEquals(
            Res.string.settings_backup_import_preview_title,
            backupImportOverlayTitleRes(BackupImportSheetPhase.PREVIEW),
        )
    }

    @Test
    fun importSheetStaysHiddenOnTheHub() {
        assertEquals(
            BackupImportSheetPhase.NONE,
            backupImportSheetPhase(
                isImporting = false,
                preview = null,
                importResult = null,
                importCancelled = false,
                failure = null,
            ),
        )
    }

    @Test
    fun importingTakesPriorityOverPreview() {
        assertEquals(
            BackupImportSheetPhase.IMPORTING,
            backupImportSheetPhase(
                isImporting = true,
                preview = samplePreview(),
                importResult = null,
                importCancelled = false,
                failure = null,
            ),
        )
    }

    @Test
    fun importFailureTakesPriorityOverPreview() {
        assertEquals(
            BackupImportSheetPhase.FAILED,
            backupImportSheetPhase(
                isImporting = false,
                preview = samplePreview(),
                importResult = null,
                importCancelled = false,
                failure = BackupFailure(RuntimeException("decode")),
            ),
        )
    }

    @Test
    fun aPickedFileThatCannotBeParsedDoesNotOpenTheImportSheet() {
        assertEquals(
            BackupImportSheetPhase.NONE,
            backupImportSheetPhase(
                isImporting = false,
                preview = null,
                importResult = null,
                importCancelled = false,
                failure = BackupFailure(RuntimeException("decode")),
            ),
        )
    }

    @Test
    fun exportFailureDoesNotOpenTheImportSheet() {
        assertEquals(
            BackupImportSheetPhase.NONE,
            backupImportSheetPhase(
                isImporting = false,
                preview = null,
                importResult = null,
                importCancelled = false,
                failure = BackupFailure(RuntimeException("write"), isExport = true),
            ),
        )
    }

    @Test
    fun cancelledImportStaysOnTheSheet() {
        assertEquals(
            BackupImportSheetPhase.CANCELLED,
            backupImportSheetPhase(
                isImporting = false,
                preview = null,
                importResult = null,
                importCancelled = true,
                failure = null,
            ),
        )
    }

    @Test
    fun previewOpensTheImportSheet() {
        assertEquals(
            BackupImportSheetPhase.PREVIEW,
            backupImportSheetPhase(
                isImporting = false,
                preview = samplePreview(),
                importResult = null,
                importCancelled = false,
                failure = null,
            ),
        )
    }
}
