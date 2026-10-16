package one.meshcore.shared.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.meshcore.MeshEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A discovery scan reports the scan error and stops. Cancellation keeps the thrown instance and
 * leaves that message clear. A thrown error propagates.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NodeDiscoveryScanFaultTest {
    @Test
    fun aScanFaultUsesTheLatestLocalizedCopy() = runTest {
        var message = "first language"
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val vm = scanningViewModel(scanFailedMessage = { message }) { _, _ -> throw ScanDiscoverFailure() }

        vm.scan(scope)
        assertEquals("first language", vm.state.value.errorMessage)

        message = "second language"
        vm.scan(scope)
        assertEquals("second language", vm.state.value.errorMessage)
        scope.cancel()
    }

    @Test
    fun aCancelledScanKeepsTheThrownInstance() = runTest {
        val stopped = CancellationException("stopped")
        lateinit var launched: Job
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val vm = scanningViewModel { _, _ ->
            launched = currentCoroutineContext()[Job] ?: error("missing scan job")
            throw stopped
        }

        vm.scan(scope)

        assertSame(stopped, launched.completionCause())
        assertNull(vm.state.value.errorMessage)
        assertFalse(vm.state.value.isScanning)
        assertTrue(vm.state.value.hasScannedOnce)
        scope.cancel()
    }

    @Test
    fun aScanExceptionReportsTheScanErrorAndStops() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val vm = scanningViewModel { _, _ -> throw ScanDiscoverFailure() }

        vm.scan(scope)

        assertEquals("scan fail", vm.state.value.errorMessage)
        assertFalse(vm.state.value.isScanning)
        assertTrue(vm.state.value.hasScannedOnce)
        scope.cancel()
    }

    @Test
    fun aScanErrorPropagatesAndLeavesTheMessageClear() = runTest {
        val probe = ScanDiscoverProbeError()
        val errors = mutableListOf<Throwable>()
        val scope = CoroutineScope(
            SupervisorJob() + UnconfinedTestDispatcher(testScheduler) +
                CoroutineExceptionHandler { _, error -> errors += error },
        )
        val vm = scanningViewModel { _, _ -> throw probe }

        vm.scan(scope)

        assertSame(probe, errors.single())
        assertNull(vm.state.value.errorMessage)
        assertFalse(vm.state.value.isScanning)
        assertTrue(vm.state.value.hasScannedOnce)
        scope.cancel()
    }

    private fun scanningViewModel(
        scanFailedMessage: () -> String = { "scan fail" },
        sendDiscover: suspend (Int, Boolean) -> Long,
    ) = NodeDiscoveryViewModel(
        dependencies = NodeDiscoveryViewModel.Dependencies(
            sendDiscover = sendDiscover,
            events = { MutableSharedFlow<MeshEvent>().asSharedFlow() },
            contactService = { null },
            discoveredNodeStore = { null },
            radioId = { null },
            scanFailedMessage = scanFailedMessage,
            errorText = { NODE_DISCOVERY_LOCALIZED_ERROR },
            notConnectedMessage = { NODE_DISCOVERY_NOT_CONNECTED },
            unknownNodeLabel = { NODE_DISCOVERY_UNKNOWN_NODE },
        ),
        scanDurationMs = SCAN_WINDOW_MS,
    )
}

private class ScanDiscoverFailure : Exception("radio")

private class ScanDiscoverProbeError : Error("probe")

private const val SCAN_WINDOW_MS = 1_000L
