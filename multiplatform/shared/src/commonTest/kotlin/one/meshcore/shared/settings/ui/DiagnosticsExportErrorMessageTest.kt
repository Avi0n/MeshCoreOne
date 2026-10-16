package one.meshcore.shared.settings.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A diagnostics export fault must not interpolate exception text. iOS shows the export-failed copy
 * when the file cannot be created; a thrown Kotlin failure uses the same string.
 */
class DiagnosticsExportErrorMessageTest {
    @Test
    fun aThrownFailureShowsExportFailedNotExceptionText() {
        val result = Result.failure<String>(IllegalStateException("disk full"))
        assertEquals("export failed", diagnosticsExportErrorMessage(result, "export failed"))
    }

    @Test
    fun aBlankExportShowsExportFailed() {
        assertEquals("export failed", diagnosticsExportErrorMessage(Result.success(""), "export failed"))
        assertEquals("export failed", diagnosticsExportErrorMessage(Result.success("  "), "export failed"))
    }

    @Test
    fun shareableTextHasNoError() {
        assertNull(diagnosticsExportErrorMessage(Result.success("log body"), "export failed"))
    }

    @Test
    fun exportIsDisabledWhileInProgress() {
        assertEquals(true, diagnosticsExportEnabled(isExporting = false))
        assertEquals(false, diagnosticsExportEnabled(isExporting = true))
    }
}
