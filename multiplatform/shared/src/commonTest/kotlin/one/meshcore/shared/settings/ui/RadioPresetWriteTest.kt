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
import one.meshcore.shared.settings.RadioPresets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Applying a radio preset. A failure still reports the settings text or the retry alert, and the
 * spinner always clears. Cancellation and an error propagate and leave the snapshot alone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RadioPresetWriteTest {
    @Test
    fun aPresetApplyWritesTheFullSnapshot() = runTest {
        val preset = RadioPresets.all.first()
        val harness = PresetHarness(repeatEnabled = false)
        launchRadioPresetApply(presetScope(), harness.request(preset))
        advanceUntilIdle()

        assertEquals(preset.id, harness.snapshot?.appliedRadioPresetID)
        assertEquals(preset.frequencyKHz, harness.snapshot?.frequencyKHz)
        assertEquals(preset.bandwidthHz, harness.snapshot?.bandwidthHz)
        assertEquals(1, harness.otherReads)
        assertEquals(listOf<String?>(null), harness.errors)
        assertFalse(harness.alert.isPresented)
        assertEquals(listOf(true, false), harness.flight)
    }

    @Test
    fun aRepeatPresetApplyWritesOnlyTheFrequency() = runTest {
        val preset = RadioPresets.all.first()
        val harness = PresetHarness(repeatEnabled = true)
        launchRadioPresetApply(presetScope(), harness.request(preset))
        advanceUntilIdle()

        assertEquals(preset.frequencyKHz, harness.snapshot?.frequencyKHz)
        assertEquals(ORIGINAL_BANDWIDTH_HZ, harness.snapshot?.bandwidthHz)
        assertNull(harness.snapshot?.appliedRadioPresetID)
        assertEquals(0, harness.otherReads)
        assertEquals(1, harness.repeatApplies)
        assertEquals(0, harness.applies)
    }

    @Test
    fun aPresetApplyExceptionReportsTheSettingsText() = runTest {
        val harness = PresetHarness(apply = { throw IllegalArgumentException("bad preset") })
        launchRadioPresetApply(presetScope(), harness.request(RadioPresets.all.first()))
        advanceUntilIdle()

        assertEquals(listOf(null, "bad preset"), harness.errors)
        assertFalse(harness.alert.isPresented)
        assertNull(harness.snapshot)
        assertEquals(listOf(true, false), harness.flight)
    }

    @Test
    fun aRetryablePresetApplyShowsTheAlert() = runTest {
        val harness = PresetHarness(apply = { throw IllegalStateException("No radio connected") })
        launchRadioPresetApply(presetScope(), harness.request(RadioPresets.all.first()))
        advanceUntilIdle()

        assertTrue(harness.alert.isPresented)
        assertEquals("No radio connected", harness.alert.message)
        assertEquals(listOf<String?>(null), harness.errors)
        assertNull(harness.snapshot)
        assertEquals(listOf(true, false), harness.flight)
    }

    @Test
    fun aCancelledPresetApplyPropagatesAndLeavesTheSnapshot() = runTest {
        val cancelled = CancellationException("superseded")
        val harness = PresetHarness(apply = { throw cancelled })
        val scope = presetScope()
        launchRadioPresetApply(scope, harness.request(RadioPresets.all.first()))
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
    fun aPresetApplyErrorPropagatesAndLeavesTheSnapshot() = runTest {
        var thrown: Throwable? = null
        val handler = CoroutineExceptionHandler { _, error -> thrown = error }
        val probe = RadioPresetProbeError()
        val harness = PresetHarness(apply = { throw probe })
        launchRadioPresetApply(presetScope(handler), harness.request(RadioPresets.all.first()))
        advanceUntilIdle()

        assertSame(probe, thrown)
        assertEquals(listOf<String?>(null), harness.errors)
        assertFalse(harness.alert.isPresented)
        assertNull(harness.snapshot)
        assertEquals(listOf(true, false), harness.flight)
    }
}

private class RadioPresetProbeError : Error("probe")

private const val ORIGINAL_BANDWIDTH_HZ = 125_000L

@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.presetScope(
    handler: CoroutineExceptionHandler = CoroutineExceptionHandler { _, _ -> },
): CoroutineScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler) + handler)

private class PresetHarness(private val repeatEnabled: Boolean = false, private val apply: suspend () -> Unit = {}) {
    val alert = RetryAlertState()
    val errors = mutableListOf<String?>()
    val flight = mutableListOf<Boolean>()
    var snapshot: RadioDeviceSnapshot? = null
    var applies = 0
    var repeatApplies = 0
    var otherReads = 0

    fun request(preset: one.meshcore.shared.settings.RadioPreset): RadioPresetWrite = RadioPresetWrite(
        preset = preset,
        repeatEnabled = repeatEnabled,
        inFlight = false,
        snapshot = RadioDeviceSnapshot(
            frequencyKHz = 1L,
            bandwidthHz = ORIGINAL_BANDWIDTH_HZ,
            spreadingFactor = 7,
            codingRate = 5,
        ),
        onApplyPreset = {
            applies += 1
            apply()
        },
        onApplyRepeatPreset = {
            repeatApplies += 1
            apply()
        },
        otherParamsProvider = {
            otherReads += 1
            null
        },
        onInFlight = { flight += it },
        onError = { errors += it },
        onSnapshot = { snapshot = it },
        onOtherParams = {},
        settingsErrorText = { it.message ?: "settings failed" },
        retryAlert = alert,
        onMaxRetriesExceeded = {},
    )
}
