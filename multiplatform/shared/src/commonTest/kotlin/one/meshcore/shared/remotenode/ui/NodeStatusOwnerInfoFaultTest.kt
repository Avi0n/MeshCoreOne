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
 * A failed owner-info read is shown on the section. Cancellation clears loading and keeps the thrown
 * instance off the section. A timeout stays the timeout error. A thrown error leaves loading set.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NodeStatusOwnerInfoFaultTest {
    @Test
    fun aCancelledOwnerInfoRequestClearsLoadingWithoutASectionError() = runTest {
        val thrown = mutableListOf<Throwable>()
        val harness = repeaterStatusHarness(thrown)
        val vm = repeaterStatusViewModel(harness)
        harness.nodeSession.ownerInfoError = CancellationException("stopped")

        vm.requestOwnerInfo()
        runCurrent()

        assertFalse(vm.state.value.isLoadingOwnerInfo)
        assertNull(vm.state.value.ownerInfoSectionError)
        assertNull(vm.state.value.ownerInfo)
        assertNull(vm.state.value.firmwareVersion)
        assertTrue(thrown.isEmpty())
        assertTrue(vm.ownerInfoJob!!.isCancelled)
        assertEquals(1, harness.nodeSession.requestOwnerInfoCalls.size)
    }

    @Test
    fun anOwnerInfoRequestFailureIsShownOnTheSection() = runTest {
        val thrown = mutableListOf<Throwable>()
        val harness = repeaterStatusHarness(thrown)
        val vm = repeaterStatusViewModel(harness)
        harness.nodeSession.ownerInfoError = OwnerInfoRequestFailure()

        vm.requestOwnerInfo()
        runCurrent()

        assertEquals(NodeSectionError.Message("radio"), vm.state.value.ownerInfoSectionError)
        assertFalse(vm.state.value.isLoadingOwnerInfo)
        assertNull(vm.state.value.ownerInfo)
        assertTrue(thrown.isEmpty())
        assertEquals(1, harness.nodeSession.requestOwnerInfoCalls.size)
    }

    @Test
    fun anOwnerInfoRequestTimeoutStaysTheTimeoutError() = runTest {
        val thrown = mutableListOf<Throwable>()
        val harness = repeaterStatusHarness(thrown)
        val vm = repeaterStatusViewModel(harness)
        harness.nodeSession.ownerInfoError = RemoteNodeError.Timeout

        vm.requestOwnerInfo()
        runCurrent()

        assertIs<NodeSectionError.Timeout>(vm.state.value.ownerInfoSectionError)
        assertFalse(vm.state.value.isLoadingOwnerInfo)
        assertNull(vm.state.value.ownerInfo)
        assertTrue(thrown.isEmpty())
        assertEquals(1, harness.nodeSession.requestOwnerInfoCalls.size)
    }

    @Test
    fun anOwnerInfoRequestErrorLeavesLoadingSetAndPropagates() = runTest {
        val probe = OwnerInfoRequestProbeError()
        val thrown = mutableListOf<Throwable>()
        val harness = repeaterStatusHarness(thrown)
        val vm = repeaterStatusViewModel(harness)
        harness.nodeSession.ownerInfoError = probe

        vm.requestOwnerInfo()
        runCurrent()

        assertTrue(vm.state.value.isLoadingOwnerInfo)
        assertNull(vm.state.value.ownerInfoSectionError)
        assertNull(vm.state.value.ownerInfo)
        assertSame(probe, thrown.single())
        assertEquals(1, harness.nodeSession.requestOwnerInfoCalls.size)
    }
}

private class OwnerInfoRequestFailure : Exception("radio")

private class OwnerInfoRequestProbeError : Error("probe")
