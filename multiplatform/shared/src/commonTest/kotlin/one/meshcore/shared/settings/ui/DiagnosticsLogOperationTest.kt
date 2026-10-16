package one.meshcore.shared.settings.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class DiagnosticsLogOperationTest {
    @Test
    fun cancelledExportDoesNotBecomeAnExportFailure() = runTest {
        val cancelled = CancellationException("export cancelled")

        val thrown = assertFailsWith<CancellationException> {
            runDiagnosticsLogOperation<String> { throw cancelled }
        }

        assertSame(cancelled, thrown)
    }

    @Test
    fun failedExportUsesTheScreensFailureCopy() = runTest {
        val result = runDiagnosticsLogOperation<String> { error("storage failure") }

        assertEquals("export failed", diagnosticsExportErrorMessage(result, "export failed"))
    }
}
