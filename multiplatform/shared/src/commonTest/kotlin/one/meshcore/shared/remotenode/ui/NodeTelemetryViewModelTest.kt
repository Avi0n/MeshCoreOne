package one.meshcore.shared.remotenode.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.TelemetrySnapshotEntry
import one.meshcore.shared.battery.BatteryOcv
import one.meshcore.shared.battery.OCVPreset
import one.meshcore.shared.meshcore.TelemetryResponse
import one.meshcore.shared.remotenode.InMemoryNodeSnapshotStore
import one.meshcore.shared.remotenode.LPPDataPoint
import one.meshcore.shared.remotenode.LPPSensorType
import one.meshcore.shared.remotenode.LPPValue
import one.meshcore.shared.remotenode.MeshError
import one.meshcore.shared.remotenode.NodeSnapshotService
import one.meshcore.shared.remotenode.RemoteSectionRetryPolicy
import one.meshcore.shared.remotenode.voltageOcvPercent
import one.meshcore.shared.remotenode.voltagePoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NodeTelemetryViewModelTest {
    private val publicKey = ByteArray(32) { it.toByte() }
    private val prefix = publicKey.copyOf(6)

    @Test
    fun successDecodesPointsAndRecordsSnapshot() = runTest {
        val store = InMemoryNodeSnapshotStore()
        val snapshots = NodeSnapshotService(store)
        val vm = viewModel(
            request = { voltageResponse() },
            snapshots = snapshots,
        )
        advanceUntilIdle()
        val state = vm.state.value
        assertTrue(state.loaded)
        assertFalse(state.isLoading)
        assertEquals(1, state.dataPoints.size)
        assertEquals(null, state.currentLocationFix)
        val volts = (state.dataPoints[0].value as one.meshcore.shared.remotenode.LPPValue.FloatValue).value
        assertEquals(3.99, volts, absoluteTolerance = 0.001)

        val rows = snapshots.fetchSnapshots(publicKey)
        assertEquals(1, rows.size)
        val entries = rows[0].telemetryEntries.orEmpty()
        assertEquals(listOf(TelemetrySnapshotEntry(1, "Voltage", 3.99)), entries)
    }

    @Test
    fun collapsingLoadedTelemetryDoesNotRequestAgain() = runTest {
        var calls = 0
        val vm = viewModel(
            request = {
                calls += 1
                voltageResponse()
            },
        )
        advanceUntilIdle()
        assertEquals(1, calls)
        assertTrue(vm.state.value.loaded)
        assertTrue(vm.state.value.expanded)

        vm.setExpanded(false)
        advanceUntilIdle()
        vm.setExpanded(true)
        advanceUntilIdle()

        assertEquals(1, calls)
        assertTrue(vm.state.value.expanded)
        assertTrue(vm.state.value.loaded)
        assertFalse(vm.state.value.isLoading)
    }

    @Test
    fun reloadReadsTheCurrentRequestProvider() = runTest {
        var firstCalls = 0
        var secondCalls = 0
        var request: suspend (ByteArray) -> TelemetryResponse = {
            firstCalls += 1
            voltageResponse()
        }
        val vm = NodeTelemetryViewModel(
            scope = this,
            publicKey = publicKey,
            dependencies = NodeTelemetryViewModel.Dependencies(
                requestTelemetry = { request },
                expectedPrefix = prefix,
            ),
        )
        vm.setExpanded(true)
        advanceUntilIdle()

        request = {
            secondCalls += 1
            voltageResponse()
        }
        vm.requestTelemetry()
        advanceUntilIdle()

        assertEquals(1, firstCalls)
        assertEquals(1, secondCalls)
        assertTrue(vm.state.value.loaded)
    }

    @Test
    fun closeCancelsTheOwnedRequest() = runTest {
        var cancelled = false
        var requests = 0
        val vm = viewModel(
            request = {
                requests += 1
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            },
        )
        runCurrent()

        vm.close()
        vm.requestTelemetry()
        vm.setExpanded(false)
        advanceUntilIdle()

        assertEquals(1, requests)
        assertTrue(cancelled)
        assertTrue(vm.state.value.expanded)
        assertFalse(vm.state.value.isLoading)
        assertNull(vm.state.value.sectionError)
    }

    @Test
    fun aFailedRequestMapsTheSectionErrorAndClearsLoading() = runTest {
        val vm = viewModel(request = { error("link down") })
        advanceUntilIdle()
        assertFalse(vm.state.value.isLoading)
        assertFalse(vm.state.value.loaded)
        assertEquals(NodeSectionError.Message("link down"), vm.state.value.sectionError)
    }

    @Test
    fun aCancelledRequestClearsLoadingWithoutASectionError() = runTest {
        val cancelled = CancellationException("superseded")
        val vm = viewModel(request = { throw cancelled })
        val child = coroutineContext.job.children.single()
        var cause: Throwable? = null
        child.invokeOnCompletion { cause = it }
        advanceUntilIdle()
        assertSame(cancelled, cause)
        assertFalse(vm.state.value.isLoading)
        assertFalse(vm.state.value.loaded)
        assertNull(vm.state.value.sectionError)
    }

    @Test
    fun aRequestErrorPropagatesWithoutASectionError() = runTest {
        val probe = TelemetryProbeError()
        val errors = mutableListOf<Throwable>()
        val scope = CoroutineScope(
            SupervisorJob() +
                UnconfinedTestDispatcher(testScheduler) +
                CoroutineExceptionHandler { _, error -> errors += error },
        )
        val vm = viewModel(scope = scope, request = { throw probe })
        assertSame(probe, errors.single())
        assertTrue(vm.state.value.isLoading)
        assertFalse(vm.state.value.loaded)
        assertNull(vm.state.value.sectionError)
        scope.cancel()
    }

    @Test
    fun timeoutMapsToSectionError() = runTest {
        val vm = viewModel(request = { throw MeshError.Timeout })
        advanceUntilIdle()
        val state = vm.state.value
        assertFalse(state.loaded)
        assertEquals(NodeSectionError.Timeout, state.sectionError)
    }

    @Test
    fun transientFirmwareReplyIsReissuedInsideTheBudget() = runTest {
        var calls = 0
        val vm = viewModel(
            request = {
                calls += 1
                if (calls == 1) {
                    throw MeshError.DeviceError(RemoteSectionRetryPolicy.REMOTE_NODE_NO_RESPONSE_YET)
                }
                voltageResponse()
            },
        )
        advanceUntilIdle()

        assertEquals(2, calls)
        assertTrue(vm.state.value.loaded)
        assertEquals(null, vm.state.value.sectionError)
    }

    @Test
    fun locationOnlyReadingStillRecordsASnapshot() = runTest {
        val store = InMemoryNodeSnapshotStore()
        val snapshots = NodeSnapshotService(store)
        val vm = viewModel(request = { gpsOnlyResponse() }, snapshots = snapshots)
        advanceUntilIdle()

        assertTrue(vm.state.value.loaded)
        val fix = vm.state.value.currentLocationFix
        assertEquals(GPS_LATITUDE, fix?.latitude)
        assertEquals(GPS_LONGITUDE, fix?.longitude)
        val rows = snapshots.fetchSnapshots(publicKey)
        assertEquals(1, rows.size)
        assertEquals(GPS_LATITUDE, rows[0].latitude)
        assertEquals(GPS_LONGITUDE, rows[0].longitude)
    }

    @Test
    fun liveVoltageCaptionUsesTheContactOcvCurve() = runTest {
        val curve = OCVPreset.LILY_GO_T_BEAM_1W.ocvArray
        val vm = viewModel(request = { voltageResponse() }, ocvValues = curve)
        advanceUntilIdle()
        val point = vm.state.value.dataPoints.single()
        assertEquals(curve, vm.state.value.ocvValues)
        assertEquals(
            BatteryOcv.percentage((VOLTAGE_VOLTS * MILLIVOLTS_PER_VOLT).toInt(), curve),
            voltageOcvPercent(point, vm.state.value.ocvValues),
        )
        assertTrue(
            voltageOcvPercent(point, curve) != voltageOcvPercent(point, OCVPreset.LI_ION.ocvArray),
            "the contact curve must change the caption vs the Li-ion default",
        )
    }

    @Test
    fun prefixMismatchIgnored() = runTest {
        val vm = viewModel(
            request = {
                TelemetryResponse(
                    publicKeyPrefix = ByteArray(6) { 0xFF.toByte() },
                    tag = null,
                    rawData = ByteArray(0),
                    points = listOf(voltagePoint()),
                )
            },
        )
        advanceUntilIdle()
        assertFalse(vm.state.value.loaded)
        assertTrue(vm.state.value.dataPoints.isEmpty())
    }

    /** The bridge's decoding of a channel-1 voltage reading. */
    private fun voltageResponse(): TelemetryResponse = TelemetryResponse(
        publicKeyPrefix = prefix,
        tag = null,
        rawData = ByteArray(0),
        points = listOf(voltagePoint()),
    )

    /** A reading carrying only a GPS fix: a node with a lock but no numeric sensors. */
    private fun gpsOnlyResponse(): TelemetryResponse = TelemetryResponse(
        publicKeyPrefix = prefix,
        tag = null,
        rawData = ByteArray(0),
        points = listOf(LPPDataPoint(1, LPPSensorType.GPS, LPPValue.Gps(GPS_LATITUDE, GPS_LONGITUDE, GPS_ALTITUDE))),
    )

    private fun TestScope.viewModel(
        request: suspend (ByteArray) -> TelemetryResponse,
        snapshots: NodeSnapshotService? = null,
        ocvValues: List<Int> = OCVPreset.LI_ION.ocvArray,
        scope: CoroutineScope = this,
    ): NodeTelemetryViewModel = NodeTelemetryViewModel(
        scope = scope,
        publicKey = publicKey,
        dependencies = NodeTelemetryViewModel.Dependencies(
            requestTelemetry = { request },
            nodeSnapshotService = { snapshots },
            expectedPrefix = prefix,
            ocvValues = ocvValues,
        ),
    ).also { it.setExpanded(true) }

    private companion object {
        const val GPS_LATITUDE = 10.0
        const val GPS_LONGITUDE = 20.0
        const val GPS_ALTITUDE = 100.0
        const val VOLTAGE_VOLTS = 3.99
        const val MILLIVOLTS_PER_VOLT = 1000.0
    }
}

private class TelemetryProbeError : Error("probe")
