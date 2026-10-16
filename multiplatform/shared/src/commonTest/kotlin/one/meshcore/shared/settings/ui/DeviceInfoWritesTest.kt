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
import one.meshcore.shared.meshcore.BridgeErrorCode
import one.meshcore.shared.meshcore.MeshBridgeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Saving a node name or importing a private key. A failed name still reports the settings text or
 * the retry alert. A failed import still reports the mapped text. Cancellation and an error
 * propagate and do not record a failure.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceInfoWritesTest {
    @Test
    fun aNameSaveWritesAndClearsTheSpinner() = runTest {
        val harness = NodeNameHarness()
        launchNodeNameSave(deviceInfoScope(), harness.nameRequest("Ada"))
        advanceUntilIdle()

        assertEquals(listOf("Ada"), harness.saved)
        assertEquals(listOf<String?>(null), harness.errors)
        assertFalse(harness.alert.isPresented)
        assertEquals(listOf(true, false), harness.saving)
    }

    @Test
    fun anEmptyNameReturnsBeforeCallbacks() = runTest {
        val harness = NodeNameHarness()
        launchNodeNameSave(deviceInfoScope(), harness.nameRequest(""))
        advanceUntilIdle()

        assertTrue(harness.saved.isEmpty())
        assertTrue(harness.errors.isEmpty())
        assertTrue(harness.saving.isEmpty())
        assertFalse(harness.alert.isPresented)
    }

    @Test
    fun anInFlightNameSaveReturnsBeforeCallbacks() = runTest {
        val harness = NodeNameHarness()
        launchNodeNameSave(deviceInfoScope(), harness.nameRequest("Ada", inFlight = true))
        advanceUntilIdle()

        assertTrue(harness.saved.isEmpty())
        assertTrue(harness.errors.isEmpty())
        assertTrue(harness.saving.isEmpty())
    }

    @Test
    fun aNameExceptionReportsTheSettingsText() = runTest {
        val harness = NodeNameHarness(save = { throw IllegalArgumentException("bad name") })
        launchNodeNameSave(deviceInfoScope(), harness.nameRequest("Ada"))
        advanceUntilIdle()

        assertEquals(listOf(null, "bad name"), harness.errors)
        assertTrue(harness.saved.isEmpty())
        assertFalse(harness.alert.isPresented)
        assertEquals(listOf(true, false), harness.saving)
    }

    @Test
    fun aRetryableNameFailureShowsTheAlert() = runTest {
        val harness = NodeNameHarness(save = { throw IllegalStateException("No radio connected") })
        launchNodeNameSave(deviceInfoScope(), harness.nameRequest("Ada"))
        advanceUntilIdle()

        assertTrue(harness.alert.isPresented)
        assertEquals("No radio connected", harness.alert.message)
        assertEquals(listOf<String?>(null), harness.errors)
        assertTrue(harness.saved.isEmpty())
        assertEquals(listOf(true, false), harness.saving)
    }

    @Test
    fun aCancelledNameSavePropagatesWithoutAFailure() = runTest {
        val cancelled = CancellationException("superseded")
        val harness = NodeNameHarness(save = { throw cancelled })
        val scope = deviceInfoScope()
        launchNodeNameSave(scope, harness.nameRequest("Ada"))
        val child = scope.coroutineContext[Job]!!.children.single()
        child.invokeOnCompletion { harness.cause = it }
        advanceUntilIdle()

        assertSame(cancelled, harness.cause)
        assertEquals(listOf<String?>(null), harness.errors)
        assertTrue(harness.saved.isEmpty())
        assertFalse(harness.alert.isPresented)
        assertEquals(listOf(true, false), harness.saving)
    }

    @Test
    fun aNameSaveErrorPropagatesWithoutAFailure() = runTest {
        var thrown: Throwable? = null
        val probe = DeviceInfoProbeError()
        val harness = NodeNameHarness(save = { throw probe })
        launchNodeNameSave(deviceInfoScope { thrown = it }, harness.nameRequest("Ada"))
        advanceUntilIdle()

        assertSame(probe, thrown)
        assertEquals(listOf<String?>(null), harness.errors)
        assertTrue(harness.saved.isEmpty())
        assertFalse(harness.alert.isPresented)
        assertEquals(listOf(true, false), harness.saving)
    }

    @Test
    fun anImportWritesTheKeyAndClearsTheDraft() = runTest {
        val harness = PrivateKeyHarness()
        launchPrivateKeyImport(deviceInfoScope(), harness.importRequest())
        advanceUntilIdle()

        assertTrue(harness.imported.single().contentEquals(KEY))
        assertEquals(1, harness.cleared)
        assertEquals(1, harness.haptics)
        assertEquals(1, harness.successes)
        assertEquals(listOf<String?>(null), harness.errors)
        assertEquals(listOf(true, false), harness.importing)
        assertEquals(listOf(true, false), harness.busy)
    }

    @Test
    fun aDisabledImportReportsThatMessage() = runTest {
        val harness = PrivateKeyHarness(
            write = { throw MeshBridgeException(BridgeErrorCode.FEATURE_DISABLED, "off") },
        )
        launchPrivateKeyImport(deviceInfoScope(), harness.importRequest())
        advanceUntilIdle()

        assertEquals(listOf(null, FEATURE), harness.errors)
        assertEquals(0, harness.cleared)
        assertEquals(0, harness.haptics)
        assertEquals(0, harness.successes)
        assertEquals(listOf(true, false), harness.importing)
        assertEquals(listOf(true, false), harness.busy)
    }

    @Test
    fun aRejectedImportReportsThatMessage() = runTest {
        val harness = PrivateKeyHarness(
            write = { throw MeshBridgeException(BridgeErrorCode.DEVICE_ERROR, "no") },
        )
        launchPrivateKeyImport(deviceInfoScope(), harness.importRequest())
        advanceUntilIdle()

        assertEquals(listOf(null, REJECTED), harness.errors)
        assertEquals(0, harness.successes)
        assertEquals(listOf(true, false), harness.busy)
    }

    @Test
    fun anUnexpectedImportExceptionUsesTheSessionText() = runTest {
        val harness = PrivateKeyHarness(write = { throw IllegalStateException("timed out") })
        launchPrivateKeyImport(deviceInfoScope(), harness.importRequest())
        advanceUntilIdle()

        assertEquals(listOf(null, "timed out"), harness.errors)
        assertEquals(0, harness.haptics)
        assertEquals(listOf(true, false), harness.importing)
    }

    @Test
    fun aCancelledImportPropagatesWithoutAFailure() = runTest {
        val cancelled = CancellationException("superseded")
        val harness = PrivateKeyHarness(write = { throw cancelled })
        val scope = deviceInfoScope()
        launchPrivateKeyImport(scope, harness.importRequest())
        val child = scope.coroutineContext[Job]!!.children.single()
        child.invokeOnCompletion { harness.cause = it }
        advanceUntilIdle()

        assertSame(cancelled, harness.cause)
        assertEquals(listOf<String?>(null), harness.errors)
        assertEquals(0, harness.cleared)
        assertEquals(0, harness.haptics)
        assertEquals(0, harness.successes)
        assertEquals(listOf(true, false), harness.importing)
        assertEquals(listOf(true, false), harness.busy)
    }

    @Test
    fun anImportErrorPropagatesWithoutAFailure() = runTest {
        var thrown: Throwable? = null
        val probe = DeviceInfoProbeError()
        val harness = PrivateKeyHarness(write = { throw probe })
        launchPrivateKeyImport(deviceInfoScope { thrown = it }, harness.importRequest())
        advanceUntilIdle()

        assertSame(probe, thrown)
        assertEquals(listOf<String?>(null), harness.errors)
        assertEquals(0, harness.cleared)
        assertEquals(0, harness.haptics)
        assertEquals(0, harness.successes)
        assertEquals(listOf(true, false), harness.importing)
        assertEquals(listOf(true, false), harness.busy)
    }
}

