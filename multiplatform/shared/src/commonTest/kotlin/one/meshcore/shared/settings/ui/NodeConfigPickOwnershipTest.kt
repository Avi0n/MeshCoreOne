package one.meshcore.shared.settings.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.settings.NodeConfigDeviceDiff
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NodeConfigPickOwnershipTest {
    @Test
    fun rejectedClaimDoesNotReadOrMutateTheScreen() = runTest {
        val probe = ImportProbe()
        var reads = 0
        var installed: Job? = null
        launchConfigImportPick(
            this,
            probe.pickRequest(
                pick = {
                    reads += 1
                    CONFIG
                },
                claim = { false },
                installJob = { installed = it },
            ),
        )
        runCurrent()
        assertEquals(0, reads)
        assertNull(installed)
        assertTrue(probe.parsing.isEmpty())
        assertTrue(probe.errors.isEmpty())
        assertTrue(probe.configs.isEmpty())
    }

    @Test
    fun synchronousSelectionSeesItsInstalledJobBeforePublication() = runTest {
        val owner = SupervisorJob()
        val scope = CoroutineScope(owner + UnconfinedTestDispatcher(testScheduler))
        val probe = ImportProbe()
        var installed: Job? = null
        try {
            launchConfigImportPick(
                scope,
                probe.pickRequest(
                    pick = {
                        assertSame(installed, currentCoroutineContext()[Job])
                        CONFIG
                    },
                    installJob = { installed = it },
                ),
            )
            assertTrue(assertNotNull(installed).isCompleted)
            assertEquals("Selected", probe.configs.single()?.name)
            assertEquals(listOf(true, false), probe.parsing)
        } finally {
            owner.cancelAndJoin()
        }
    }

    @Test
    fun retiredSelectionDoesNotPublishConfigOrClearReplacementParsing() = runTest {
        val probe = ImportProbe()
        val selected = CompletableDeferred<String?>()
        var current = true
        var installed: Job? = null
        launchConfigImportPick(
            this,
            probe.pickRequest(pick = { selected.await() }, isCurrent = { current }, installJob = { installed = it }),
        )
        runCurrent()
        current = false
        selected.complete(CONFIG)
        assertNotNull(installed).join()
        assertTrue(probe.configs.isEmpty())
        assertTrue(probe.sections.isEmpty())
        assertTrue(probe.diffs.isEmpty())
        assertEquals(listOf<String?>(null), probe.errors)
        assertEquals(listOf(true), probe.parsing)
    }

    @Test
    fun retiredOrdinaryFailureDoesNotPublishErrorOrClearParsing() = runTest {
        val probe = ImportProbe()
        val returned = CompletableDeferred<Unit>()
        var current = true
        var installed: Job? = null
        launchConfigImportPick(
            this,
            probe.pickRequest(
                pick = {
                    returned.await()
                    error("old read failure")
                },
                isCurrent = { current },
                installJob = { installed = it },
            ),
        )
        runCurrent()
        current = false
        returned.complete(Unit)
        assertNotNull(installed).join()
        assertEquals(listOf<String?>(null), probe.errors)
        assertEquals(listOf(true), probe.parsing)
        assertTrue(probe.configs.isEmpty())
    }

    @Test
    fun retiredDiffDoesNotChangeTheReplacement() = runTest {
        val probe = ImportProbe()
        val returned = CompletableDeferred<NodeConfigDeviceDiff>()
        var current = true
        var installed: Job? = null
        launchConfigImportPick(
            this,
            probe.pickRequest(load = { returned.await() }, isCurrent = { current }, installJob = { installed = it }),
        )
        runCurrent()
        assertEquals(1, probe.configs.size)
        current = false
        returned.complete(NodeConfigDeviceDiff("Retired", null, null))
        assertNotNull(installed).join()
        assertTrue(probe.diffs.isEmpty())
        assertEquals(listOf(true), probe.parsing)
    }

    @Test
    fun liveRouteChangeRetiresThePickWithoutCollectorAssistance() = runTest {
        var destinationCurrent = true
        val workspace = NodeConfigImportWorkspace(this) { destinationCurrent }
        val state = NodeConfigImportState()
        val probe = ImportProbe()
        val selected = CompletableDeferred<String?>()
        var installed: Job? = null
        launchConfigImportPick(
            this,
            probe.pickRequest(
                pick = { selected.await() },
                claim = { workspace.claimPick(state) },
                isCurrent = { workspace.isPickCurrent(state) },
                installJob = {
                    installed = it
                    workspace.installPickJob(state, it)
                },
            ),
        )
        runCurrent()
        destinationCurrent = false
        selected.complete(CONFIG)
        assertNotNull(installed).join()
        assertNull(workspace.pendingState)
        assertTrue(probe.configs.isEmpty())
        assertTrue(probe.diffs.isEmpty())
        assertEquals(listOf<String?>(null), probe.errors)
        assertEquals(listOf(true), probe.parsing)
    }

    private companion object {
        const val CONFIG = """{"name":"Selected"}"""
    }
}
