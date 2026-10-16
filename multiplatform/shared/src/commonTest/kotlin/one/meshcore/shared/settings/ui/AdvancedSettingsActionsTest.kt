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
import one.meshcore.shared.battery.OCVPreset
import one.meshcore.shared.contact.AutoAddMode
import one.meshcore.shared.settings.NodesAutoAddDraft
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Reboot, battery-curve save, and nodes auto-add. A failure still reports the settings text, the
 * save-failed key, or the retry alert, and clears the spinner. Cancellation and an error propagate
 * and do not record a failure.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AdvancedSettingsActionsTest {
    @Test
    fun aRebootClearsTheSpinner() = runTest {
        val harness = RebootHarness()
        launchDeviceReboot(actionScope(), harness.request())
        advanceUntilIdle()

        assertEquals(1, harness.reboots)
        assertEquals(listOf<String?>(null), harness.errors)
        assertEquals(listOf(true, false), harness.rebooting)
    }

    @Test
    fun aRebootExceptionReportsTheSettingsText() = runTest {
        val harness = RebootHarness(body = { throw IllegalArgumentException("bad reboot") })
        launchDeviceReboot(actionScope(), harness.request())
        advanceUntilIdle()

        assertEquals(listOf(null, "bad reboot"), harness.errors)
        assertEquals(0, harness.reboots)
        assertEquals(listOf(true, false), harness.rebooting)
    }

    @Test
    fun aCancelledRebootPropagatesWithoutAFailure() = runTest {
        val cancelled = CancellationException("superseded")
        val harness = RebootHarness(body = { throw cancelled })
        val scope = actionScope()
        launchDeviceReboot(scope, harness.request())
        val child = scope.coroutineContext[Job]!!.children.single()
        child.invokeOnCompletion { harness.cause = it }
        advanceUntilIdle()

        assertSame(cancelled, harness.cause)
        assertEquals(listOf<String?>(null), harness.errors)
        assertEquals(0, harness.reboots)
        assertEquals(listOf(true, false), harness.rebooting)
    }

    @Test
    fun aRebootErrorPropagatesWithoutAFailure() = runTest {
        var thrown: Throwable? = null
        val probe = AdvancedSettingsProbeError()
        val harness = RebootHarness(body = { throw probe })
        launchDeviceReboot(actionScope { thrown = it }, harness.request())
        advanceUntilIdle()

        assertSame(probe, thrown)
        assertEquals(listOf<String?>(null), harness.errors)
        assertEquals(0, harness.reboots)
        assertEquals(listOf(true, false), harness.rebooting)
    }

    @Test
    fun aBatteryCurveSaveWritesThePreset() = runTest {
        val harness = BatteryHarness()
        launchBatteryCurveSave(actionScope(), harness.request())
        advanceUntilIdle()

        assertEquals(OCVPreset.LI_ION, harness.preset)
        assertEquals(CURVE, harness.values)
        assertEquals(listOf<String?>(null), harness.keys)
        assertEquals(listOf(true, false), harness.saving)
    }

    @Test
    fun aBatteryCurveExceptionUsesTheSaveFailedKey() = runTest {
        val harness = BatteryHarness(body = { throw IllegalStateException("disk") })
        launchBatteryCurveSave(actionScope(), harness.request())
        advanceUntilIdle()

        assertEquals(listOf(null, BATTERY_CURVE_SAVE_FAILED_KEY), harness.keys)
        assertEquals(null, harness.preset)
        assertEquals(listOf(true, false), harness.saving)
    }

    @Test
    fun aCancelledBatteryCurveSavePropagatesWithoutAFailure() = runTest {
        val cancelled = CancellationException("superseded")
        val harness = BatteryHarness(body = { throw cancelled })
        val scope = actionScope()
        launchBatteryCurveSave(scope, harness.request())
        val child = scope.coroutineContext[Job]!!.children.single()
        child.invokeOnCompletion { harness.cause = it }
        advanceUntilIdle()

        assertSame(cancelled, harness.cause)
        assertEquals(listOf<String?>(null), harness.keys)
        assertEquals(null, harness.preset)
        assertEquals(listOf(true, false), harness.saving)
    }

    @Test
    fun aBatteryCurveErrorPropagatesWithoutAFailure() = runTest {
        var thrown: Throwable? = null
        val probe = AdvancedSettingsProbeError()
        val harness = BatteryHarness(body = { throw probe })
        launchBatteryCurveSave(actionScope { thrown = it }, harness.request())
        advanceUntilIdle()

        assertSame(probe, thrown)
        assertEquals(listOf<String?>(null), harness.keys)
        assertEquals(listOf(true, false), harness.saving)
    }

    @Test
    fun anAutoAddApplyWritesTheDraft() = runTest {
        val harness = AutoAddHarness()
        launchNodesAutoAdd(actionScope(), harness.request())
        advanceUntilIdle()

        assertEquals(DRAFT, harness.applied)
        assertEquals(listOf(false, true), harness.appliedFlags)
        assertEquals(listOf<String?>(null), harness.errors)
        assertFalse(harness.alert.isPresented)
        assertEquals(listOf(true, false), harness.applying)
    }

    @Test
    fun anAutoAddExceptionReportsTheSettingsText() = runTest {
        val harness = AutoAddHarness(body = { throw IllegalArgumentException("bad policy") })
        launchNodesAutoAdd(actionScope(), harness.request())
        advanceUntilIdle()

        assertEquals(listOf(null, "bad policy"), harness.errors)
        assertEquals(listOf(false), harness.appliedFlags)
        assertFalse(harness.alert.isPresented)
        assertEquals(null, harness.applied)
        assertEquals(listOf(true, false), harness.applying)
    }

    @Test
    fun aRetryableAutoAddFailureShowsTheAlert() = runTest {
        val harness = AutoAddHarness(body = { throw IllegalStateException("No radio connected") })
        launchNodesAutoAdd(actionScope(), harness.request())
        advanceUntilIdle()

        assertTrue(harness.alert.isPresented)
        assertEquals("No radio connected", harness.alert.message)
        assertEquals(listOf<String?>(null), harness.errors)
        assertEquals(listOf(false), harness.appliedFlags)
        assertEquals(listOf(true, false), harness.applying)
    }

    @Test
    fun aCancelledAutoAddPropagatesWithoutAFailure() = runTest {
        val cancelled = CancellationException("superseded")
        val harness = AutoAddHarness(body = { throw cancelled })
        val scope = actionScope()
        launchNodesAutoAdd(scope, harness.request())
        val child = scope.coroutineContext[Job]!!.children.single()
        child.invokeOnCompletion { harness.cause = it }
        advanceUntilIdle()

        assertSame(cancelled, harness.cause)
        assertEquals(listOf<String?>(null), harness.errors)
        assertEquals(listOf(false), harness.appliedFlags)
        assertFalse(harness.alert.isPresented)
        assertEquals(listOf(true, false), harness.applying)
    }

    @Test
    fun anAutoAddErrorPropagatesWithoutAFailure() = runTest {
        var thrown: Throwable? = null
        val probe = AdvancedSettingsProbeError()
        val harness = AutoAddHarness(body = { throw probe })
        launchNodesAutoAdd(actionScope { thrown = it }, harness.request())
        advanceUntilIdle()

        assertSame(probe, thrown)
        assertEquals(listOf<String?>(null), harness.errors)
        assertEquals(listOf(false), harness.appliedFlags)
        assertFalse(harness.alert.isPresented)
        assertEquals(listOf(true, false), harness.applying)
    }
}

