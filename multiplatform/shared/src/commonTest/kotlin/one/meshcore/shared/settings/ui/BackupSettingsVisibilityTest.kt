package one.meshcore.shared.settings.ui

import one.meshcore.shared.backup.BackupExportSuccessSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackupSettingsVisibilityTest {
    @Test
    fun busyWhenAnyBackupStepIsLive() {
        assertFalse(backupSettingsBusy(isExporting = false, isImporting = false, isParsing = false))
        assertTrue(backupSettingsBusy(isExporting = true, isImporting = false, isParsing = false))
        assertTrue(backupSettingsBusy(isExporting = false, isImporting = true, isParsing = false))
        assertTrue(backupSettingsBusy(isExporting = false, isImporting = false, isParsing = true))
    }

    @Test
    fun exportSuccessOverlayNeedsASummary() {
        assertFalse(showBackupExportSuccess(null))
        assertTrue(
            showBackupExportSuccess(BackupExportSuccessSummary("radio.mc1backup", 12, samplePreview())),
        )
    }

    @Test
    fun importOverlayHidesUntilAPhaseOpens() {
        assertFalse(showBackupImportOverlay(BackupImportSheetPhase.NONE))
        assertTrue(showBackupImportOverlay(BackupImportSheetPhase.PREVIEW))
        assertTrue(showBackupImportOverlay(BackupImportSheetPhase.FAILED))
    }

    @Test
    fun hubDialogSkipsImportFailuresThatAlreadyHaveAPreview() {
        val exportFailure = BackupFailure(IllegalStateException("disk"), isExport = true)
        val importFailure = BackupFailure(IllegalStateException("bad"))
        assertNull(backupFailureForDialog(null, null))
        assertEquals(exportFailure, backupFailureForDialog(exportFailure, samplePreview()))
        assertEquals(importFailure, backupFailureForDialog(importFailure, null))
        assertNull(backupFailureForDialog(importFailure, samplePreview()))
    }
}
