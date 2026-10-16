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
 * A failed status read is shown on the section. Cancellation clears loading and keeps the thrown
 * instance off the section. A timeout stays the timeout error. A thrown error leaves loading set.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NodeStatusRequestFaultTest {
    @Test
    fun aCancelledStatusRequestClearsLoadingWithoutASectionError() = runTest {
        val stopped = CancellationException("stopped")
        val thrown = mutableListOf<Throwable>()
        val harness = repeaterStatusHarness(thrown)
        val vm = repeaterStatusViewModel(harness)
        harness.nodeSession.statusError = stopped

        vm.requestStatus()
        runCurrent()

        assertFalse(vm.state.value.isLoadingStatus)
        assertNull(vm.state.value.statusSectionError)
        assertNull(vm.state.value.status)
        assertTrue(thrown.isEmpty())
        assertTrue(vm.statusJob!!.isCancelled)
        assertEquals(1, harness.nodeSession.requestStatusCalls.size)
    }

    @Test
    fun aStatusRequestFailureIsShownOnTheSection() = runTest {
        val thrown = mutableListOf<Throwable>()
        val harness = repeaterStatusHarness(thrown)
        val vm = repeaterStatusViewModel(harness)
        harness.nodeSession.statusError = StatusRequestFailure()

        vm.requestStatus()
        runCurrent()

        assertEquals(NodeSectionError.Message("radio"), vm.state.value.statusSectionError)
        assertFalse(vm.state.value.isLoadingStatus)
        assertNull(vm.state.value.status)
        assertTrue(thrown.isEmpty())
        assertEquals(1, harness.nodeSession.requestStatusCalls.size)
    }

    @Test
    fun aStatusRequestTimeoutStaysTheTimeoutError() = runTest {
        val thrown = mutableListOf<Throwable>()
        val harness = repeaterStatusHarness(thrown)
        val vm = repeaterStatusViewModel(harness)
        harness.nodeSession.statusError = RemoteNodeError.Timeout

        vm.requestStatus()
        runCurrent()

        assertIs<NodeSectionError.Timeout>(vm.state.value.statusSectionError)
        assertFalse(vm.state.value.isLoadingStatus)
        assertNull(vm.state.value.status)
        assertTrue(thrown.isEmpty())
    }

    @Test
    fun aStatusRequestErrorLeavesLoadingSetAndPropagates() = runTest {
        val probe = StatusRequestProbeError()
        val thrown = mutableListOf<Throwable>()
        val harness = repeaterStatusHarness(thrown)
        val vm = repeaterStatusViewModel(harness)
        harness.nodeSession.statusError = probe

        vm.requestStatus()
        runCurrent()

        assertTrue(vm.state.value.isLoadingStatus)
        assertNull(vm.state.value.statusSectionError)
        assertNull(vm.state.value.status)
        assertSame(probe, thrown.single())
        assertEquals(1, harness.nodeSession.requestStatusCalls.size)
    }
}

private class StatusRequestFailure : Exception("radio")

private class StatusRequestProbeError : Error("probe")