private class AdvancedSettingsProbeError : Error("probe")

private val CURVE = listOf(3400, 3300)
private val DRAFT = NodesAutoAddDraft(mode = AutoAddMode.MANUAL)

@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.actionScope(onError: (Throwable) -> Unit = {}): CoroutineScope = CoroutineScope(
    SupervisorJob() + StandardTestDispatcher(testScheduler) + CoroutineExceptionHandler { _, error -> onError(error) },
)

private class RebootHarness(private val body: suspend () -> Unit = {}) {
    val errors = mutableListOf<String?>()
    val rebooting = mutableListOf<Boolean>()
    var reboots = 0
    var cause: Throwable? = null

    fun request() = DeviceRebootRequest(
        onReboot = {
            body()
            reboots += 1
        },
        onRebooting = { rebooting += it },
        onError = { errors += it },
        settingsErrorText = { it.message ?: "settings failed" },
    )
}

private class BatteryHarness(private val body: suspend () -> Unit = {}) {
    val keys = mutableListOf<String?>()
    val saving = mutableListOf<Boolean>()
    var preset: OCVPreset? = null
    var values: List<Int>? = null
    var cause: Throwable? = null

    fun request() = BatteryCurveSave(
        preset = OCVPreset.LI_ION,
        values = CURVE,
        onSave = { savedPreset, savedValues ->
            body()
            preset = savedPreset
            values = savedValues
        },
        onSaving = { saving += it },
        onErrorKey = { keys += it },
    )
}

private class AutoAddHarness(private val body: suspend () -> Unit = {}) {
    val alert = RetryAlertState()
    val errors = mutableListOf<String?>()
    val applying = mutableListOf<Boolean>()
    val appliedFlags = mutableListOf<Boolean>()
    var applied: NodesAutoAddDraft? = null
    var cause: Throwable? = null

    fun request() = NodesAutoAddApply(
        draft = DRAFT,
        onApply = { draft ->
            body()
            applied = draft
        },
        onApplying = { applying += it },
        onError = { errors += it },
        onApplied = { appliedFlags += it },
        settingsErrorText = { it.message ?: "settings failed" },
        retryAlert = alert,
        onMaxRetriesExceeded = {},
    )
}