private class DeviceInfoProbeError : Error("probe")

private val KEY = byteArrayOf(1, 2, 3)

private const val FEATURE = "import not supported"

private const val REJECTED = "device rejected"

@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.deviceInfoScope(onError: (Throwable) -> Unit = {}): CoroutineScope = CoroutineScope(
    SupervisorJob() + StandardTestDispatcher(testScheduler) + CoroutineExceptionHandler { _, error -> onError(error) },
)

private class NodeNameHarness(private val save: suspend (String) -> Unit = {}) {
    val alert = RetryAlertState()
    val errors = mutableListOf<String?>()
    val saving = mutableListOf<Boolean>()
    val saved = mutableListOf<String>()
    var cause: Throwable? = null

    fun nameRequest(name: String, inFlight: Boolean = false) = NodeNameSave(
        name = name,
        inFlight = inFlight,
        onSave = { value ->
            save(value)
            saved += value
        },
        onSaving = { saving += it },
        onError = { errors += it },
        settingsErrorText = { it.message ?: "settings failed" },
        retryAlert = alert,
        onMaxRetriesExceeded = {},
    )
}

private class PrivateKeyHarness(private val write: suspend (ByteArray) -> Unit = {}) {
    val errors = mutableListOf<String?>()
    val importing = mutableListOf<Boolean>()
    val busy = mutableListOf<Boolean>()
    val imported = mutableListOf<ByteArray>()
    var cleared = 0
    var haptics = 0
    var successes = 0
    var cause: Throwable? = null

    fun importRequest() = PrivateKeyImport(
        key = KEY,
        featureDisabledMessage = FEATURE,
        rejectedMessage = REJECTED,
        sessionText = { error -> error.message ?: "session" },
        onError = { errors += it },
        onBusyChange = { busy += it },
        onImportingChange = { importing += it },
        onImport = { key ->
            write(key)
            imported += key
        },
        onCleared = { cleared += 1 },
        onHapticSuccess = { haptics += 1 },
        onSuccess = { successes += 1 },
    )
}
