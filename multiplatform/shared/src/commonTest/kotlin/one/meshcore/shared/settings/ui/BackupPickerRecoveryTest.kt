package one.meshcore.shared.settings.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.AppBackupError
import one.meshcore.shared.backup.BackupExportSuccessSummary
import one.meshcore.shared.backup.BackupImportResult
import one.meshcore.shared.backup.loadBackupFixture
import one.meshcore.shared.backup.previewPickedBackupJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class BackupPickerRecoveryTest {
    @Test
    fun selectedFileIsReadOnceAndOnlyPreparedForReview() = runTest {
        val workspace = BackupSettingsWorkspace(this)
        val json = loadBackupFixture("full-envelope.json")
        var reads = 0
        assertTrue(
            workspace.recoverPickedBackup {
                reads += 1
                json
            },
        )
        assertTrue(workspace.state.isParsing)
        assertFalse(
            workspace.recoverPickedBackup {
                reads += 1
                "unexpected"
            },
        )
        advanceUntilIdle()

        assertEquals(1, reads)
        assertEquals(json, workspace.state.importJson)
        assertEquals(previewPickedBackupJson(json), workspace.state.preview)
        assertFalse(workspace.state.isParsing)
        assertFalse(workspace.state.isImporting)
        assertNull(workspace.state.importResult)
        assertNull(workspace.state.failure)
    }

    @Test
    fun inlineCompletionStillClaimsTheSelection() = runTest(UnconfinedTestDispatcher()) {
        val workspace = BackupSettingsWorkspace(this)
        val json = loadBackupFixture("full-envelope.json")
        var reads = 0
        assertTrue(
            workspace.recoverPickedBackup {
                reads += 1
                json
            },
        )
        assertFalse(workspace.state.isParsing)
        assertNotNull(workspace.state.preview)
        assertFalse(
            workspace.recoverPickedBackup {
                reads += 1
                "unexpected"
            },
        )
        assertEquals(1, reads)
    }

    @Test
    fun existingWaitingParseKeepsItsSelection() = runTest(UnconfinedTestDispatcher()) {
        val workspace = BackupSettingsWorkspace(this)
        val picked = CompletableDeferred<String?>()
        val json = loadBackupFixture("full-envelope.json")
        workspace.state.beginParse({ picked.await() }, this)
        assertFalse(workspace.recoverPickedBackup { error("Cannot read another owner's selection") })
        assertTrue(workspace.state.isParsing)
        picked.complete(json)
        assertEquals(json, workspace.state.importJson)
        assertEquals(previewPickedBackupJson(json), workspace.state.preview)
    }

    @Test
    fun callbackBeforeTheOriginalAwaitCannotStartAnotherParse() = runTest(UnconfinedTestDispatcher()) {
        val workspace = BackupSettingsWorkspace(this)
        val picked = CompletableDeferred<String?>()
        val json = loadBackupFixture("full-envelope.json")
        var callbacks = 0
        workspace.state.beginParse({
            callbacks += 1
            assertFalse(workspace.recoverPickedBackup { error("Original picker already owns the result") })
            picked.complete(json)
            picked.await()
        }, this)
        assertEquals(1, callbacks)
        assertFalse(workspace.state.isParsing)
        assertEquals(json, workspace.state.importJson)
        assertEquals(previewPickedBackupJson(json), workspace.state.preview)
    }

    @Test
    fun cancelledOriginalPickerStaysIdleAndAllowsALaterSelection() = runTest(UnconfinedTestDispatcher()) {
        val workspace = BackupSettingsWorkspace(this)
        val picked = CompletableDeferred<String?>()
        workspace.state.beginParse({ picked.await() }, this)
        assertFalse(workspace.recoverPickedBackup { error("Pending picker owns cancellation") })
        picked.complete(null)
        assertFalse(workspace.state.isParsing)
        assertNull(workspace.state.preview)
        assertNull(workspace.state.failure)
        assertTrue(workspace.recoverPickedBackup { loadBackupFixture("full-envelope.json") })
        assertNotNull(workspace.state.preview)
    }

    @Test
    fun activeExportKeepsItsDocumentAndSummary() = runTest(UnconfinedTestDispatcher()) {
        val workspace = BackupSettingsWorkspace(this)
        val exported = CompletableDeferred<BackupExportSuccessSummary?>()
        val summary = BackupExportSuccessSummary(
            "owned.mc1backup",
            42,
            previewPickedBackupJson(loadBackupFixture("full-envelope.json")),
        )
        workspace.state.beginExport(this, { exported.await() }, {})
        assertFalse(workspace.recoverPickedBackup { error("Export still owns the workspace") })
        assertTrue(workspace.state.isExporting)
        exported.complete(summary)
        assertSame(summary, workspace.state.exportSummary)
        assertFalse(workspace.state.isExporting)
        assertNull(workspace.state.preview)
    }

    @Test
    fun existingPresentationsAndImportWorkCannotBeReplaced() = runTest {
        val preview = previewPickedBackupJson(loadBackupFixture("full-envelope.json"))
        val owners: List<BackupSettingsState.() -> Unit> = listOf(
            { showExportNotice = true },
            { exportSummary = BackupExportSuccessSummary("owned.mc1backup", 42, preview) },
            { failure = BackupFailure(IllegalStateException("retained")) },
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
            assertFalse(workspace.recoverPickedBackup { error("Owned presentation $index was read") })
            assertEquals(before, presentation(workspace.state), "Owner $index must remain unchanged")
        }
    }

    @Test
    fun malformedSelectionUsesTheExistingFailurePath() = runTest {
        val workspace = BackupSettingsWorkspace(this)
        assertTrue(workspace.recoverPickedBackup { "not JSON" })
        advanceUntilIdle()
        assertFalse(workspace.state.isParsing)
        assertNull(workspace.state.preview)
        assertIs<AppBackupError.InvalidFile>(workspace.state.failure?.cause)
        assertFalse(workspace.state.failure!!.isExport)
        assertNull(workspace.state.importResult)
    }

    @Test
    fun readFailureRetainsItsCauseAndDoesNotMerge() = runTest {
        val workspace = BackupSettingsWorkspace(this)
        val cause = IllegalStateException("document unavailable")
        assertTrue(workspace.recoverPickedBackup { throw cause })
        advanceUntilIdle()
        assertSame(cause, workspace.state.failure?.cause)
        assertFalse(workspace.state.isParsing)
        assertNull(workspace.state.preview)
        assertNull(workspace.state.importResult)
    }

    @Test
    fun cancelledReadPropagatesWithoutShowingAnImportError() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val workspace = BackupSettingsWorkspace(scope)
            val cancelled = CancellationException("read cancelled")
            assertTrue(workspace.recoverPickedBackup { throw cancelled })
            val child = scope.coroutineContext[Job]!!.children.single()
            var cause: Throwable? = null
            child.invokeOnCompletion { cause = it }
            advanceUntilIdle()
            assertSame(cancelled, cause)
            assertFalse(workspace.state.isParsing)
            assertNull(workspace.state.failure)
            assertNull(workspace.state.preview)
            assertNull(workspace.state.importResult)
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }

    private fun presentation(state: BackupSettingsState): List<Any?> = listOf(
        state.showExportNotice, state.exportSummary, state.failure, state.preview, state.importResult,
        state.importCancelled, state.isExporting, state.isImporting, state.isCancellingImport,
        state.isParsing, state.importJson,
    )
}
