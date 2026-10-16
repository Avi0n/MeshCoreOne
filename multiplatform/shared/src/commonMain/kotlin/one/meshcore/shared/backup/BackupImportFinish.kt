package one.meshcore.shared.backup

import kotlinx.coroutines.CancellationException

/**
 * Preference restore after [RoomBackupImporter.importEnvelope] has already committed.
 *
 * Cancellation past that point is ignored: the database has the restore, and preference restore is
 * write-if-missing so finishing (or skipping) it is safer than reporting cancelled while Room has
 * the data. Mirrors Swift `AppBackupService.importBackup` after `importBackupDatabase`.
 */
suspend fun restorePrefsAfterCommittedImport(restore: suspend () -> Boolean): Boolean = try {
    restore()
} catch (_: CancellationException) {
    false
} catch (_: Throwable) {
    false
}

/**
 * Radio-scope adopt after a committed import. Cancellation must not escape: the caller still returns
 * [BackupImportResult] so the UI cannot claim nothing changed.
 */
suspend fun adoptRadioAfterCommittedImport(adopt: suspend () -> Unit) {
    try {
        adopt()
    } catch (_: CancellationException) {
        // ignored
    } catch (_: Throwable) {
        // ignored: adopt implementations already swallow non-cancel faults
    }
}
