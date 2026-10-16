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
 * Applying a default flood scope. A failure still reports the settings text or the retry alert, and
 * the spinner always clears. Cancellation and an error propagate and leave the scope alone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FloodScopeApplyTest {
    @Test
    fun applyingARegionWritesThatName() = runTest {
        val harness = FloodHarness(name = SCOPE_NAME)
        launchFloodScopeApply(floodScope(), harness.request())
        advanceUntilIdle()

        assertEquals(listOf<String?>(SCOPE_NAME), harness.applied)
        assertEquals(listOf<String?>(null), harness.errors)
        assertEquals(listOf<String?>(null), harness.statuses)
        assertFalse(harness.alert.isPresented)
        assertEquals(listOf(true, false), harness.flight)
    }

    @Test
    fun disablingTheScopeWritesNull() = runTest {
        val harness = FloodHarness(name = null)
        launchFloodScopeApply(floodScope(), harness.request())
        advanceUntilIdle()

        assertEquals(listOf<String?>(null), harness.applied)
        assertEquals(listOf(true, false), harness.flight)
    }

    @Test
    fun anInFlightApplyDoesNotStartAnotherWrite() = runTest {
        val harness = FloodHarness(name = SCOPE_NAME, inFlight = true)
        launchFloodScopeApply(floodScope(), harness.request())
        advanceUntilIdle()

        assertTrue(harness.applied.isEmpty())
        assertTrue(harness.flight.isEmpty())
        assertTrue(harness.errors.isEmpty())
        assertTrue(harness.statuses.isEmpty())
    }

    @Test
    fun aScopeExceptionReportsTheSettingsText() = runTest {
        val harness = FloodHarness(name = SCOPE_NAME, body = { throw IllegalArgumentException("bad scope") })
        launchFloodScopeApply(floodScope(), harness.request())
        advanceUntilIdle()

        assertEquals(listOf(null, "bad scope"), harness.errors)
        assertTrue(harness.applied.isEmpty())
        assertFalse(harness.alert.isPresented)
        assertEquals(listOf<String?>(null), harness.statuses)
        assertEquals(listOf(true, false), harness.flight)
    }

    @Test
    fun aRetryableScopeFailureShowsTheAlert() = runTest {
        val harness = FloodHarness(name = SCOPE_NAME, body = { throw IllegalStateException("No radio connected") })
        launchFloodScopeApply(floodScope(), harness.request())
        advanceUntilIdle()

        assertTrue(harness.alert.isPresented)
        assertEquals("No radio connected", harness.alert.message)
        assertEquals(listOf<String?>(null), harness.errors)
        assertTrue(harness.applied.isEmpty())
        assertEquals(listOf(true, false), harness.flight)
    }

    @Test
    fun aCancelledScopeApplyPropagatesAndLeavesTheScope() = runTest {
        val cancelled = CancellationException("superseded")
        val harness = FloodHarness(name = SCOPE_NAME, body = { throw cancelled })
        val scope = floodScope()
        launchFloodScopeApply(scope, harness.request())
        val child = scope.coroutineContext[Job]!!.children.single()
        var cause: Throwable? = null
        child.invokeOnCompletion { cause = it }
        advanceUntilIdle()

        assertSame(cancelled, cause)
        assertTrue(harness.applied.isEmpty())
        assertEquals(listOf<String?>(null), harness.errors)
        assertFalse(harness.alert.isPresented)
        assertEquals(listOf(true, false), harness.flight)
    }

    @Test
    fun aScopeApplyErrorPropagatesAndLeavesTheScope() = runTest {
        var thrown: Throwable? = null
        val handler = CoroutineExceptionHandler { _, error -> thrown = error }
        val probe = FloodScopeProbeError()
        val harness = FloodHarness(name = SCOPE_NAME, body = { throw probe })
        launchFloodScopeApply(floodScope(handler), harness.request())
        advanceUntilIdle()

        assertSame(probe, thrown)
        assertTrue(harness.applied.isEmpty())
        assertEquals(listOf<String?>(null), harness.errors)
        assertFalse(harness.alert.isPresented)
        assertEquals(listOf(true, false), harness.flight)
    }
}

private class FloodScopeProbeError : Error("probe")

private const val SCOPE_NAME = "Europe"

@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.floodScope(
    handler: CoroutineExceptionHandler = CoroutineExceptionHandler { _, _ -> },
): CoroutineScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler) + handler)

private class FloodHarness(
    private val name: String?,
    private val inFlight: Boolean = false,
    private val body: suspend (String?) -> Unit = {},
) {
    val alert = RetryAlertState()
    val errors = mutableListOf<String?>()
    val statuses = mutableListOf<String?>()
    val flight = mutableListOf<Boolean>()
    val applied = mutableListOf<String?>()

    suspend fun apply(scopeName: String?) {
        body(scopeName)
        applied += scopeName
    }

    fun request(): FloodScopeApply = FloodScopeApply(
        name = name,
        inFlight = inFlight,
        onApply = ::apply,
        onInFlight = { flight += it },
        onStatus = { statuses += it },
        onError = { errors += it },
        settingsErrorText = { it.message ?: "settings failed" },
        retryAlert = alert,
        onMaxRetriesExceeded = {},
    )
}
