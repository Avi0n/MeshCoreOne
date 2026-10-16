package one.meshcore.shared.settings.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.settings.MeshCoreNodeConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NodeConfigColdSelectionTest {
    @Test
    fun anUnownedReturnedDocumentReservesAndReadsExactlyOnce() = runTest {
        var current = false
        var reads = 0
        val workspace = NodeConfigImportWorkspace(this) { current }
        assertTrue(
            workspace.recoverPickedConfig(
                readSelected = {
                    reads += 1
                    "selected"
                },
                showReview = { current = true },
            ),
        )
        val state = assertNotNull(workspace.pendingState)
        assertTrue(state.isParsing)
        assertEquals(0, reads)
        val reader = assertNotNull(workspace.takeRecoveredReader(state))
        assertNull(workspace.takeRecoveredReader(state))
        assertEquals("selected", reader())
        assertEquals(1, reads)
    }

    @Test
    fun aReentrantIntermediateRouteCannotRetireTheAdmittedDocument() = runTest {
        var current = false
        val workspace = NodeConfigImportWorkspace(this) { current }
        assertTrue(
            workspace.recoverPickedConfig(
                readSelected = { "selected" },
                showReview = {
                    workspace.retireIfDestinationChanged()
                    current = true
                },
            ),
        )
        assertNotNull(workspace.takeRecoveredReader(assertNotNull(workspace.pendingState)))
    }

    @Test
    fun aFailedOrUnmatchedRouteDropsTheReader() = runTest {
        val workspace = NodeConfigImportWorkspace(this) { false }
        assertFalse(workspace.recoverPickedConfig({ error("Abandoned reader ran") }) {})
        assertNull(workspace.pendingState)
        val failure = IllegalStateException("routing failed")
        var thrown: Throwable? = null
        try {
            workspace.recoverPickedConfig({ error("Abandoned reader ran") }) { throw failure }
        } catch (error: IllegalStateException) {
            thrown = error
        }
        assertSame(failure, thrown)
        assertNull(workspace.pendingState)
    }

    @Test
    fun aDuplicateCannotReplaceTheStagedReaderOrReroute() = runTest {
        val workspace = NodeConfigImportWorkspace(this)
        assertTrue(workspace.recoverPickedConfig({ "first" }) {})
        val state = assertNotNull(workspace.pendingState)
        assertFalse(workspace.recoverPickedConfig({ "second" }) { error("Duplicate rerouted") })
        assertSame(state, workspace.pendingState)
        assertEquals("first", assertNotNull(workspace.takeRecoveredReader(state))())
    }

    @Test
    fun existingPendingPreviewErrorOrConfirmedStateRejectsRecovery() = runTest {
        for (kind in Ownership.entries) {
            val workspace = NodeConfigImportWorkspace(this)
            val state = NodeConfigImportState()
            when (kind) {
                Ownership.PENDING -> {
                    assertTrue(workspace.claimPick(state))
                    state.isParsing = true
                }
                Ownership.PREVIEW -> {
                    assertTrue(workspace.claimPick(state))
                    state.importedConfig = MeshCoreNodeConfig(name = "Selected")
                }
                Ownership.ERROR -> {
                    assertTrue(workspace.claimPick(state))
                    state.errorMessage = "visible"
                }
                Ownership.CONFIRMED -> assertTrue(workspace.claim(state))
            }
            assertFalse(workspace.recoverPickedConfig({ error("Busy reader ran") }) { error("Busy rerouted") })
        }
    }

    @Test
    fun leavingBeforeConsumptionDiscardsTheDocumentAndAllowsAFreshPick() = runTest {
        var current = true
        val workspace = NodeConfigImportWorkspace(this) { current }
        assertTrue(workspace.recoverPickedConfig({ error("Abandoned reader ran") }) {})
        val retired = assertNotNull(workspace.pendingState)
        current = false
        workspace.retireIfDestinationChanged()
        assertNull(workspace.pendingState)
        assertNull(workspace.takeRecoveredReader(retired))
        current = true
        val fresh = NodeConfigImportState()
        assertTrue(workspace.claimPick(fresh))
        assertNull(workspace.takeRecoveredReader(fresh))
    }

    @Test
    fun leavingDuringAReadCancelsTheInstalledProcessChild() = runTest {
        var current = true
        val workspace = NodeConfigImportWorkspace(this) { current }
        val held = CompletableDeferred<String>()
        assertTrue(workspace.recoverPickedConfig({ held.await() }) {})
        val state = assertNotNull(workspace.pendingState)
        val reader = assertNotNull(workspace.takeRecoveredReader(state))
        val job = launch(start = CoroutineStart.UNDISPATCHED) { reader() }
        workspace.installPickJob(state, job)
        current = false
        workspace.retireIfDestinationChanged()
        job.join()
        assertTrue(job.isCancelled)
        assertNull(workspace.pendingState)
    }

    @Test
    fun aStagedReaderSurvivesPresentationReplacementButCompletedPreviewIsReleased() = runTest {
        val workspace = NodeConfigImportWorkspace(this)
        assertTrue(workspace.recoverPickedConfig({ "selected" }) {})
        val state = assertNotNull(workspace.pendingState)
        val outgoing = workspace.acquirePresentation(state)
        workspace.release(outgoing)
        assertSame(state, workspace.pendingState)
        assertNotNull(workspace.takeRecoveredReader(state))
        state.importedConfig = MeshCoreNodeConfig(name = "Selected")
        state.isParsing = false
        workspace.release(workspace.acquirePresentation(state))
        assertNull(workspace.pendingState)
    }

    @Test
    fun theSharedLauncherUsesTheReturnedReaderOnceWithoutOpeningAnotherPicker() = runTest {
        val workspace = NodeConfigImportWorkspace(this)
        val returned = CompletableDeferred<String>()
        var reads = 0
        assertTrue(
            workspace.recoverPickedConfig(
                readSelected = {
                    reads += 1
                    returned.await()
                },
                showReview = {},
            ),
        )
        val state = assertNotNull(workspace.pendingState)
        val probe = ImportProbe()
        val request = probe.pickRequest(
            parsing = true,
            pick = { error("Unexpected fresh picker") },
            isCurrent = { workspace.isPickCurrent(state) },
            installJob = { workspace.installPickJob(state, it) },
        )
        launchRecoveredConfigImportPick(workspace, state, request)
        launchRecoveredConfigImportPick(workspace, state, request)
        runCurrent()
        assertEquals(1, reads)
        assertTrue(probe.configs.isEmpty())
        returned.complete("""{"name":"Recovered"}""")
        runCurrent()
        assertEquals("Recovered", probe.configs.single()?.name)
        assertEquals(listOf(true, false), probe.parsing)
        assertTrue(probe.showConfirm.isEmpty())
        assertTrue(probe.applying.isEmpty())
    }

    @Test
    fun theSharedLauncherReportsAReadFailureWithoutApplying() = runTest {
        val workspace = NodeConfigImportWorkspace(this)
        assertTrue(workspace.recoverPickedConfig({ throw IllegalStateException("private detail") }) {})
        val state = assertNotNull(workspace.pendingState)
        val probe = ImportProbe()
        launchRecoveredConfigImportPick(
            workspace,
            state,
            probe.pickRequest(
                parsing = true,
                pick = { error("Unexpected fresh picker") },
                configErrorText = { "mapped read failure" },
                isCurrent = { workspace.isPickCurrent(state) },
                installJob = { workspace.installPickJob(state, it) },
            ),
        )
        runCurrent()
        assertEquals("mapped read failure", probe.errors.last())
        assertTrue(probe.configs.isEmpty())
        assertTrue(probe.applying.isEmpty())
        assertEquals(listOf(true, false), probe.parsing)
    }

    private enum class Ownership { PENDING, PREVIEW, ERROR, CONFIRMED }
}
