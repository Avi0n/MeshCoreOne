package one.meshcore.shared.settings.ui

import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.settings_backup_import_cancelled_title
import one.meshcore.shared.resources.settings_backup_import_progress
import kotlin.test.Test
import kotlin.test.assertEquals

/** iOS `ImportPreviewSheet` announces import start and cancel. */
class BackupImportAnnounceTest {
    @Test
    fun importStartAndCancelSpeakTheIosAnnouncements() {
        assertEquals(
            Res.string.settings_backup_import_progress,
            backupImportStartedAnnouncementRes(),
        )
        assertEquals(
            Res.string.settings_backup_import_cancelled_title,
            backupImportCancelledAnnouncementRes(),
        )
    }
}
