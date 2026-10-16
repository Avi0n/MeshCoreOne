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
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Saving telemetry, direct-message acknowledgements, or the path-hash mode. A failure still reports
 * the settings text or the retry alert and clears the spinner. Cancellation and an error propagate
 * and leave the value uncommitted.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OtherParamsSaveTest {
    @Test
    fun aSaveCommitsAndClearsTheSpinner() = runTest {
        val harness = OtherParamsHarness()
        launchOtherParamsSave(paramsScope(), harness.request())
        advanceUntilIdle()

        assertEquals(1, harness.saves)
        assertEquals(1, harness.commits)
        assertEquals(listOf<String?>(null), harness.errors)
        assertFalse(harness.alert.isPresented)
        assertEquals(listOf(true, false), harness.saving)
        assertEquals(listOf(true, false), harness.flight)
    }

    @Test
    fun aSaveExceptionReportsTheSettingsText() = runTest {
        val harness = OtherParamsHarness(body = { throw IllegalArgumentException("bad params") })
        launchOtherParamsSave(paramsScope(), harness.request())
        advanceUntilIdle()

        assertEquals(listOf(null, "bad params"), harness.errors)
        assertEquals(0, harness.commits)
        assertFalse(harness.alert.isPresented)
        assertEquals(listOf(true, false), harness.saving)
        assertEquals(listOf(true, false), harness.flight)
    }

    @Test
    fun aRetryableSaveFailureShowsTheAlert() = runTest {
        val harness = OtherParamsHarness(body = { throw IllegalStateException("No radio connected") })
        launchOtherParamsSave(paramsScope(), harness.request())
        advanceUntilIdle()

        assertTrue(harness.alert.isPresented)
        assertEquals("No radio connected", harness.alert.message)
        assertEquals(listOf<String?>(null), harness.errors)
        assertEquals(0, harness.commits)
        assertEquals(listOf(true, false), harness.flight)
    }

    @Test
    fun aCancelledSavePropagatesWithoutAFailure() = runTest {
        val cancelled = CancellationException("superseded")
        val harness = OtherParamsHarness(body = { throw cancelled })
        val scope = paramsScope()
        launchOtherParamsSave(scope, harness.request())
        val child = scope.coroutineContext[Job]!!.children.single()
        child.invokeOnCompletion { harness.cause = it }
        advanceUntilIdle()

        assertSame(cancelled, harness.cause)
        assertEquals(listOf<String?>(null), harness.errors)
        assertEquals(0, harness.commits)
        assertFalse(harness.alert.isPresented)
        assertEquals(listOf(true, false), harness.saving)
        assertEquals(listOf(true, false), harness.flight)
    }

    @Test
    fun aSaveErrorPropagatesWithoutAFailure() = runTest {
        var thrown: Throwable? = null
        val probe = OtherParamsProbeError()
        val harness = OtherParamsHarness(body = { throw probe })
        launchOtherParamsSave(paramsScope { thrown = it }, harness.request())
        advanceUntilIdle()

        assertSame(probe, thrown)
        assertEquals(listOf<String?>(null), harness.errors)
        assertEquals(0, harness.commits)
        assertFalse(harness.alert.isPresented)
        assertEquals(listOf(true, false), harness.saving)
        assertEquals(listOf(true, false), harness.flight)
    }
}

private class OtherParamsProbeError : Error("probe")

@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.paramsScope(onError: (Throwable) -> Unit = {}): CoroutineScope = CoroutineScope(
    SupervisorJob() + StandardTestDispatcher(testScheduler) + CoroutineExceptionHandler { _, error -> onError(error) },
)

private class OtherParamsHarness(private val body: suspend () -> Unit = {}) {
    val alert = RetryAlertState()
    val errors = mutableListOf<String?>()
    val saving = mutableListOf<Boolean>()
    val flight = mutableListOf<Boolean>()
    var saves = 0
    var commits = 0
    var cause: Throwable? = null

    fun request() = OtherParamsSave(
        onSave = {
            body()
            saves += 1
        },
        onSaving = { saving += it },
        onError = { errors += it },
        onCommitted = { commits += 1 },
        onWriteInFlight = { flight += it },
        settingsErrorText = { it.message ?: "settings failed" },
        retryAlert = alert,
        onMaxRetriesExceeded = {},
    )
}
