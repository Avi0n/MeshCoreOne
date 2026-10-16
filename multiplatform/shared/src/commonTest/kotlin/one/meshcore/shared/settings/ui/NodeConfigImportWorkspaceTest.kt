package one.meshcore.shared.settings.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.settings.MeshCoreNodeConfig
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NodeConfigImportWorkspaceTest {
    @Test
    fun disposingAnOlderIdleMountCannotDiscardAnotherMountsConfirmedImport() = runTest {
        val workspace = NodeConfigImportWorkspace(this)
        val olderMount = NodeConfigImportState()
        val replacementMount = NodeConfigImportState()
        val olderPresentation = workspace.acquirePresentation(olderMount)
        val replacementPresentation = workspace.acquirePresentation(replacementMount)
        assertTrue(workspace.claim(olderMount))
        olderMount.isApplying = false
        olderMount.importComplete = true
        assertTrue(workspace.claim(replacementMount))
        workspace.release(olderPresentation)
        assertSame(replacementMount, workspace.confirmedState)
        assertTrue(replacementMount.isApplying)
        workspace.release(replacementPresentation)
        assertSame(replacementMount, workspace.confirmedState)
        replacementMount.isApplying = false
        workspace.release(workspace.acquirePresentation(replacementMount))
        assertNull(workspace.confirmedState)
    }

    @Test
    fun aSecondMountCannotClaimAnotherImportBeforeTheConfirmedOneFinishes() = runTest {
        val workspace = NodeConfigImportWorkspace(this)
        val originalMount = NodeConfigImportState()
        val replacementMount = NodeConfigImportState()
        assertTrue(workspace.claim(originalMount))
        assertFalse(workspace.claim(replacementMount))
        assertFalse(replacementMount.isApplying)
        assertSame(originalMount, workspace.confirmedState)
        originalMount.isApplying = false
        assertTrue(workspace.claim(replacementMount))
        assertSame(replacementMount, workspace.confirmedState)
    }

    @Test
    fun pendingSelectionSurvivesDisposalAndAdoptsItsCompletedResult() = runTest {
        val workspace = NodeConfigImportWorkspace(this)
        val state = NodeConfigImportState()
        val returned = CompletableDeferred<Unit>()
        val originalPresentation = workspace.acquirePresentation(state)
        assertTrue(workspace.claimPick(state))
        state.isParsing = true
        val job = launch(start = CoroutineStart.UNDISPATCHED) { returned.await() }
        workspace.installPickJob(state, job)
        workspace.release(originalPresentation)
        assertSame(state, workspace.pendingState)
        assertTrue(job.isActive)
        returned.complete(Unit)
        job.join()
        state.importedConfig = MeshCoreNodeConfig(name = "Selected")
        state.isParsing = false
        assertSame(state, workspace.pendingState)
        workspace.release(workspace.acquirePresentation(state))
        assertNull(workspace.pendingState)
    }

    @Test
    fun actualRouteDepartureRetiresTheOriginalPendingJob() = runTest {
        var current = true
        val workspace = NodeConfigImportWorkspace(this) { current }
        val state = NodeConfigImportState()
        val originalPresentation = workspace.acquirePresentation(state)
        val replacementPresentation = workspace.acquirePresentation(state)
        assertTrue(workspace.claimPick(state))
        state.isParsing = true
        val job = launch(start = CoroutineStart.UNDISPATCHED) { CompletableDeferred<Unit>().await() }
        workspace.installPickJob(state, job)
        current = false
        assertFalse(workspace.isPickCurrent(state))
        job.join()
        assertTrue(job.isCancelled)
        assertNull(workspace.pendingState)
        assertFalse(workspace.claimPick(NodeConfigImportState()))
        workspace.release(originalPresentation)
        workspace.release(replacementPresentation)
        assertNull(workspace.pendingState)
    }

    @Test
    fun disposingAnOldStateCannotCancelItsReplacementPick() = runTest {
        var current = true
        val workspace = NodeConfigImportWorkspace(this) { current }
        val retired = NodeConfigImportState()
        val retiredPresentation = workspace.acquirePresentation(retired)
        assertTrue(workspace.claimPick(retired))
        retired.isParsing = true
        current = false
        workspace.retireIfDestinationChanged()
        current = true
        val replacement = NodeConfigImportState()
        assertTrue(workspace.claimPick(replacement))
        replacement.isParsing = true
        val job = launch(start = CoroutineStart.UNDISPATCHED) { CompletableDeferred<Unit>().await() }
        workspace.installPickJob(replacement, job)
        workspace.release(retiredPresentation)
        assertTrue(job.isActive)
        assertSame(replacement, workspace.pendingState)
        assertFalse(workspace.isPickCurrent(retired))
        current = false
        workspace.retireIfDestinationChanged()
        job.join()
    }

    @Test
    fun leavingDuringConfirmedApplyDoesNotCancelOrReplaceIt() = runTest {
        var current = true
        val workspace = NodeConfigImportWorkspace(this) { current }
        val confirmed = NodeConfigImportState()
        val presentation = workspace.acquirePresentation(confirmed)
        assertTrue(workspace.claim(confirmed))
        current = false
        workspace.retireIfDestinationChanged()
        workspace.release(presentation)
        assertSame(confirmed, workspace.confirmedState)
        assertTrue(confirmed.isApplying)
        current = true
        assertFalse(workspace.claimPick(NodeConfigImportState()))
    }

    @Test
    fun eachRemainingPresentationKeepsTheSharedPreviewInEitherDisposalOrder() = runTest {
        for (disposeOriginalFirst in listOf(true, false)) {
            val workspace = NodeConfigImportWorkspace(this)
            val state = NodeConfigImportState()
            assertTrue(workspace.claimPick(state))
            state.importedConfig = MeshCoreNodeConfig(name = "Selected")
            val original = workspace.acquirePresentation(state)
            val replacement = workspace.acquirePresentation(state)
            val third = workspace.acquirePresentation(state)
            val first = if (disposeOriginalFirst) original else replacement
            val second = if (disposeOriginalFirst) replacement else original
            workspace.release(first)
            assertSame(state, workspace.pendingState)
            workspace.release(first)
            assertSame(state, workspace.pendingState)
            workspace.release(second)
            assertSame(state, workspace.pendingState)
            workspace.release(third)
            assertNull(workspace.pendingState)
        }
    }

    @Test
    fun anUnacquiredTokenCannotReleaseTheCurrentCompletedPreview() = runTest {
        val workspace = NodeConfigImportWorkspace(this)
        val state = NodeConfigImportState()
        assertTrue(workspace.claimPick(state))
        state.importedConfig = MeshCoreNodeConfig(name = "Selected")
        val presentation = workspace.acquirePresentation(state)
        workspace.release(NodeConfigImportWorkspace.Presentation(state))
        assertSame(state, workspace.pendingState)
        workspace.release(presentation)
        assertNull(workspace.pendingState)
    }
}
