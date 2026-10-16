package one.meshcore.shared.settings.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.BackupExportSuccessSummary
import one.meshcore.shared.backup.BackupImportResult
import one.meshcore.shared.backup.loadBackupFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackupSettingsSessionTest {
    @Test
    fun restoredImportIsTheOnlySuccessHaptic() {
        assertFalse(backupImportRestored(null))
        assertFalse(backupImportRestored(BackupImportResult()))
        assertTrue(backupImportRestored(BackupImportResult(devicesInserted = 1)))
        assertTrue(backupImportRestored(BackupImportResult(userDefaultsRestored = true)))
    }

    @Test
    fun clearImportOverlayKeepsAnExportFailure() {
        val state = BackupSettingsState()
        state.preview = samplePreview()
        state.importJson = "{}"
        state.importCancelled = true
        state.importResult = BackupImportResult(devicesInserted = 1)
        state.failure = BackupFailure(IllegalStateException("disk"), isExport = true)
        state.clearImportOverlay()
        assertNull(state.preview)
        assertEquals("", state.importJson)
        assertFalse(state.importCancelled)
        assertNull(state.importResult)
        assertTrue(state.failure?.isExport == true)
    }

    @Test
    fun clearImportOverlayDropsAnImportFailure() {
        val state = BackupSettingsState()
        state.failure = BackupFailure(IllegalStateException("bad"))
        state.clearImportOverlay()
        assertNull(state.failure)
    }

    @Test
    fun dismissWhileImportingCancelsOnce() {
        val state = BackupSettingsState()
        val job = Job()
        state.isImporting = true
        state.importJob = job
        state.preview = samplePreview()
        state.dismissImportSheet()
        assertTrue(state.isCancellingImport)
        assertTrue(job.isCancelled)
        assertNotNull(state.preview)
        state.dismissImportSheet()
        assertTrue(state.isImporting)
        assertNotNull(state.preview)
    }

    @Test
    fun dismissWhileIdleClearsTheOverlay() {
        val state = BackupSettingsState()
        state.preview = samplePreview()
        state.importJson = "{}"
        state.importCancelled = true
        state.dismissImportSheet()
        assertNull(state.preview)
        assertEquals("", state.importJson)
        assertFalse(state.importCancelled)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class BackupSettingsTransferTest {
    @Test
    fun beginExportSkipsWhenBusy() = runTest(UnconfinedTestDispatcher()) {
        val state = BackupSettingsState()
        state.isParsing = true
        var called = false
        state.beginExport(this, {
            called = true
            null
        }) {}
        assertFalse(called)
        assertTrue(state.isParsing)
    }

    @Test
    fun beginExportStoresASummary() = runTest(UnconfinedTestDispatcher()) {
        val state = BackupSettingsState()
        val summary = BackupExportSuccessSummary("radio.mc1backup", 12, samplePreview())
        var signaled = false
        state.beginExport(this, { summary }) { signaled = true }
        assertEquals(summary, state.exportSummary)
        assertTrue(signaled)
        assertFalse(state.isExporting)
        assertNull(state.failure)
    }

    @Test
    fun cancelledExportStaysIdle() = runTest(UnconfinedTestDispatcher()) {
        val state = BackupSettingsState()
        var signaled = false
        state.beginExport(this, { null }) { signaled = true }
        assertFalse(signaled)
        assertNull(state.exportSummary)
        assertNull(state.failure)
        assertFalse(state.isExporting)
    }

    @Test
    fun beginExportMapsAFailure() = runTest(UnconfinedTestDispatcher()) {
        val state = BackupSettingsState()
        var signaled = false
        state.beginExport(this, { error("disk") }) { signaled = true }
        assertFalse(signaled)
        assertNull(state.exportSummary)
        assertTrue(state.failure?.isExport == true)
        assertFalse(state.isExporting)
    }

    @Test
    fun beginParseWithNoPickerLeavesTheSheetAlone() = runTest(UnconfinedTestDispatcher()) {
        val state = BackupSettingsState()
        state.failure = BackupFailure(IllegalStateException("old"))
        state.importJson = "kept"
        state.beginParse(null, this)
        assertEquals("kept", state.importJson)
        assertNotNull(state.failure)
        assertFalse(state.isParsing)
    }

    @Test
    fun cancelledParseClearsAStalePreview() = runTest(UnconfinedTestDispatcher()) {
        val state = BackupSettingsState()
        state.preview = samplePreview()
        state.importJson = "kept"
        state.failure = BackupFailure(IllegalStateException("old"))
        state.beginParse({ null }, this)
        assertNull(state.preview)
        assertNull(state.failure)
        assertEquals("kept", state.importJson)
        assertFalse(state.isParsing)
    }

    @Test
    fun beginParseStoresAPreview() = runTest(UnconfinedTestDispatcher()) {
        val state = BackupSettingsState()
        val json = loadBackupFixture("full-envelope.json")
        state.importResult = BackupImportResult(devicesInserted = 1)
        state.beginParse({ json }, this)
        assertEquals(json, state.importJson)
        assertTrue(state.preview?.manifestValid == true)
        assertNull(state.importResult)
        assertNull(state.failure)
        assertFalse(state.isParsing)
    }

    @Test
    fun beginParseMapsAFailure() = runTest(UnconfinedTestDispatcher()) {
        val state = BackupSettingsState()
        state.beginParse({ "not-json" }, this)
        assertNull(state.preview)
        assertFalse(state.failure?.isExport == true)
        assertNotNull(state.failure)
        assertFalse(state.isParsing)
    }
}
