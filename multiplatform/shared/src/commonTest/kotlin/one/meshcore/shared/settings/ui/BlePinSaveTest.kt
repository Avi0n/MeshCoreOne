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
 * Saving a BLE PIN. A failure still reports the settings text or the retry alert, and the spinner
 * always clears. Cancellation and an error propagate and do not record the save.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BlePinSaveTest {
    @Test
    fun aPinSaveWritesThePinAndClearsTheSpinner() = runTest {
        val harness = PinHarness()
        launchBlePinSave(pinScope(), harness::write, harness.request())
        advanceUntilIdle()

        assertEquals(listOf(TEST_PIN), harness.written)
        assertEquals(1, harness.saved)
        assertEquals(listOf<String?>(null), harness.errors)
        assertFalse(harness.alert.isPresented)
        assertEquals(listOf(true, false), harness.saving)
    }

    @Test
    fun aMissingWriterDoesNotStartTheSave() = runTest {
        val harness = PinHarness()
        launchBlePinSave(pinScope(), null, harness.request())
        advanceUntilIdle()

        assertTrue(harness.written.isEmpty())
        assertEquals(0, harness.saved)
        assertTrue(harness.saving.isEmpty())
        assertTrue(harness.errors.isEmpty())
    }

    @Test
    fun aPinSaveExceptionReportsTheSettingsText() = runTest {
        val harness = PinHarness(body = { throw IllegalArgumentException("bad pin") })
        launchBlePinSave(pinScope(), harness::write, harness.request())
        advanceUntilIdle()

        assertEquals(listOf(null, "bad pin"), harness.errors)
        assertEquals(0, harness.saved)
        assertFalse(harness.alert.isPresented)
        assertEquals(listOf(true, false), harness.saving)
    }

    @Test
    fun aRetryablePinSaveShowsTheAlert() = runTest {
        val harness = PinHarness(body = { throw IllegalStateException("No radio connected") })
        launchBlePinSave(pinScope(), harness::write, harness.request())
        advanceUntilIdle()

        assertTrue(harness.alert.isPresented)
        assertEquals("No radio connected", harness.alert.message)
        assertEquals(0, harness.saved)
        assertEquals(listOf<String?>(null), harness.errors)
        assertEquals(listOf(true, false), harness.saving)
    }

    @Test
    fun aCancelledPinSavePropagatesAndDoesNotRecordTheSave() = runTest {
        val cancelled = CancellationException("superseded")
        val harness = PinHarness(body = { throw cancelled })
        val scope = pinScope()
        launchBlePinSave(scope, harness::write, harness.request())
        val child = scope.coroutineContext[Job]!!.children.single()
        var cause: Throwable? = null
        child.invokeOnCompletion { cause = it }
        advanceUntilIdle()

        assertSame(cancelled, cause)
        assertEquals(0, harness.saved)
        assertEquals(listOf<String?>(null), harness.errors)
        assertFalse(harness.alert.isPresented)
        assertEquals(listOf(true, false), harness.saving)
    }

    @Test
    fun aPinSaveErrorPropagatesAndDoesNotRecordTheSave() = runTest {
        var thrown: Throwable? = null
        val handler = CoroutineExceptionHandler { _, error -> thrown = error }
        val probe = BlePinProbeError()
        val harness = PinHarness(body = { throw probe })
        launchBlePinSave(pinScope(handler), harness::write, harness.request())
        advanceUntilIdle()

        assertSame(probe, thrown)
        assertEquals(0, harness.saved)
        assertEquals(listOf<String?>(null), harness.errors)
        assertFalse(harness.alert.isPresented)
        assertEquals(listOf(true, false), harness.saving)
    }
}

private class BlePinProbeError : Error("probe")

private const val TEST_PIN = 123_456L

@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.pinScope(
    handler: CoroutineExceptionHandler = CoroutineExceptionHandler { _, _ -> },
): CoroutineScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler) + handler)

private class PinHarness(private val body: suspend (Long) -> Unit = {}) {
    val alert = RetryAlertState()
    val errors = mutableListOf<String?>()
    val saving = mutableListOf<Boolean>()
    val written = mutableListOf<Long>()
    var saved = 0

    suspend fun write(pin: Long) {
        written += pin
        body(pin)
    }

    fun request(): BlePinSave = BlePinSave(
        pin = TEST_PIN,
        onSavingChange = { saving += it },
        onError = { errors += it },
        onSaved = { saved += 1 },
        retryAlert = alert,
        settingsErrorText = { it.message ?: "settings failed" },
    )
}
