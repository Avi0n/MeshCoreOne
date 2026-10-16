package one.meshcore.shared.settings.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import one.meshcore.shared.settings.ImportPreview
import one.meshcore.shared.settings.ImportProgress
import one.meshcore.shared.settings.ImportStep
import one.meshcore.shared.settings.NodeConfigDeviceDiff
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Importing a node configuration. A failure still reports the mapped text and clears its spinner.
 * Cancellation during apply still says whether a write landed, then propagates. An error propagates
 * and does not record a failure.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NodeConfigImportLaunchTest {
    @Test
    fun aFinishedImportResetsAfterTheSuccessDelay() = runTest {
        val harness = ImportProbe()
        var refreshed = 0
        launchConfigImportApply(
            importScope(),
            harness.applyRequest(
                importConfig = { onProgress ->
                    onProgress(ImportProgress(ImportStep.NodeName, current = 1, total = 2))
                },
                onImportApplied = { refreshed += 1 },
            ),
        )
        advanceUntilIdle()

        assertEquals(1, refreshed)
        assertEquals(listOf(true, false), harness.applying)
        assertEquals(listOf(0f, 0.5f), harness.progress)
        assertEquals(listOf("name"), harness.steps)
        assertEquals(listOf(false, true), harness.didWrite)
        assertEquals(listOf(false, true), harness.complete)
        assertEquals(1, harness.resets)
        assertEquals(listOf<String?>(null), harness.errors)
        assertNull(harness.cause)
    }

    @Test
    fun anOlderSuccessTimerDoesNotResetANewerCompletedImport() = runTest {
        val harness = ImportProbe()
        val scope = importScope()
        var imported = 0
        val request = harness.applyRequest(importConfig = { imported += 1 })
        launchConfigImportApply(scope, request.copy(successResetMs = FIRST_RESET_MS))
        runCurrent()
        advanceTimeBy(SECOND_START_MS)
        launchConfigImportApply(scope, request.copy(successResetMs = SECOND_RESET_MS))
        val newerJob = harness.job
        runCurrent()
        assertEquals(2, imported)
        assertEquals(listOf(false, true, false, true), harness.complete)
        advanceTimeBy(FIRST_RESET_MS - SECOND_START_MS)
        runCurrent()
        assertEquals(0, harness.resets)
        assertSame(newerJob, harness.job)
        assertFalse(newerJob!!.isCompleted)
        advanceUntilIdle()
        assertEquals(1, harness.resets)
        assertTrue(newerJob.isCompleted)
    }

    @Test
    fun anotherApplyCannotStartWhileTheFirstImporterIsPending() = runTest {
        val harness = ImportProbe()
        val scope = importScope()
        val finished = CompletableDeferred<Unit>()
        var imports = 0
        var refreshes = 0
        val request = harness.applyRequest(
            importConfig = {
                imports += 1
                finished.await()
            },
            onImportApplied = { refreshes += 1 },
        )
        launchConfigImportApply(scope, request)
        runCurrent()
        val originalJob = harness.job
        launchConfigImportApply(scope, request)
        runCurrent()
        assertEquals(1, imports)
        assertEquals(0, refreshes)
        assertSame(originalJob, harness.job)
        assertEquals(listOf(true), harness.applying)
        finished.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, refreshes)
        assertEquals(1, harness.resets)
        assertEquals(listOf(true, false), harness.applying)
    }

    @Test
    fun immediateCompletionInstallsItsJobBeforeTheZeroDelayReset() = runTest {
        val harness = ImportProbe()
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        var imports = 0
        launchConfigImportApply(scope, harness.applyRequest(importConfig = { imports += 1 }))
        assertEquals(1, imports)
        assertEquals(1, harness.resets)
        assertTrue(harness.job!!.isCompleted)
        assertEquals(listOf(false, true), harness.complete)
    }

    @Test
    fun anApplyExceptionBeforeAWriteReportsTheMappedText() = runTest {
        val harness = ImportProbe()
        launchConfigImportApply(
            importScope(),
            harness.applyRequest(
                importConfig = { throw IllegalArgumentException("bad plan") },
                configErrorText = { error -> error.message },
            ),
        )
        harness.job!!.invokeOnCompletion { harness.cause = it }
        advanceUntilIdle()

        assertEquals(listOf(null, "bad plan"), harness.errors)
        assertEquals(listOf(true, false), harness.applying)
        assertEquals(listOf(false), harness.didWrite)
        assertEquals(listOf(false), harness.complete)
        assertEquals(0, harness.resets)
        assertNull(harness.cause)
    }

    @Test
    fun anApplyExceptionAfterAWriteUsesThePartialTemplate() = runTest {
        val harness = ImportProbe()
        launchConfigImportApply(
            importScope(),
            harness.applyRequest(
                importConfig = { onProgress ->
                    onProgress(ImportProgress(ImportStep.RadioParameters, current = 1, total = 1))
                    throw IllegalArgumentException("bad plan")
                },
            ),
        )
        advanceUntilIdle()

        assertEquals(listOf(null, "Stopped after a write: $UNMAPPED"), harness.errors)
        assertEquals(listOf("radio"), harness.steps)
        assertEquals(listOf(1f), harness.progress.drop(1))
        assertEquals(listOf(false, true), harness.didWrite)
        assertEquals(listOf(true, false), harness.applying)
    }

    @Test
    fun aCancelledApplyBeforeAWriteReportsTheCancelTextAndPropagates() = runTest {
        val harness = ImportProbe()
        val scope = importScope()
        val cancelled = CancellationException("superseded")
        launchConfigImportApply(
            scope,
            harness.applyRequest(importConfig = { throw cancelled }),
        )
        harness.job!!.invokeOnCompletion { harness.cause = it }
        advanceUntilIdle()

        assertSame(cancelled, harness.cause)
        assertEquals(listOf(null, CANCELLED), harness.errors)
        assertEquals(listOf(true, false), harness.applying)
        assertEquals(listOf(false), harness.didWrite)
        assertEquals(0, harness.resets)
    }

    @Test
    fun aCancelledApplyAfterAWriteReportsThePartialCancelText() = runTest {
        val cancelled = CancellationException("superseded")
        val harness = ImportProbe()
        val scope = importScope()
        launchConfigImportApply(
            scope,
            harness.applyRequest(
                importConfig = { onProgress ->
                    onProgress(ImportProgress(ImportStep.TxPower, current = 1, total = 4))
                    throw cancelled
                },
            ),
        )
        harness.job!!.invokeOnCompletion { harness.cause = it }
        advanceUntilIdle()

        assertSame(cancelled, harness.cause)
        assertEquals(listOf(null, CANCELLED_PARTIAL), harness.errors)
        assertEquals(listOf(0.25f), harness.progress.drop(1))
        assertEquals(listOf(false, true), harness.didWrite)
    }

    @Test
    fun anApplyErrorPropagatesAndLeavesTheSpinnerUp() = runTest {
        var thrown: Throwable? = null
        val probe = NodeConfigImportProbeError()
        val harness = ImportProbe()
        launchConfigImportApply(
            importScope { thrown = it },
            harness.applyRequest(importConfig = { throw probe }),
        )
        advanceUntilIdle()

        assertSame(probe, thrown)
        assertEquals(listOf<String?>(null), harness.errors)
        assertEquals(listOf(true), harness.applying)
        assertEquals(0, harness.resets)
    }

    @Test
    fun aPickedConfigDecodesAndLoadsTheDiff() = runTest {
        val harness = ImportProbe()
        val diff = NodeConfigDeviceDiff(name = "Live", radio = null, position = null)
        launchConfigImportPick(
            importScope(),
            harness.pickRequest(pick = { CONFIG_JSON }, load = { diff }),
        )
        advanceUntilIdle()

        assertEquals("Relay", harness.configs.single()?.name)
        assertEquals(true, harness.sections.single().nodeIdentity)
        assertEquals(false, harness.sections.single().channels)
        assertEquals(listOf<NodeConfigDeviceDiff?>(diff), harness.diffs)
        assertEquals(listOf<String?>(null, null), harness.errors)
        assertEquals(listOf(true, false), harness.parsing)
    }

    @Test
    fun aDismissedPickerClearsTheSpinnerWithoutAConfig() = runTest {
        val harness = ImportProbe()
        launchConfigImportPick(importScope(), harness.pickRequest(pick = { null }))
        advanceUntilIdle()

        assertTrue(harness.configs.isEmpty())
        assertEquals(listOf<String?>(null), harness.errors)
        assertEquals(listOf(true, false, false), harness.parsing)
    }

    @Test
    fun aBadConfigFileReportsTheMappedText() = runTest {
        val harness = ImportProbe()
        launchConfigImportPick(
            importScope(),
            harness.pickRequest(
                pick = { "not-json" },
                configErrorText = { error ->
                    if (error is SerializationException) "not a node config" else null
                },
            ),
        )
        advanceUntilIdle()

        assertEquals(listOf(null, "not a node config"), harness.errors)
        assertTrue(harness.configs.isEmpty())
        assertEquals(listOf(true, false), harness.parsing)
    }

    @Test
    fun aDiffLoadFailureStillKeepsTheDecodedConfig() = runTest {
        val harness = ImportProbe()
        launchConfigImportPick(
            importScope(),
            harness.pickRequest(
                pick = { CONFIG_JSON },
                load = { throw IllegalStateException("diff failed") },
            ),
        )
        advanceUntilIdle()

        assertEquals("Relay", harness.configs.single()?.name)
        assertTrue(harness.diffs.isEmpty())
        assertEquals(listOf<String?>(null, null), harness.errors)
        assertEquals(listOf(true, false), harness.parsing)
    }

    @Test
    fun aCancelledDiffLoadPropagatesAndClearsTheSpinner() = runTest {
        val cancelled = CancellationException("diff cancelled")
        val harness = ImportProbe()
        val scope = importScope()
        launchConfigImportPick(scope, harness.pickRequest(load = { throw cancelled }))
        val child = scope.coroutineContext[Job]!!.children.single()
        child.invokeOnCompletion { harness.cause = it }
        advanceUntilIdle()

        assertSame(cancelled, harness.cause)
        assertEquals("Relay", harness.configs.single()?.name)
        assertEquals(listOf<String?>(null, null), harness.errors)
        assertEquals(listOf(true, false), harness.parsing)
    }

    @Test
    fun aPickAlreadyRunningDoesNotStart() = runTest {
        val harness = ImportProbe()
        launchConfigImportPick(importScope(), harness.pickRequest(parsing = true))
        advanceUntilIdle()

        assertTrue(harness.parsing.isEmpty())
        assertTrue(harness.errors.isEmpty())
        assertNull(harness.job)
    }

    @Test
    fun aCancelledPickPropagatesWithoutAFailure() = runTest {
        val cancelled = CancellationException("superseded")
        val harness = ImportProbe()
        val scope = importScope()
        launchConfigImportPick(
            scope,
            harness.pickRequest(pick = { throw cancelled }),
        )
        val child = scope.coroutineContext[Job]!!.children.single()
        child.invokeOnCompletion { harness.cause = it }
        advanceUntilIdle()

        assertSame(cancelled, harness.cause)
        assertEquals(listOf<String?>(null), harness.errors)
        assertTrue(harness.configs.isEmpty())
        assertEquals(listOf(true, false), harness.parsing)
    }

    @Test
    fun aPickErrorPropagatesWithoutAFailure() = runTest {
        var thrown: Throwable? = null
        val probe = NodeConfigImportProbeError()
        val harness = ImportProbe()
        launchConfigImportPick(
            importScope { thrown = it },
            harness.pickRequest(pick = { throw probe }),
        )
        advanceUntilIdle()

        assertSame(probe, thrown)
        assertEquals(listOf<String?>(null), harness.errors)
        assertTrue(harness.configs.isEmpty())
        assertEquals(listOf(true, false), harness.parsing)
    }

    @Test
    fun aPreviewShowsConfirmationAndTheOverwriteFlag() = runTest {
        val harness = ImportProbe()
        launchConfigImportPreview(
            importScope(),
            harness.previewRequest(preview = { ImportPreview(channelsOverwriteExisting = true) }),
        )
        advanceUntilIdle()

        assertEquals(listOf(true), harness.overwrite)
        assertEquals(listOf(true), harness.showConfirm)
        assertEquals(listOf(true, false), harness.preparing)
        assertEquals(listOf<String?>(null), harness.errors)
    }

    @Test
    fun aPreviewAlreadyRunningDoesNotStart() = runTest {
        val harness = ImportProbe()
        launchConfigImportPreview(importScope(), harness.previewRequest(inFlight = true))
        advanceUntilIdle()

        assertTrue(harness.preparing.isEmpty())
        assertTrue(harness.showConfirm.isEmpty())
        assertNull(harness.job)
    }

    @Test
    fun aPreviewExceptionReportsTheMappedText() = runTest {
        val harness = ImportProbe()
        launchConfigImportPreview(
            importScope(),
            harness.previewRequest(preview = { throw IllegalArgumentException("bad preview") }),
        )
        advanceUntilIdle()

        assertEquals(listOf(null, UNMAPPED), harness.errors)
        assertTrue(harness.showConfirm.isEmpty())
        assertEquals(listOf(true, false), harness.preparing)
    }

    @Test
    fun aCancelledPreviewPropagatesWithoutAFailure() = runTest {
        val cancelled = CancellationException("superseded")
        val harness = ImportProbe()
        val scope = importScope()
        launchConfigImportPreview(
            scope,
            harness.previewRequest(preview = { throw cancelled }),
        )
        val child = scope.coroutineContext[Job]!!.children.single()
        child.invokeOnCompletion { harness.cause = it }
        advanceUntilIdle()

        assertSame(cancelled, harness.cause)
        assertEquals(listOf<String?>(null), harness.errors)
        assertTrue(harness.showConfirm.isEmpty())
        assertEquals(listOf(true, false), harness.preparing)
    }

    @Test
    fun aPreviewErrorPropagatesWithoutAFailure() = runTest {
        var thrown: Throwable? = null
        val probe = NodeConfigImportProbeError()
        val harness = ImportProbe()
        launchConfigImportPreview(
            importScope { thrown = it },
            harness.previewRequest(preview = { throw probe }),
        )
        advanceUntilIdle()

        assertSame(probe, thrown)
        assertEquals(listOf<String?>(null), harness.errors)
        assertTrue(harness.showConfirm.isEmpty())
        assertEquals(listOf(true, false), harness.preparing)
    }
}

