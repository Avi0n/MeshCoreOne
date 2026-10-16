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
 * A failed telemetry read is shown on the section. Cancellation clears loading and keeps the thrown
 * instance off the section. A timeout stays the timeout error. A thrown error leaves loading set.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NodeStatusTelemetryFaultTest {
    @Test
    fun aCancelledTelemetryRequestClearsLoadingWithoutASectionError() = runTest {
        val thrown = mutableListOf<Throwable>()
        val harness = repeaterStatusHarness(thrown)
        val vm = repeaterStatusViewModel(harness)
        harness.nodeSession.telemetryError = CancellationException("stopped")

        vm.requestTelemetry()
        runCurrent()

        assertFalse(vm.state.value.isLoadingTelemetry)
        assertNull(vm.state.value.telemetrySectionError)
        assertFalse(vm.state.value.telemetryLoaded)
        assertTrue(thrown.isEmpty())
        assertTrue(vm.telemetryJob!!.isCancelled)
        assertEquals(1, harness.nodeSession.requestTelemetryCalls.size)
    }

    @Test
    fun aTelemetryRequestFailureIsShownOnTheSection() = runTest {
        val thrown = mutableListOf<Throwable>()
        val harness = repeaterStatusHarness(thrown)
        val vm = repeaterStatusViewModel(harness)
        harness.nodeSession.telemetryError = TelemetryRequestFailure()

        vm.requestTelemetry()
        runCurrent()

        assertEquals(NodeSectionError.Message("radio"), vm.state.value.telemetrySectionError)
        assertFalse(vm.state.value.isLoadingTelemetry)
        assertFalse(vm.state.value.telemetryLoaded)
        assertTrue(thrown.isEmpty())
        assertEquals(1, harness.nodeSession.requestTelemetryCalls.size)
    }

    @Test
    fun aTelemetryRequestTimeoutStaysTheTimeoutError() = runTest {
        val thrown = mutableListOf<Throwable>()
        val harness = repeaterStatusHarness(thrown)
        val vm = repeaterStatusViewModel(harness)
        harness.nodeSession.telemetryError = RemoteNodeError.Timeout

        vm.requestTelemetry()
        runCurrent()

        assertIs<NodeSectionError.Timeout>(vm.state.value.telemetrySectionError)
        assertFalse(vm.state.value.isLoadingTelemetry)
        assertFalse(vm.state.value.telemetryLoaded)
        assertTrue(thrown.isEmpty())
        assertEquals(1, harness.nodeSession.requestTelemetryCalls.size)
    }

    @Test
    fun aTelemetryRequestErrorLeavesLoadingSetAndPropagates() = runTest {
        val probe = TelemetryRequestProbeError()
        val thrown = mutableListOf<Throwable>()
        val harness = repeaterStatusHarness(thrown)
        val vm = repeaterStatusViewModel(harness)
        harness.nodeSession.telemetryError = probe

        vm.requestTelemetry()
        runCurrent()

        assertTrue(vm.state.value.isLoadingTelemetry)
        assertNull(vm.state.value.telemetrySectionError)
        assertFalse(vm.state.value.telemetryLoaded)
        assertSame(probe, thrown.single())
        assertEquals(1, harness.nodeSession.requestTelemetryCalls.size)
    }
}

private class TelemetryRequestFailure : Exception("radio")

private class TelemetryRequestProbeError : Error("probe")
