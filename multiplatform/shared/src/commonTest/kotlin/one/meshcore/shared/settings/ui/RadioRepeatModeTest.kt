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
import one.meshcore.shared.settings.RadioDeviceSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Turning client repeat on or off. A failure still reports the settings text or the retry alert, and
 * the spinner always clears. Cancellation and an error propagate and leave repeat mode alone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RadioRepeatModeTest {
    @Test
    fun enablingRepeatWritesTheFlag() = runTest {
        val harness = RepeatHarness(enabled = true)
        launchRadioRepeatMode(repeatScope(), harness.request())
        advanceUntilIdle()

        assertEquals(true, harness.snapshot?.clientRepeat)
        assertEquals(1, harness.enables)
        assertEquals(0, harness.disables)
        assertEquals(listOf<String?>(null), harness.errors)
        assertFalse(harness.alert.isPresented)
        assertEquals(listOf(true, false), harness.flight)
    }

    @Test
    fun disablingRepeatClearsTheFlag() = runTest {
        val harness = RepeatHarness(enabled = false)
        launchRadioRepeatMode(repeatScope(), harness.request())
        advanceUntilIdle()

        assertEquals(false, harness.snapshot?.clientRepeat)
        assertEquals(0, harness.enables)
        assertEquals(1, harness.disables)
    }

    @Test
    fun aRepeatExceptionReportsTheSettingsText() = runTest {
        val harness = RepeatHarness(enabled = true, body = { throw IllegalArgumentException("bad repeat") })
        launchRadioRepeatMode(repeatScope(), harness.request())
        advanceUntilIdle()

        assertEquals(listOf(null, "bad repeat"), harness.errors)
        assertFalse(harness.alert.isPresented)
        assertNull(harness.snapshot)
        assertEquals(listOf(true, false), harness.flight)
    }

    @Test
    fun aRetryableRepeatFailureShowsTheAlert() = runTest {
        val harness = RepeatHarness(enabled = true, body = { throw IllegalStateException("No radio connected") })
        launchRadioRepeatMode(repeatScope(), harness.request())
        advanceUntilIdle()

        assertTrue(harness.alert.isPresented)
        assertEquals("No radio connected", harness.alert.message)
        assertEquals(listOf<String?>(null), harness.errors)
        assertNull(harness.snapshot)
        assertEquals(listOf(true, false), harness.flight)
    }

    @Test
    fun aCancelledRepeatChangePropagatesAndLeavesTheFlag() = runTest {
        val cancelled = CancellationException("superseded")
        val harness = RepeatHarness(enabled = true, body = { throw cancelled })
        val scope = repeatScope()
        launchRadioRepeatMode(scope, harness.request())
        val child = scope.coroutineContext[Job]!!.children.single()
        var cause: Throwable? = null
        child.invokeOnCompletion { cause = it }
        advanceUntilIdle()

        assertSame(cancelled, cause)
        assertEquals(listOf<String?>(null), harness.errors)
        assertFalse(harness.alert.isPresented)
        assertNull(harness.snapshot)
        assertEquals(listOf(true, false), harness.flight)
    }

    @Test
    fun aRepeatChangeErrorPropagatesAndLeavesTheFlag() = runTest {
        var thrown: Throwable? = null
        val handler = CoroutineExceptionHandler { _, error -> thrown = error }
        val probe = RadioRepeatProbeError()
        val harness = RepeatHarness(enabled = true, body = { throw probe })
        launchRadioRepeatMode(repeatScope(handler), harness.request())
        advanceUntilIdle()

        assertSame(probe, thrown)
        assertEquals(listOf<String?>(null), harness.errors)
        assertFalse(harness.alert.isPresented)
        assertNull(harness.snapshot)
        assertEquals(listOf(true, false), harness.flight)
    }
}

private class RadioRepeatProbeError : Error("probe")

@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.repeatScope(
    handler: CoroutineExceptionHandler = CoroutineExceptionHandler { _, _ -> },
): CoroutineScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler) + handler)

private class RepeatHarness(private val enabled: Boolean, private val body: suspend () -> Unit = {}) {
    val alert = RetryAlertState()
    val errors = mutableListOf<String?>()
    val flight = mutableListOf<Boolean>()
    var snapshot: RadioDeviceSnapshot? = null
    var enables = 0
    var disables = 0

    fun request(): RadioRepeatWrite = RadioRepeatWrite(
        enabled = enabled,
        inFlight = false,
        currentSnapshot = {
            RadioDeviceSnapshot(
                frequencyKHz = 1L,
                bandwidthHz = 125_000L,
                spreadingFactor = 7,
                codingRate = 5,
                clientRepeat = !enabled,
            )
        },
        onEnable = {
            enables += 1
            body()
        },
        onDisable = {
            disables += 1
            body()
        },
        onInFlight = { flight += it },
        onError = { errors += it },
        onSnapshot = { snapshot = it },
        settingsErrorText = { it.message ?: "settings failed" },
        retryAlert = alert,
        onMaxRetriesExceeded = {},
    )
}