private class NodeConfigImportProbeError : Error("probe")

private const val CONFIG_JSON = """{"name":"Relay"}"""
private const val UNMAPPED = "Could not import the configuration."
private const val CANCELLED = "Import cancelled."
private const val CANCELLED_PARTIAL = "Import cancelled after a write."
private const val PARTIAL_TEMPLATE = "Stopped after a write: %s"
private const val SUCCESS_RESET_MS = 0L
private const val FIRST_RESET_MS = 100L
private const val SECOND_START_MS = 25L
private const val SECOND_RESET_MS = 200L

@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.importScope(onError: (Throwable) -> Unit = {}): CoroutineScope = CoroutineScope(
    SupervisorJob() + StandardTestDispatcher(testScheduler) + CoroutineExceptionHandler { _, error -> onError(error) },
)

internal class ImportProbe {
    val errors = mutableListOf<String?>()
    val applying = mutableListOf<Boolean>()
    val parsing = mutableListOf<Boolean>()
    val preparing = mutableListOf<Boolean>()
    val progress = mutableListOf<Float>()
    val steps = mutableListOf<String>()
    val didWrite = mutableListOf<Boolean>()
    val complete = mutableListOf<Boolean>()
    val configs = mutableListOf<one.meshcore.shared.settings.MeshCoreNodeConfig?>()
    val sections = mutableListOf<one.meshcore.shared.settings.ConfigSections>()
    val diffs = mutableListOf<NodeConfigDeviceDiff?>()
    val overwrite = mutableListOf<Boolean>()
    val showConfirm = mutableListOf<Boolean>()
    var resets = 0
    var job: Job? = null
    private var applyingNow = false
    var cause: Throwable? = null

