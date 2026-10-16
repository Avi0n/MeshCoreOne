package one.meshcore.shared.remotenode.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.remotenode.RemoteNodeError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A failed neighbour read is shown on the section. Cancellation clears loading and keeps the thrown
 * instance off the section. A timeout stays the timeout error. A thrown error leaves loading set.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NodeStatusNeighborsFaultTest {
    @Test
    fun aCancelledNeighborRequestClearsLoadingWithoutASectionError() = runTest {
        val thrown = mutableListOf<Throwable>()
        val harness = repeaterStatusHarness(thrown)
        val vm = repeaterStatusViewModel(harness)
        harness.nodeSession.neighboursError = CancellationException("stopped")

        vm.requestNeighbors()
        runCurrent()

        assertFalse(vm.state.value.isLoadingNeighbors)
        assertNull(vm.state.value.neighborsSectionError)
        assertTrue(vm.state.value.neighbors.isEmpty())
        assertTrue(thrown.isEmpty())
        assertTrue(vm.neighborsJob!!.isCancelled)
        assertEquals(1, harness.nodeSession.requestNeighboursCalls.size)
    }

    @Test
    fun aNeighborRequestFailureIsShownOnTheSection() = runTest {
        val thrown = mutableListOf<Throwable>()
        val harness = repeaterStatusHarness(thrown)
        val vm = repeaterStatusViewModel(harness)
        harness.nodeSession.neighboursError = NeighborsRequestFailure()

        vm.requestNeighbors()
        runCurrent()

        assertEquals(NodeSectionError.Message("radio"), vm.state.value.neighborsSectionError)
        assertFalse(vm.state.value.isLoadingNeighbors)
        assertTrue(vm.state.value.neighbors.isEmpty())
        assertTrue(thrown.isEmpty())
        assertEquals(1, harness.nodeSession.requestNeighboursCalls.size)
    }

    @Test
    fun aNeighborRequestTimeoutStaysTheTimeoutError() = runTest {
        val thrown = mutableListOf<Throwable>()
        val harness = repeaterStatusHarness(thrown)
        val vm = repeaterStatusViewModel(harness)
        harness.nodeSession.neighboursError = RemoteNodeError.Timeout

        vm.requestNeighbors()
        runCurrent()

        assertIs<NodeSectionError.Timeout>(vm.state.value.neighborsSectionError)
        assertFalse(vm.state.value.isLoadingNeighbors)
        assertTrue(vm.state.value.neighbors.isEmpty())
        assertTrue(thrown.isEmpty())
        assertEquals(1, harness.nodeSession.requestNeighboursCalls.size)
    }

    @Test
    fun aNeighborRequestErrorLeavesLoadingSetAndPropagates() = runTest {
        val probe = NeighborsRequestProbeError()
        val thrown = mutableListOf<Throwable>()
        val harness = repeaterStatusHarness(thrown)
        val vm = repeaterStatusViewModel(harness)
        harness.nodeSession.neighboursError = probe

        vm.requestNeighbors()
        runCurrent()

        assertTrue(vm.state.value.isLoadingNeighbors)
        assertNull(vm.state.value.neighborsSectionError)
        assertTrue(vm.state.value.neighbors.isEmpty())
        assertSame(probe, thrown.single())
        assertEquals(1, harness.nodeSession.requestNeighboursCalls.size)
    }
}

private class NeighborsRequestFailure : Exception("radio")

private class NeighborsRequestProbeError : Error("probe")
