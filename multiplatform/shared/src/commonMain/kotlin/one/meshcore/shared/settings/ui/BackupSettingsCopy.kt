package one.meshcore.shared.settings.ui

import one.meshcore.shared.backup.AppBackupError
import one.meshcore.shared.backup.BackupExportSuccessSummary
import one.meshcore.shared.backup.BackupImportPreview
import one.meshcore.shared.backup.BackupImportResult
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.settings_backup_error_corrupted_manifest
import one.meshcore.shared.resources.settings_backup_error_decompressed_too_large
import one.meshcore.shared.resources.settings_backup_error_export_failed
import one.meshcore.shared.resources.settings_backup_error_file_too_large
import one.meshcore.shared.resources.settings_backup_error_import_failed
import one.meshcore.shared.resources.settings_backup_error_invalid_file
import one.meshcore.shared.resources.settings_backup_error_unsupported_version
import one.meshcore.shared.resources.settings_backup_import_cancelled_title
import one.meshcore.shared.resources.settings_backup_import_cancelling
import one.meshcore.shared.resources.settings_backup_import_disabled_when_connected
import one.meshcore.shared.resources.settings_backup_import_error_title
import one.meshcore.shared.resources.settings_backup_import_nothing_to_import_title
import one.meshcore.shared.resources.settings_backup_import_preview_title
import one.meshcore.shared.resources.settings_backup_import_progress
import one.meshcore.shared.resources.settings_backup_import_success_title
import org.jetbrains.compose.resources.StringResource

/** Divisor turning the byte-valued size limits into the megabyte figures the copy quotes. */
private const val BYTES_PER_MEGABYTE = 1_048_576

/**
 * Maps a backup failure onto its `settings_backup_error_*` string, mirroring the Swift
 * `AppBackupError.errorDescription` cases. Anything the gates did not raise falls through to the
 * export/import wrapper carrying the underlying text, matching Swift `.exportFailed`/`.importFailed`.
 */
internal fun backupErrorCopy(failure: BackupFailure): BackupErrorCopy = when (val error = failure.cause) {
    is AppBackupError.InvalidFile -> BackupErrorCopy(Res.string.settings_backup_error_invalid_file)
    is AppBackupError.FileTooLarge -> BackupErrorCopy(
        Res.string.settings_backup_error_file_too_large,
        listOf(error.actualBytes / BYTES_PER_MEGABYTE, error.maxBytes / BYTES_PER_MEGABYTE),
    )
    is AppBackupError.DecompressedTooLarge -> BackupErrorCopy(
        Res.string.settings_backup_error_decompressed_too_large,
        listOf(error.maxBytes / BYTES_PER_MEGABYTE),
    )
    is AppBackupError.UnsupportedVersion -> BackupErrorCopy(
        Res.string.settings_backup_error_unsupported_version,
        listOf(error.found, error.maxSupported),
    )
    is AppBackupError.CorruptedManifest -> BackupErrorCopy(Res.string.settings_backup_error_corrupted_manifest)
    is AppBackupError.RadioConnected ->
        BackupErrorCopy(Res.string.settings_backup_import_disabled_when_connected)
    else -> error.message?.let { detail ->
        BackupErrorCopy(
            if (failure.isExport) {
                Res.string.settings_backup_error_export_failed
            } else {
                Res.string.settings_backup_error_import_failed
            },
            listOf(detail),
        )
    } ?: BackupErrorCopy(Res.string.settings_backup_import_error_title)
}

/**
 * iOS `ImportPreviewSheet` errorView always shows Import Failed as the headline. The body is a
 * separate detail unless the gate had nothing to say, in which case the title is the whole message.
 */
internal fun backupImportFailureShowsSeparateDetail(failure: BackupFailure): Boolean =
    backupErrorCopy(failure).resource != Res.string.settings_backup_import_error_title

internal fun backupImportSheetPhase(
    isImporting: Boolean,
    preview: BackupImportPreview?,
    importResult: BackupImportResult?,
    importCancelled: Boolean,
    failure: BackupFailure?,
): BackupImportSheetPhase = when {
    isImporting -> BackupImportSheetPhase.IMPORTING
    importResult != null -> BackupImportSheetPhase.SUCCESS
    importCancelled -> BackupImportSheetPhase.CANCELLED
    failure != null && !failure.isExport && preview != null -> BackupImportSheetPhase.FAILED
    preview != null -> BackupImportSheetPhase.PREVIEW
    else -> BackupImportSheetPhase.NONE
}

internal fun backupImportOverlayTitleRes(
    phase: BackupImportSheetPhase,
    importResult: BackupImportResult? = null,
    isCancellingImport: Boolean = false,
) = when (phase) {
    BackupImportSheetPhase.IMPORTING -> if (isCancellingImport) {
        Res.string.settings_backup_import_cancelling
    } else {
        Res.string.settings_backup_import_progress
    }
    BackupImportSheetPhase.SUCCESS -> if (importResult?.hasRestoredChanges == false) {
        Res.string.settings_backup_import_nothing_to_import_title
    } else {
        Res.string.settings_backup_import_success_title
    }
    BackupImportSheetPhase.CANCELLED -> Res.string.settings_backup_import_cancelled_title
    BackupImportSheetPhase.FAILED -> Res.string.settings_backup_import_error_title
    BackupImportSheetPhase.PREVIEW, BackupImportSheetPhase.NONE ->
        Res.string.settings_backup_import_preview_title
}

/** iOS `ImportPreviewSheet.importingView` VoiceOver announcement. */
internal fun backupImportStartedAnnouncementRes(): StringResource = Res.string.settings_backup_import_progress

/** iOS `ImportPreviewSheet.cancelledView` VoiceOver announcement. */
internal fun backupImportCancelledAnnouncementRes(): StringResource = Res.string.settings_backup_import_cancelled_title

internal fun backupSettingsBusy(isExporting: Boolean, isImporting: Boolean, isParsing: Boolean): Boolean =
    isExporting || isImporting || isParsing

internal fun showBackupExportSuccess(summary: BackupExportSuccessSummary?): Boolean = summary != null

internal fun showBackupImportOverlay(phase: BackupImportSheetPhase): Boolean = phase != BackupImportSheetPhase.NONE

internal fun backupFailureForDialog(failure: BackupFailure?, preview: BackupImportPreview?): BackupFailure? =
    failure?.takeIf { it.isExport || preview == null }