    private fun bindings() = NodeConfigImportBindings(
        isApplying = { applyingNow },
        claimApply = { !applyingNow },
        importJob = { job },
        isConfirmationCurrent = { true },
        setConfirmationCurrent = {},
        isParsing = { false },
        isPreparingConfirmation = { false },
        setApplying = {
            applyingNow = it
            applying += it
        },
        setParsing = { parsing += it },
        setPreparingConfirmation = { preparing += it },
        setErrorMessage = { errors += it },
        setImportComplete = { complete += it },
        setDidApplyAnyWrite = { didWrite += it },
        setApplyProgress = { progress += it },
        setApplyStepDescription = { steps += it },
        setImportJob = { job = it },
        setShowConfirmation = { showConfirm += it },
        setChannelsWouldOverwrite = { overwrite += it },
        setImportedConfig = { configs += it },
        setSections = { sections += it },
        setDeviceDiff = { diffs += it },
        resetToFileSelection = { resets += 1 },
    )

    fun applyRequest(
        importConfig: suspend (onProgress: (ImportProgress) -> Unit) -> Unit = {},
        onImportApplied: suspend () -> Unit = {},
        configErrorText: (Throwable) -> String? = { null },
    ) = ConfigImportApplyRequest(
        importConfig = importConfig,
        onImportApplied = onImportApplied,
        describeStep = { step ->
            when (step) {
                ImportStep.NodeName -> "name"
                ImportStep.RadioParameters -> "radio"
                else -> "other"
            }
        },
        configErrorText = configErrorText,
        unmappedConfigError = UNMAPPED,
        cancelledMsg = CANCELLED,
        cancelledPartialMsg = CANCELLED_PARTIAL,
        failedPartialTemplate = PARTIAL_TEMPLATE,
        successResetMs = SUCCESS_RESET_MS,
        bindings = bindings(),
    )

    fun pickRequest(
        parsing: Boolean = false,
        pick: suspend () -> String? = { CONFIG_JSON },
        load: suspend () -> NodeConfigDeviceDiff = {
            NodeConfigDeviceDiff(name = null, radio = null, position = null)
        },
        configErrorText: (Throwable) -> String? = { null },
        claim: () -> Boolean = { true },
        isCurrent: () -> Boolean = { true },
        installJob: (Job) -> Unit = {},
    ) = ConfigImportPickRequest(
        parsing = parsing,
        pickJsonFile = pick,
        loadDeviceDiff = load,
        claim = claim,
        isCurrent = isCurrent,
        installJob = installJob,
        configErrorText = configErrorText,
        unmappedConfigError = UNMAPPED,
        bindings = bindings(),
    )

    fun previewRequest(
        inFlight: Boolean = false,
        preview: suspend () -> ImportPreview = { ImportPreview(channelsOverwriteExisting = false) },
        configErrorText: (Throwable) -> String? = { null },
    ) = ConfigImportPreviewRequest(
        inFlight = inFlight,
        preview = preview,
        configErrorText = configErrorText,
        unmappedConfigError = UNMAPPED,
        bindings = bindings(),
    )
}
