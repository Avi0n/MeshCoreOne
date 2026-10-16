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
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Exporting a node configuration. A failure still reports the fallback and clears the spinner.
 * Cancellation and an error propagate and do not record a failure.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NodeConfigExportWriteTest {
    @Test
    fun anExportRunsAndClearsTheSpinner() = runTest {
        val harness = ExportHarness()
        launchNodeConfigExport(exportScope(), harness.request())
        advanceUntilIdle()

        assertEquals(1, harness.exports)
        assertEquals(listOf<String?>(null), harness.errors)
        assertEquals(listOf(true, false), harness.exporting)
    }

    @Test
    fun anExportWithNothingSelectedDoesNotStart() = runTest {
        val harness = ExportHarness(hasSelection = false)
        launchNodeConfigExport(exportScope(), harness.request())
        advanceUntilIdle()

        assertEquals(0, harness.exports)
        assertTrue(harness.exporting.isEmpty())
        assertTrue(harness.errors.isEmpty())
    }

    @Test
    fun anInFlightExportDoesNotStartAnother() = runTest {
        val harness = ExportHarness(inFlight = true)
        launchNodeConfigExport(exportScope(), harness.request())
        advanceUntilIdle()

        assertEquals(0, harness.exports)
        assertTrue(harness.exporting.isEmpty())
        assertTrue(harness.errors.isEmpty())
    }

    @Test
    fun anExportExceptionReportsTheFallback() = runTest {
        val harness = ExportHarness(body = { throw IllegalArgumentException("bad export") })
        launchNodeConfigExport(exportScope(), harness.request())
        advanceUntilIdle()

        assertEquals(listOf(null, EXPORT_FAILED), harness.errors)
        assertEquals(0, harness.exports)
        assertEquals(listOf(true, false), harness.exporting)
    }

    @Test
    fun aMappedExportExceptionUsesThatText() = runTest {
        val harness = ExportHarness(
            body = { throw IllegalStateException("No radio connected") },
            bridgeErrorText = { error -> error.message },
        )
        launchNodeConfigExport(exportScope(), harness.request())
        advanceUntilIdle()

        assertEquals(listOf(null, "No radio connected"), harness.errors)
        assertEquals(0, harness.exports)
    }

    @Test
    fun aCancelledExportPropagatesAndDoesNotRecordAFailure() = runTest {
        val cancelled = CancellationException("superseded")
        val harness = ExportHarness(body = { throw cancelled })
        val scope = exportScope()
        launchNodeConfigExport(scope, harness.request())
        val child = scope.coroutineContext[Job]!!.children.single()
        var cause: Throwable? = null
        child.invokeOnCompletion { cause = it }
        advanceUntilIdle()

        assertSame(cancelled, cause)
        assertEquals(0, harness.exports)
        assertEquals(listOf<String?>(null), harness.errors)
        assertEquals(listOf(true, false), harness.exporting)
    }

    @Test
    fun anExportErrorPropagatesAndDoesNotRecordAFailure() = runTest {
        var thrown: Throwable? = null
        val probe = NodeConfigExportProbeError()
        val harness = ExportHarness(body = { throw probe })
        launchNodeConfigExport(exportScope { thrown = it }, harness.request())
        advanceUntilIdle()

        assertSame(probe, thrown)
        assertEquals(0, harness.exports)
        assertEquals(listOf<String?>(null), harness.errors)
        assertEquals(listOf(true, false), harness.exporting)
    }
}

private class NodeConfigExportProbeError : Error("probe")

private const val EXPORT_FAILED = "export failed"

@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.exportScope(onError: (Throwable) -> Unit = {}): CoroutineScope = CoroutineScope(
    SupervisorJob() + StandardTestDispatcher(testScheduler) + CoroutineExceptionHandler { _, error -> onError(error) },
)

private class ExportHarness(
    private val inFlight: Boolean = false,
    private val hasSelection: Boolean = true,
    private val bridgeErrorText: (Throwable) -> String? = { null },
    private val body: suspend () -> Unit = {},
) {
    val errors = mutableListOf<String?>()
    val exporting = mutableListOf<Boolean>()
    var exports = 0

    suspend fun export() {
        body()
        exports += 1
    }

    fun request(): NodeConfigExportWrite = NodeConfigExportWrite(
        inFlight = inFlight,
        hasSelection = hasSelection,
        onExport = ::export,
        onExporting = { exporting += it },
        onError = { errors += it },
        bridgeErrorText = bridgeErrorText,
        exportFailedMessage = EXPORT_FAILED,
    )
}
