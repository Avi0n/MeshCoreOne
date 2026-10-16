package one.meshcore.shared.settings.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.BackupExportSuccessSummary
import one.meshcore.shared.backup.BackupImportResult
import one.meshcore.shared.backup.loadBackupFixture
import one.meshcore.shared.backup.previewPickedBackupJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class BackupSaveRecoveryTest {
    @Test
    fun admittedSaveRunsOneExportAndPublishesItsActualSummary() = runTest {
        val workspace = BackupSettingsWorkspace(this)
        val expected = sampleSummary("selected.mc1backup")
        var exports = 0
        val admitted = workspace.recoverCreatedBackup {
            exports += 1
            expected
        }
        assertTrue(admitted)
        assertTrue(workspace.state.isExporting)
        assertFalse(workspace.recoverCreatedBackup { error("Already claimed") })
        advanceUntilIdle()

        assertEquals(1, exports)
        assertSame(expected, workspace.state.exportSummary)
        assertFalse(workspace.state.isExporting)
        assertNull(workspace.state.failure)
        assertNull(workspace.state.preview)
        assertNull(workspace.state.importResult)
    }

    @Test
    fun inlineCompletionStillClaimsTheReturnedSave() = runTest(UnconfinedTestDispatcher()) {
        val workspace = BackupSettingsWorkspace(this)
        val expected = sampleSummary("selected.mc1backup")
        assertTrue(workspace.recoverCreatedBackup { expected })
        assertFalse(workspace.state.isExporting)
        assertSame(expected, workspace.state.exportSummary)
        assertFalse(workspace.recoverCreatedBackup { error("Completed save still owns its summary") })
    }

    @Test
    fun existingExportKeepsItsPreparedResultAndCompletion() = runTest(UnconfinedTestDispatcher()) {
        val workspace = BackupSettingsWorkspace(this)
        val saved = CompletableDeferred<BackupExportSuccessSummary?>()
        val expected = sampleSummary("original.mc1backup")
        var completions = 0
        workspace.state.beginExport(this, { saved.await() }) { completions += 1 }
        assertFalse(workspace.recoverCreatedBackup { error("Original export owns the Save result") })
        assertTrue(workspace.state.isExporting)
        saved.complete(expected)
        assertSame(expected, workspace.state.exportSummary)
        assertFalse(workspace.state.isExporting)
        assertEquals(1, completions)
    }

    @Test
    fun callbackBeforeTheOriginalAwaitCannotStartAnotherExport() = runTest(UnconfinedTestDispatcher()) {
        val workspace = BackupSettingsWorkspace(this)
        val saved = CompletableDeferred<BackupExportSuccessSummary?>()
        val expected = sampleSummary("original.mc1backup")
        var callbacks = 0
        workspace.state.beginExport(this, {
            callbacks += 1
            assertFalse(workspace.recoverCreatedBackup { error("Original picker already owns Save") })
            saved.complete(expected)
            saved.await()
        }, {})
        assertEquals(1, callbacks)
        assertSame(expected, workspace.state.exportSummary)
        assertFalse(workspace.state.isExporting)
    }

    @Test
    fun pendingImportKeepsItsSelectionAndPreview() = runTest(UnconfinedTestDispatcher()) {
        val workspace = BackupSettingsWorkspace(this)
        val picked = CompletableDeferred<String?>()
        val json = loadBackupFixture("full-envelope.json")
        workspace.state.beginParse({ picked.await() }, this)
        assertFalse(workspace.recoverCreatedBackup { error("The pending import cannot be replaced") })
        assertTrue(workspace.state.isParsing)
        picked.complete(json)
        assertEquals(json, workspace.state.importJson)
        assertEquals(previewPickedBackupJson(json), workspace.state.preview)
        assertNull(workspace.state.exportSummary)
    }

    @Test
    fun cancelledOriginalSaveStaysIdleAndAllowsALaterExport() = runTest(UnconfinedTestDispatcher()) {
        val workspace = BackupSettingsWorkspace(this)
        val saved = CompletableDeferred<BackupExportSuccessSummary?>()
        workspace.state.beginExport(this, { saved.await() }, {})
        assertFalse(workspace.recoverCreatedBackup { error("Pending Save owns cancellation") })
        saved.complete(null)
        assertFalse(workspace.state.isExporting)
        assertNull(workspace.state.exportSummary)
        assertNull(workspace.state.failure)
        val expected = sampleSummary("later.mc1backup")
        assertTrue(workspace.recoverCreatedBackup { expected })
        assertSame(expected, workspace.state.exportSummary)
    }

    @Test
    fun ownedPresentationsCannotBeReplacedByAnotherExport() = runTest {
        val preview = previewPickedBackupJson(loadBackupFixture("full-envelope.json"))
        val owners: List<BackupSettingsState.() -> Unit> = listOf(
            { showExportNotice = true },
            { exportSummary = sampleSummary("owned.mc1backup") },
            { failure = BackupFailure(IllegalStateException("retained"), isExport = true) },
            { this.preview = preview },
            { importResult = BackupImportResult(messagesInserted = 2) },
            { importCancelled = true },
            { isImporting = true },
            {
                isImporting = true
                isCancellingImport = true
            },
        )
        owners.forEachIndexed { index, own ->
            val workspace = BackupSettingsWorkspace(this)
            workspace.state.apply(own)
            val before = presentation(workspace.state)
            assertFalse(workspace.recoverCreatedBackup { error("Owned presentation $index was replaced") })
            assertEquals(before, presentation(workspace.state), "Owner $index must remain unchanged")
        }
    }

    @Test
    fun nullProducerResultUsesTheExistingCancelledSavePolicy() = runTest {
        val workspace = BackupSettingsWorkspace(this)
        assertTrue(workspace.recoverCreatedBackup { null })
        advanceUntilIdle()
        assertFalse(workspace.state.isExporting)
        assertNull(workspace.state.exportSummary)
        assertNull(workspace.state.failure)
    }

    @Test
    fun producerFailureKeepsItsCauseAndExportErrorKind() = runTest {
        val workspace = BackupSettingsWorkspace(this)
        val cause = IllegalStateException("document unavailable")
        assertTrue(workspace.recoverCreatedBackup { throw cause })
        advanceUntilIdle()
        assertFalse(workspace.state.isExporting)
        assertNull(workspace.state.exportSummary)
        assertSame(cause, workspace.state.failure?.cause)
        assertTrue(workspace.state.failure!!.isExport)
    }

    @Test
    fun producerCancellationPropagatesWithoutAnExportError() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val workspace = BackupSettingsWorkspace(scope)
            val cancelled = CancellationException("write cancelled")
            assertTrue(workspace.recoverCreatedBackup { throw cancelled })
            val child = scope.coroutineContext[Job]!!.children.single()
            var cause: Throwable? = null
            child.invokeOnCompletion { cause = it }
            advanceUntilIdle()
            assertSame(cancelled, cause)
            assertTrue(scope.coroutineContext[Job]!!.isActive)
            assertFalse(workspace.state.isExporting)
            assertNull(workspace.state.exportSummary)
            assertNull(workspace.state.failure)
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }

    @Test
    fun cancellingThePendingExportChildClearsBusyWithoutCancellingItsParent() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val workspace = BackupSettingsWorkspace(scope)
            val started = CompletableDeferred<Unit>()
            val admitted = workspace.recoverCreatedBackup {
                started.complete(Unit)
                awaitCancellation()
            }
            assertTrue(admitted)
            advanceUntilIdle()
            assertTrue(started.isCompleted)
            assertTrue(workspace.state.isExporting)
            val child = scope.coroutineContext[Job]!!.children.single()
            val cancelled = CancellationException("retired export")
            var cause: Throwable? = null
            child.invokeOnCompletion { cause = it }
            child.cancel(cancelled)
            advanceUntilIdle()
            assertSame(cancelled, cause)
            assertTrue(child.isCompleted)
            assertTrue(scope.coroutineContext[Job]!!.isActive)
            assertFalse(workspace.state.isExporting)
            assertNull(workspace.state.failure)
            assertNull(workspace.state.exportSummary)
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }

    private fun sampleSummary(filename: String): BackupExportSuccessSummary = BackupExportSuccessSummary(
        filename,
        42,
        previewPickedBackupJson(loadBackupFixture("full-envelope.json")),
    )

    private fun presentation(state: BackupSettingsState): List<Any?> = listOf(
        state.showExportNotice, state.exportSummary, state.failure, state.preview, state.importResult,
        state.importCancelled, state.isExporting, state.isImporting, state.isCancellingImport,
        state.isParsing, state.importJson,
    )
}
