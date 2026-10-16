package one.meshcore.shared.backup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BackupExportSuccessTest {
    @Test
    fun byteCountUsesTheCompressedPayload() {
        val json = loadBackupFixture("full-envelope.json")
        val compressed = ByteArray(COMPRESSED_SIZE)
        val summary = backupExportSuccessSummary("MC1-backup-2026-08-14.mc1backup", compressed, json)

        assertEquals("MC1-backup-2026-08-14.mc1backup", summary.filename)
        assertEquals(COMPRESSED_SIZE, summary.byteCount)
        assertEquals(1, summary.preview.deviceCount)
        assertEquals(1, summary.preview.contactCount)
    }

    @Test
    fun suggestedNameUsesTheLocalizedTemplateAndPaddedStamp() {
        val stamp = backupExportTimestamp(BackupExportClock(2026, 8, 14, 9, 30, 5))
        assertEquals("2026-08-14 093005", stamp)
        assertEquals(
            "MC1 Backup 2026-08-14 093005.mc1backup",
            suggestedBackupFilename(stamp),
        )
    }

    @Test
    fun withBackupFileExtensionAddsTheSuffixOnce() {
        assertEquals("notes.mc1backup", withBackupFileExtension("notes"))
        assertEquals("notes.mc1backup", withBackupFileExtension("notes.mc1backup"))
        assertEquals("NOTES.MC1BACKUP", withBackupFileExtension("NOTES.MC1BACKUP"))
        assertEquals("MC1-backup.mc1backup", withBackupFileExtension("  "))
    }

    @Test
    fun fileSizeStepsAt1024() {
        assertEquals("0 B", formatBackupByteCount(0))
        assertEquals("512 B", formatBackupByteCount(512))
        assertEquals("1.0 KB", formatBackupByteCount(1_024))
        assertEquals("1.5 KB", formatBackupByteCount(1_536))
        assertEquals("1.0 MB", formatBackupByteCount(1_048_576))
        assertTrue(formatBackupByteCount(2_097_152).endsWith(" MB"))
    }

    private companion object {
        const val COMPRESSED_SIZE = 240
    }
}
