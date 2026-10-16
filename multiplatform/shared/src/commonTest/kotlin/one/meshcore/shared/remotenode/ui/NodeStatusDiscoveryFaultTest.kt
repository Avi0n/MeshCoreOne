package one.meshcore.shared.remotenode.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.RoomPermissionLevel
import one.meshcore.shared.remotenode.FakeNodeSession
import one.meshcore.shared.remotenode.InMemoryNodePasswordStore
import one.meshcore.shared.remotenode.InMemoryRemoteNodeStore
import one.meshcore.shared.remotenode.NodeCliCoordinator
import one.meshcore.shared.remotenode.RemoteNodeService
import one.meshcore.shared.remotenode.RepeaterAdminService
import one.meshcore.shared.remotenode.testSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A cancelled neighbor-discovery command clears the countdown and keeps the cancellation that left the
 * command. A command fault still stops the countdown in the status suite.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NodeStatusDiscoveryFaultTest {
    @Test
    fun aCancelledDiscoverCommandKeepsTheCancellation() = runTest(UnconfinedTestDispatcher()) {
        val harness = DiscoveryHarness(backgroundScope)
        val vm = discoveryViewModel(harness)
        runCurrent()
        val stopped = CancellationException("superseded")
        harness.nodeSession.sendCommandError = stopped

        vm.startDiscovery()
        val job = vm.discoverJob
        assertNotNull(job)
        var cause: Throwable? = null
        job.invokeOnCompletion { cause = it }
        runCurrent()

        assertSame(stopped, cause)
        assertFalse(vm.state.value.isDiscovering)
        assertEquals(0, vm.state.value.discoverySecondsRemaining)
        assertNull(vm.state.value.neighborsSectionError)
    }

    @Test
    fun aCancelledNeighborPollKeepsTheOriginalCause() = runTest(UnconfinedTestDispatcher()) {
        val harness = DiscoveryHarness(backgroundScope)
        val vm = discoveryViewModel(harness)
        runCurrent()
        vm.startDiscovery()
        runCurrent()
        harness.nodeSession.emitCliResponse("OK")
        runCurrent()
        val stopped = CancellationException("superseded")
        harness.nodeSession.neighboursError = stopped
        val job = vm.discoverJob
        assertNotNull(job)
        var cause: Throwable? = null
        job.invokeOnCompletion { cause = it }

        val tick = NodeStatusViewModel.DISCOVERY_POLL_INTERVAL_SECONDS *
            NodeStatusViewModel.DISCOVERY_TICK_MS
        advanceTimeBy(tick)
        runCurrent()

        // The neighbour read sits in withTimeout, which copies a cancellation and keeps the original.
        val thrown = cause
        assertTrue(thrown is CancellationException)
        assertTrue(thrown !is TimeoutCancellationException)
        assertSame(stopped, thrown?.cause)
        assertNull(vm.state.value.neighborsSectionError)
    }
}

private class DiscoveryHarness(scope: CoroutineScope) {
    val nodeSession = FakeNodeSession()
    val store = InMemoryRemoteNodeStore()
    val passwordStore = InMemoryNodePasswordStore()
    val remote = RemoteNodeService(nodeSession, store, passwordStore, scope)
    val cli = NodeCliCoordinator(nodeSession, store, passwordStore, scope)
    val admin = RepeaterAdminService(remote, cli, nodeSession, store, passwordStore)
}

private suspend fun TestScope.discoveryViewModel(harness: DiscoveryHarness): NodeStatusViewModel {
    val session = testSession(isConnected = true, permissionLevel = RoomPermissionLevel.ADMIN)
    harness.store.saveSession(session)
    return NodeStatusViewModel(
        scope = backgroundScope,
        session = session,
        dependencies = NodeStatusViewModel.Dependencies(
            repeaterAdminService = { harness.admin },
        ),
    )
}
