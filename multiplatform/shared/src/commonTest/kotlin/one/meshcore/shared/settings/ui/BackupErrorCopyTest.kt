package one.meshcore.shared.settings.ui

import one.meshcore.shared.backup.AppBackupError
import one.meshcore.shared.backup.MAX_BACKUP_COMPRESSED_BYTES
import one.meshcore.shared.backup.MAX_BACKUP_UNCOMPRESSED_BYTES
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.settings_backup_error_corrupted_manifest
import one.meshcore.shared.resources.settings_backup_error_decompressed_too_large
import one.meshcore.shared.resources.settings_backup_error_export_failed
import one.meshcore.shared.resources.settings_backup_error_file_too_large
import one.meshcore.shared.resources.settings_backup_error_import_failed
import one.meshcore.shared.resources.settings_backup_error_invalid_file
import one.meshcore.shared.resources.settings_backup_error_unsupported_version
import one.meshcore.shared.resources.settings_backup_import_error_title
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every backup gate failure must reach the user as its translated `settings_backup_error_*` string,
 * not the developer English carried on the exception.
 */
class BackupErrorCopyTest {
    private val tenMegabytes = 10 * 1_048_576

    @Test
    fun eachGateFailureMapsToItsLocalizedString() {
        assertEquals(
            Res.string.settings_backup_error_invalid_file,
            backupErrorCopy(BackupFailure(AppBackupError.InvalidFile("bad json"))).resource,
            "InvalidFile must render settings_backup_error_invalid_file",
        )
        assertEquals(
            Res.string.settings_backup_error_corrupted_manifest,
            backupErrorCopy(BackupFailure(AppBackupError.CorruptedManifest())).resource,
            "CorruptedManifest must render settings_backup_error_corrupted_manifest",
        )
    }

    @Test
    fun theFileSizeLimitIsQuotedInMegabytes() {
        val oversized = MAX_BACKUP_COMPRESSED_BYTES + tenMegabytes
        val copy = backupErrorCopy(BackupFailure(AppBackupError.FileTooLarge(oversized, MAX_BACKUP_COMPRESSED_BYTES)))
        assertEquals(
            Res.string.settings_backup_error_file_too_large,
            copy.resource,
            "FileTooLarge must render settings_backup_error_file_too_large",
        )
        assertEquals(listOf(60, 50), copy.args)
    }

    @Test
    fun theUncompressedLimitIsQuotedInMegabytes() {
        val copy = backupErrorCopy(
            BackupFailure(AppBackupError.DecompressedTooLarge(MAX_BACKUP_UNCOMPRESSED_BYTES)),
        )
        assertEquals(
            Res.string.settings_backup_error_decompressed_too_large,
            copy.resource,
            "DecompressedTooLarge must render settings_backup_error_decompressed_too_large",
        )
        assertEquals(listOf(512), copy.args)
    }

    @Test
    fun theVersionGateQuotesBothVersions() {
        val copy = backupErrorCopy(BackupFailure(AppBackupError.UnsupportedVersion(found = 4, maxSupported = 2)))
        assertEquals(
            Res.string.settings_backup_error_unsupported_version,
            copy.resource,
            "UnsupportedVersion must render settings_backup_error_unsupported_version",
        )
        assertEquals(listOf(4, 2), copy.args)
    }

    @Test
    fun anUngatedFailureWrapsItsDetailInTheExportOrImportString() {
        val exportCopy = backupErrorCopy(BackupFailure(IllegalStateException("disk full"), isExport = true))
        assertEquals(Res.string.settings_backup_error_export_failed, exportCopy.resource)
        assertEquals(listOf("disk full"), exportCopy.args)

        val importCopy = backupErrorCopy(BackupFailure(IllegalStateException("disk full")))
        assertEquals(Res.string.settings_backup_error_import_failed, importCopy.resource)
        assertEquals(listOf("disk full"), importCopy.args)
    }

    @Test
    fun aFailureWithNoDetailFallsBackToTheGenericTitle() {
        val copy = backupErrorCopy(BackupFailure(IllegalStateException()))
        assertEquals(Res.string.settings_backup_import_error_title, copy.resource)
        assertEquals(emptyList<Any>(), copy.args)
    }

    @Test
    fun importFailedHeadlineIsNotRepeatedWhenTheGateHadNoDetail() {
        assertEquals(
            false,
            backupImportFailureShowsSeparateDetail(BackupFailure(IllegalStateException())),
        )
        assertEquals(
            true,
            backupImportFailureShowsSeparateDetail(BackupFailure(AppBackupError.InvalidFile("bad json"))),
        )
    }
}
