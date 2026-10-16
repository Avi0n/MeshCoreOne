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
import one.meshcore.shared.backup.BackupImportPreview
import one.meshcore.shared.backup.BackupImportResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Committing a backup import. A failure still reports the error and finishes. Cancellation still
 * marks the import cancelled and then propagates. An error propagates and does not record a failure.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackupImportLaunchTest {
    @Test
    fun aCommitWritesTheImportResult() = runTest {
        val harness = ImportHarness(importScope())
        launchBackupImport(preview(), harness.state(), harness.actions())
        advanceUntilIdle()

        assertEquals(listOf("start", "imported", "finished"), harness.events)
        assertEquals(IMPORTED, harness.imported)
        assertEquals(IMPORT_JSON, harness.seenJson)
        assertNull(harness.failed)
        assertNull(harness.cause)
    }

    @Test
    fun aConnectedRadioSkipsTheImport() = runTest {
        val harness = ImportHarness(importScope())
        launchBackupImport(preview(), harness.state(connected = true), harness.actions())
        advanceUntilIdle()

        assertEquals(listOf("skip"), harness.events)
        assertNull(harness.job)
    }

    @Test
    fun anInvalidManifestSkipsTheImport() = runTest {
        val harness = ImportHarness(importScope())
        launchBackupImport(preview(valid = false), harness.state(), harness.actions())
        advanceUntilIdle()

        assertEquals(listOf("skip"), harness.events)
        assertNull(harness.job)
    }

    @Test
    fun anImportExceptionReportsTheFailure() = runTest {
        val harness = ImportHarness(importScope()) { throw IllegalArgumentException("bad backup") }
        launchBackupImport(preview(), harness.state(), harness.actions())
        harness.job!!.invokeOnCompletion { harness.cause = it }
        advanceUntilIdle()

        assertEquals(listOf("start", "failed", "finished"), harness.events)
        assertEquals("bad backup", harness.failed?.message)
        assertNull(harness.imported)
        assertNull(harness.cause)
    }

    @Test
    fun aCancelledImportMarksCancelledAndPropagates() = runTest {
        val cancelled = CancellationException("superseded")
        val harness = ImportHarness(importScope()) { throw cancelled }
        launchBackupImport(preview(), harness.state(), harness.actions())
        harness.job!!.invokeOnCompletion { harness.cause = it }
        advanceUntilIdle()

        assertSame(cancelled, harness.cause)
        assertEquals(listOf("start", "cancelled", "finished"), harness.events)
        assertNull(harness.failed)
        assertNull(harness.imported)
    }

    @Test
    fun anImportErrorPropagatesWithoutAFailure() = runTest {
        val probe = BackupImportProbeError()
        val harness = ImportHarness(importScope()) { throw probe }
        launchBackupImport(preview(), harness.state(), harness.actions())
        harness.job!!.invokeOnCompletion { harness.cause = it }
        advanceUntilIdle()

        assertSame(probe, harness.cause)
        assertEquals(listOf("start", "finished"), harness.events)
        assertNull(harness.failed)
        assertNull(harness.imported)
    }
}

private class BackupImportProbeError : Error("probe")

private const val IMPORT_JSON = "backup-json"

private val IMPORTED = BackupImportResult(devicesInserted = 1)

private fun preview(valid: Boolean = true) = BackupImportPreview(
    version = 1,
    exportDateEpochSeconds = 0.0,
    appVersion = "1",
    appBuild = "1",
    deviceCount = 0,
    contactCount = 0,
    channelCount = 0,
    messageCount = 0,
    messageRepeatCount = 0,
    reactionCount = 0,
    roomMessageCount = 0,
    remoteNodeSessionCount = 0,
    savedTracePathCount = 0,
    blockedChannelSenderCount = 0,
    nodeStatusSnapshotCount = 0,
    discoveredNodeCount = 0,
    manifestValid = valid,
)

@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.importScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler) + CoroutineExceptionHandler { _, _ -> })

private class ImportHarness(
    private val scope: CoroutineScope,
    private val body: suspend (String) -> BackupImportResult? = { IMPORTED },
) {
    val events = mutableListOf<String>()
    var imported: BackupImportResult? = null
    var failed: Exception? = null
    var job: Job? = null
    var cause: Throwable? = null
    var seenJson: String? = null

    fun state(connected: Boolean = false) = BackupImportOverlayState(
        phase = BackupImportSheetPhase.PREVIEW,
        preview = preview(),
        importResult = null,
        failure = null,
        isCancellingImport = false,
        isExporting = false,
        isParsing = false,
        isRadioConnected = connected,
        importJson = IMPORT_JSON,
    )

    fun actions(): BackupImportOverlayActions = BackupImportOverlayActions(
        onDismiss = {},
        onSkip = { events += "skip" },
        onStart = { events += "start" },
        onImported = { result ->
            events += "imported"
            imported = result
        },
        onCancelled = { events += "cancelled" },
        onFailed = { error ->
            events += "failed"
            failed = error
        },
        onFinished = { events += "finished" },
        onJob = { started -> job = started },
        onCancel = {},
        commitScope = scope,
        onImportConfirmed = { json ->
            seenJson = json
            body(json)
        },
    )
}
