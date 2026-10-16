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
import one.meshcore.shared.settings.ManualRadioParams
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Applying manual radio parameters. A failure still reports the settings text or the retry alert, and
 * the spinner always clears. Cancellation and an error propagate and do not record the apply.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ManualRadioApplyTest {
    @Test
    fun aManualApplyWritesTheParams() = runTest {
        val harness = RadioHarness()
        launchManualRadioApply(radioScope(), harness.request())
        advanceUntilIdle()

        assertEquals(listOf(RADIO_PARAMS), harness.applied)
        assertEquals(listOf(false, true), harness.appliedFlags)
        assertEquals(listOf<String?>(null), harness.errors)
        assertFalse(harness.alert.isPresented)
        assertEquals(listOf(true, false), harness.flight)
    }

    @Test
    fun aManualApplyExceptionReportsTheSettingsText() = runTest {
        val harness = RadioHarness(body = { throw IllegalArgumentException("bad radio") })
        launchManualRadioApply(radioScope(), harness.request())
        advanceUntilIdle()

        assertEquals(listOf(null, "bad radio"), harness.errors)
        assertTrue(harness.applied.isEmpty())
        assertEquals(listOf(false), harness.appliedFlags)
        assertFalse(harness.alert.isPresented)
        assertEquals(listOf(true, false), harness.flight)
    }

    @Test
    fun aRetryableManualApplyShowsTheAlert() = runTest {
        val harness = RadioHarness(body = { throw IllegalStateException("No radio connected") })
        launchManualRadioApply(radioScope(), harness.request())
        advanceUntilIdle()

        assertTrue(harness.alert.isPresented)
        assertEquals("No radio connected", harness.alert.message)
        assertEquals(listOf<String?>(null), harness.errors)
        assertTrue(harness.applied.isEmpty())
        assertEquals(listOf(false), harness.appliedFlags)
        assertEquals(listOf(true, false), harness.flight)
    }

    @Test
    fun aCancelledManualApplyPropagatesAndDoesNotRecordIt() = runTest {
        val cancelled = CancellationException("superseded")
        val harness = RadioHarness(body = { throw cancelled })
        val scope = radioScope()
        launchManualRadioApply(scope, harness.request())
        val child = scope.coroutineContext[Job]!!.children.single()
        var cause: Throwable? = null
        child.invokeOnCompletion { cause = it }
        advanceUntilIdle()

        assertSame(cancelled, cause)
        assertTrue(harness.applied.isEmpty())
        assertEquals(listOf(false), harness.appliedFlags)
        assertEquals(listOf<String?>(null), harness.errors)
        assertFalse(harness.alert.isPresented)
        assertEquals(listOf(true, false), harness.flight)
    }

    @Test
    fun aManualApplyErrorPropagatesAndDoesNotRecordIt() = runTest {
        var thrown: Throwable? = null
        val handler = CoroutineExceptionHandler { _, error -> thrown = error }
        val probe = ManualRadioProbeError()
        val harness = RadioHarness(body = { throw probe })
        launchManualRadioApply(radioScope(handler), harness.request())
        advanceUntilIdle()

        assertSame(probe, thrown)
        assertTrue(harness.applied.isEmpty())
        assertEquals(listOf(false), harness.appliedFlags)
        assertEquals(listOf<String?>(null), harness.errors)
        assertFalse(harness.alert.isPresented)
        assertEquals(listOf(true, false), harness.flight)
    }
}

private class ManualRadioProbeError : Error("probe")

private val RADIO_PARAMS = ManualRadioParams(
    frequencyMHz = 910.525,
    bandwidthKHz = 62.5,
    spreadingFactor = 7,
    codingRate = 5,
    txPower = 10,
)

@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.radioScope(
    handler: CoroutineExceptionHandler = CoroutineExceptionHandler { _, _ -> },
): CoroutineScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler) + handler)

private class RadioHarness(private val body: suspend (ManualRadioParams) -> Unit = {}) {
    val alert = RetryAlertState()
    val errors = mutableListOf<String?>()
    val flight = mutableListOf<Boolean>()
    val applied = mutableListOf<ManualRadioParams>()
    val appliedFlags = mutableListOf<Boolean>()

    suspend fun write(params: ManualRadioParams) {
        body(params)
        applied += params
    }

    fun request(): ManualRadioApply = ManualRadioApply(
        params = RADIO_PARAMS,
        onApply = ::write,
        onInFlight = { flight += it },
        onError = { errors += it },
        onApplied = { appliedFlags += it },
        settingsErrorText = { it.message ?: "settings failed" },
        retryAlert = alert,
        onMaxRetriesExceeded = {},
    )
}
