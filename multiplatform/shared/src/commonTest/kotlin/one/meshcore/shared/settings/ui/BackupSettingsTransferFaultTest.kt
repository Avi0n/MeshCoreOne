package one.meshcore.shared.settings.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Backup export and file parse. A failure still records the error and clears the spinner.
 * Cancellation and an error propagate and do not record a failure.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackupSettingsTransferFaultTest {
    @Test
    fun aCancelledExportPropagatesAndStaysIdle() = runTest {
        val cancelled = CancellationException("superseded")
        val state = BackupSettingsState()
        val scope = transferScope()
        var signaled = false
        state.beginExport(scope, { throw cancelled }) { signaled = true }
        val child = scope.coroutineContext[Job]!!.children.single()
        var cause: Throwable? = null
        child.invokeOnCompletion { cause = it }
        advanceUntilIdle()

        assertSame(cancelled, cause)
        assertFalse(signaled)
        assertNull(state.exportSummary)
        assertNull(state.failure)
        assertFalse(state.isExporting)
    }

    @Test
    fun anExportErrorPropagatesAndStaysIdle() = runTest {
        var thrown: Throwable? = null
        val probe = BackupTransferProbeError()
        val state = BackupSettingsState()
        var signaled = false
        state.beginExport(transferScope { thrown = it }, { throw probe }) { signaled = true }
        advanceUntilIdle()

        assertSame(probe, thrown)
        assertFalse(signaled)
        assertNull(state.exportSummary)
        assertNull(state.failure)
        assertFalse(state.isExporting)
    }

    @Test
    fun aCancelledParsePropagatesAndLeavesTheFailureClear() = runTest {
        val cancelled = CancellationException("superseded")
        val state = BackupSettingsState()
        val scope = transferScope()
        state.importJson = "kept"
        state.beginParse({ throw cancelled }, scope)
        val child = scope.coroutineContext[Job]!!.children.single()
        var cause: Throwable? = null
        child.invokeOnCompletion { cause = it }
        advanceUntilIdle()

        assertSame(cancelled, cause)
        assertEquals("kept", state.importJson)
        assertNull(state.preview)
        assertNull(state.failure)
        assertFalse(state.isParsing)
    }

    @Test
    fun aParseErrorPropagatesAndLeavesTheFailureClear() = runTest {
        var thrown: Throwable? = null
        val probe = BackupTransferProbeError()
        val state = BackupSettingsState()
        state.importJson = "kept"
        state.beginParse({ throw probe }, transferScope { thrown = it })
        advanceUntilIdle()

        assertSame(probe, thrown)
        assertEquals("kept", state.importJson)
        assertNull(state.preview)
        assertNull(state.failure)
        assertFalse(state.isParsing)
    }
}

private class BackupTransferProbeError : Error("probe")

@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.transferScope(onError: (Throwable) -> Unit = {}): CoroutineScope = CoroutineScope(
    SupervisorJob() + StandardTestDispatcher(testScheduler) + CoroutineExceptionHandler { _, error -> onError(error) },
)
