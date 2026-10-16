package one.meshcore.shared.remotenode.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.backup.NeighborSnapshotEntry
import one.meshcore.shared.backup.TelemetrySnapshotEntry
import one.meshcore.shared.battery.BatteryOcv
import one.meshcore.shared.battery.OCVPreset
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.ContactStore
import one.meshcore.shared.contact.InMemoryContactStore
import one.meshcore.shared.contact.NoOpContactSession
import one.meshcore.shared.meshcore.OwnerInfoResponse
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.RoomPermissionLevel
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.remotenode.FakeNodeSession
import one.meshcore.shared.remotenode.InMemoryNodePasswordStore
import one.meshcore.shared.remotenode.InMemoryNodeSnapshotStore
import one.meshcore.shared.remotenode.InMemoryRemoteNodeStore
import one.meshcore.shared.remotenode.LPPValue
import one.meshcore.shared.remotenode.MeshError
import one.meshcore.shared.remotenode.NodeCliCoordinator
import one.meshcore.shared.remotenode.NodeSnapshotService
import one.meshcore.shared.remotenode.NodeStatusMetrics
import one.meshcore.shared.remotenode.RemoteNodeError
import one.meshcore.shared.remotenode.RemoteNodeService
import one.meshcore.shared.remotenode.RemoteSectionRetryPolicy
import one.meshcore.shared.remotenode.RepeaterAdminService
import one.meshcore.shared.remotenode.testNeighbour
import one.meshcore.shared.remotenode.testNeighboursResponse
import one.meshcore.shared.remotenode.testPublicKey
import one.meshcore.shared.remotenode.testSession
import one.meshcore.shared.remotenode.testStatusResponse
import one.meshcore.shared.remotenode.testTelemetryResponse
import one.meshcore.shared.remotenode.voltagePoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private class StatusHarness(scope: kotlinx.coroutines.CoroutineScope) {
    val nodeSession = FakeNodeSession()
    val store = InMemoryRemoteNodeStore()
    val passwordStore = InMemoryNodePasswordStore()
    val remote = RemoteNodeService(nodeSession, store, passwordStore, scope)
    val cli = NodeCliCoordinator(nodeSession, store, passwordStore, scope)
    val admin = RepeaterAdminService(remote, cli, nodeSession, store, passwordStore)
}

@OptIn(ExperimentalCoroutinesApi::class)
private suspend fun TestScope.viewModel(
    harness: StatusHarness,
    connected: Boolean = true,
    snapshotService: NodeSnapshotService? = null,
    nowEpochSeconds: Double = 10_000.0,
): NodeStatusViewModel {
    val session = testSession(
        isConnected = connected,
        permissionLevel = RoomPermissionLevel.ADMIN,
    )
    harness.store.saveSession(session)
    return NodeStatusViewModel(
        scope = backgroundScope,
        session = session,
        dependencies = NodeStatusViewModel.Dependencies(
            repeaterAdminService = { harness.admin },
            nodeSnapshotService = { snapshotService },
            nowEpochSeconds = { nowEpochSeconds + testScheduler.currentTime / 1_000.0 },
        ),
    )
}

/**
 * Section-scoped load/error behavior for the repeater status view model. The service graph is the same
 * harness shape as [one.meshcore.shared.remotenode.RepeaterAdminServiceTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NodeStatusViewModelTest {
    @Test
    fun closePreventsFurtherRequestsFromReachingTheRadio() = runTest {
        val harness = StatusHarness(backgroundScope)
        val vm = viewModel(harness)
        runCurrent()
        val statusCalls = harness.nodeSession.requestStatusCalls.size
        val telemetryCalls = harness.nodeSession.requestTelemetryCalls.size
        val ownerInfoCalls = harness.nodeSession.requestOwnerInfoCalls.size
        val neighborCalls = harness.nodeSession.requestNeighboursCalls.size

        vm.close()
        vm.requestStatus()
        vm.requestTelemetry()
        vm.requestOwnerInfo()
        vm.requestNeighbors()
        runCurrent()

        assertEquals(statusCalls, harness.nodeSession.requestStatusCalls.size)
        assertEquals(telemetryCalls, harness.nodeSession.requestTelemetryCalls.size)
        assertEquals(ownerInfoCalls, harness.nodeSession.requestOwnerInfoCalls.size)
        assertEquals(neighborCalls, harness.nodeSession.requestNeighboursCalls.size)
    }

    @Test
    fun collapsingAnAlreadyCollapsedBatteryCurveDoesNotRadio() = runTest {
        val quiet = quietRepeaterStatus()
        val vm = quiet.vm
        assertEquals(0, quiet.reads.adminReads)
        assertFalse(vm.state.value.batteryCurveExpanded)
        assertFalse(vm.state.value.ocvLoaded)
        assertNull(vm.state.value.ocvError)

        vm.setBatteryCurveExpanded(false)
        runCurrent()

        quiet.assertStillQuiet()
        assertEquals(0, quiet.reads.adminReads)
        assertFalse(vm.state.value.batteryCurveExpanded)
        assertFalse(vm.state.value.ocvLoaded)
        assertNull(vm.state.value.ocvError)
        assertFalse(vm.state.value.statusExpanded)
        assertFalse(vm.state.value.telemetryExpanded)
        assertFalse(vm.state.value.neighborsExpanded)
    }

    @Test
    fun firstExpandLoadsStatusOnce() = runTest {
        val h = StatusHarness(backgroundScope)
        val vm = viewModel(h)
        h.nodeSession.statusResponse = testStatusResponse()

        assertFalse(vm.state.value.statusLoaded)
        vm.setStatusExpanded(true)
        runCurrent()

        assertTrue(vm.state.value.statusExpanded)
        assertTrue(vm.state.value.statusLoaded)
        assertNotNull(vm.state.value.status)
        assertEquals(1, h.nodeSession.requestStatusCalls.size)

        // Collapsing and re-expanding must not re-fetch until the user hits reload.
        vm.setStatusExpanded(false)
        vm.setStatusExpanded(true)
        runCurrent()
        assertEquals(1, h.nodeSession.requestStatusCalls.size)
    }

    @Test
    fun requestStatusTimeoutSurfacesSectionError() = runTest {
        val h = StatusHarness(backgroundScope)
        val vm = viewModel(h)
        h.nodeSession.statusError = RemoteNodeError.Timeout

        vm.requestStatus()
        runCurrent()

        assertIs<NodeSectionError.Timeout>(vm.state.value.statusSectionError)
        assertFalse(vm.state.value.isLoadingStatus)
        assertNull(vm.state.value.status)
    }

    @Test
    fun keepingRepeaterTelemetryCollapsedDoesNotRadio() = runTest {
        val quiet = quietRepeaterStatus()
        val vm = quiet.vm
        assertFalse(vm.state.value.telemetryExpanded)
        assertFalse(vm.state.value.telemetryLoaded)
        assertFalse(vm.state.value.isLoadingTelemetry)
        assertNull(vm.state.value.telemetrySectionError)
        assertTrue(vm.state.value.telemetryDataPoints.isEmpty())

        vm.setTelemetryExpanded(false)
        runCurrent()

        quiet.assertStillQuiet()
        assertFalse(vm.state.value.telemetryExpanded)
        assertFalse(vm.state.value.telemetryLoaded)
        assertFalse(vm.state.value.isLoadingTelemetry)
        assertNull(vm.state.value.telemetrySectionError)
        assertTrue(vm.state.value.telemetryDataPoints.isEmpty())
    }

    @Test
    fun telemetryLoadsOnFirstExpandDecodesAndRecordsSnapshot() = runTest {
        val h = StatusHarness(backgroundScope)
        val publicKey = testPublicKey()
        val session = testSession(publicKey = publicKey, permissionLevel = RoomPermissionLevel.ADMIN)
        h.store.saveSession(session)
        val snapshots = NodeSnapshotService(InMemoryNodeSnapshotStore())
        h.nodeSession.telemetryResponse =
            testTelemetryResponse(publicKey = publicKey, points = listOf(voltagePoint()))
        val vm = NodeStatusViewModel(
            scope = backgroundScope,
            session = session,
            dependencies = NodeStatusViewModel.Dependencies(
                repeaterAdminService = { h.admin },
                nodeSnapshotService = { snapshots },
            ),
        )

        assertFalse(vm.state.value.telemetryLoaded)
        vm.setTelemetryExpanded(true)
        runCurrent()

        assertTrue(vm.state.value.telemetryExpanded)
        assertTrue(vm.state.value.telemetryLoaded)
        assertEquals(1, vm.state.value.telemetryDataPoints.size)
        val volts = (vm.state.value.telemetryDataPoints[0].value as LPPValue.FloatValue).value
        assertEquals(3.99, volts, absoluteTolerance = 0.001)
        assertEquals(1, h.nodeSession.requestTelemetryCalls.size)

        // Collapse / re-expand must not re-fetch until explicit reload.
        vm.setTelemetryExpanded(false)
        vm.setTelemetryExpanded(true)
        runCurrent()
        assertEquals(1, h.nodeSession.requestTelemetryCalls.size)

        val rows = snapshots.fetchSnapshots(publicKey)
        assertEquals(1, rows.size)
        assertEquals(
            listOf(TelemetrySnapshotEntry(1, "Voltage", 3.99)),
            rows[0].telemetryEntries.orEmpty(),
        )
    }

    @Test
    fun requestTelemetryTimeoutSurfacesSectionError() = runTest {
        val h = StatusHarness(backgroundScope)
        val vm = viewModel(h)
        h.nodeSession.telemetryError = RemoteNodeError.Timeout

        vm.requestTelemetry()
        runCurrent()

        assertIs<NodeSectionError.Timeout>(vm.state.value.telemetrySectionError)
        assertFalse(vm.state.value.isLoadingTelemetry)
        assertFalse(vm.state.value.telemetryLoaded)
    }

    @Test
    fun aFailedStatusRequestSurfacesTheSectionMessage() = runTest {
        val h = StatusHarness(backgroundScope)
        val vm = viewModel(h)
        h.nodeSession.statusError = IllegalStateException("link down")

        vm.requestStatus()
        runCurrent()

        assertEquals(NodeSectionError.Message("link down"), vm.state.value.statusSectionError)
        assertFalse(vm.state.value.isLoadingStatus)
        assertNull(vm.state.value.status)
    }

    @Test
    fun aCancelledStatusRequestClearsLoadingWithoutASectionError() = runTest {
        val h = StatusHarness(backgroundScope)
        val vm = viewModel(h)
        h.nodeSession.statusError = CancellationException("superseded")

        vm.requestStatus()
        runCurrent()

        assertFalse(vm.state.value.isLoadingStatus)
        assertNull(vm.state.value.statusSectionError)
        assertNull(vm.state.value.status)
    }

    @Test
    fun aFailedTelemetryRequestSurfacesTheSectionMessage() = runTest {
        val h = StatusHarness(backgroundScope)
        val vm = viewModel(h)
        h.nodeSession.telemetryError = IllegalStateException("link down")

        vm.requestTelemetry()
        runCurrent()

        assertEquals(NodeSectionError.Message("link down"), vm.state.value.telemetrySectionError)
        assertFalse(vm.state.value.isLoadingTelemetry)
        assertFalse(vm.state.value.telemetryLoaded)
    }

    @Test
    fun aCancelledTelemetryRequestClearsLoadingWithoutASectionError() = runTest {
        val h = StatusHarness(backgroundScope)
        val vm = viewModel(h)
        h.nodeSession.telemetryError = CancellationException("superseded")

        vm.requestTelemetry()
        runCurrent()

        assertFalse(vm.state.value.isLoadingTelemetry)
        assertNull(vm.state.value.telemetrySectionError)
        assertFalse(vm.state.value.telemetryLoaded)
    }

    @Test
    fun aFailedOwnerInfoRequestSurfacesTheSectionMessage() = runTest {
        val h = StatusHarness(backgroundScope)
        val vm = viewModel(h)
        h.nodeSession.ownerInfoError = IllegalStateException("link down")

        vm.requestOwnerInfo()
        runCurrent()

        assertEquals(NodeSectionError.Message("link down"), vm.state.value.ownerInfoSectionError)
        assertFalse(vm.state.value.isLoadingOwnerInfo)
        assertNull(vm.state.value.ownerInfo)
    }

    @Test
    fun aCancelledOwnerInfoRequestClearsLoadingWithoutASectionError() = runTest {
        val h = StatusHarness(backgroundScope)
        val vm = viewModel(h)
        h.nodeSession.ownerInfoError = CancellationException("superseded")

        vm.requestOwnerInfo()
        runCurrent()

        assertFalse(vm.state.value.isLoadingOwnerInfo)
        assertNull(vm.state.value.ownerInfoSectionError)
        assertNull(vm.state.value.ownerInfo)
    }

    @Test
    fun requestOwnerInfoTimeoutSurfacesSectionError() = runTest {
        val h = StatusHarness(backgroundScope)
        val vm = viewModel(h)
        h.nodeSession.ownerInfoError = RemoteNodeError.Timeout

        vm.requestOwnerInfo()
        runCurrent()

        assertIs<NodeSectionError.Timeout>(vm.state.value.ownerInfoSectionError)
        assertFalse(vm.state.value.isLoadingOwnerInfo)
        assertNull(vm.state.value.ownerInfo)
    }

    @Test
    fun aFailedNeighborRequestSurfacesTheSectionMessage() = runTest {
        val h = StatusHarness(backgroundScope)
        val vm = viewModel(h)
        h.nodeSession.neighboursError = IllegalStateException("link down")

        vm.requestNeighbors()
        runCurrent()

        assertEquals(NodeSectionError.Message("link down"), vm.state.value.neighborsSectionError)
        assertFalse(vm.state.value.isLoadingNeighbors)
        assertTrue(vm.state.value.neighbors.isEmpty())
    }

    @Test
    fun aCancelledNeighborRequestClearsLoadingWithoutASectionError() = runTest {
        val h = StatusHarness(backgroundScope)
        val vm = viewModel(h)
        h.nodeSession.neighboursError = CancellationException("superseded")

        vm.requestNeighbors()
        runCurrent()

        assertFalse(vm.state.value.isLoadingNeighbors)
        assertNull(vm.state.value.neighborsSectionError)
        assertTrue(vm.state.value.neighbors.isEmpty())
    }

    @Test
    fun requestNeighborsTimeoutSurfacesSectionError() = runTest {
        val h = StatusHarness(backgroundScope)
        val vm = viewModel(h)
        h.nodeSession.neighboursError = RemoteNodeError.Timeout

        vm.requestNeighbors()
        runCurrent()

        assertIs<NodeSectionError.Timeout>(vm.state.value.neighborsSectionError)
        assertFalse(vm.state.value.isLoadingNeighbors)
        assertTrue(vm.state.value.neighbors.isEmpty())
    }

    @Test
    fun aSectionRequestErrorPropagatesWithoutASectionError() = runTest {
        val errors = mutableListOf<Throwable>()
        val scope = CoroutineScope(
            SupervisorJob() +
                UnconfinedTestDispatcher(testScheduler) +
                CoroutineExceptionHandler { _, error -> errors += error },
        )
        val h = StatusHarness(scope)
        h.nodeSession.statusError = StatusSectionProbeError()
        h.nodeSession.telemetryError = StatusSectionProbeError()
        h.nodeSession.ownerInfoError = StatusSectionProbeError()
        h.nodeSession.neighboursError = StatusSectionProbeError()
        val session = testSession(permissionLevel = RoomPermissionLevel.ADMIN)
        h.store.saveSession(session)
        val vm = NodeStatusViewModel(
            scope = scope,
            session = session,
            dependencies = NodeStatusViewModel.Dependencies(
                repeaterAdminService = { h.admin },
            ),
        )

        vm.setStatusExpanded(true)
        vm.setTelemetryExpanded(true)
        vm.setOwnerInfoExpanded(true)
        vm.setNeighborsExpanded(true)

        assertEquals(SECTION_PROBE_COUNT, errors.size)
        assertTrue(errors.all { it is StatusSectionProbeError })
        assertTrue(vm.state.value.isLoadingStatus)
        assertTrue(vm.state.value.isLoadingTelemetry)
        assertTrue(vm.state.value.isLoadingOwnerInfo)
        assertTrue(vm.state.value.isLoadingNeighbors)
        assertNull(vm.state.value.statusSectionError)
        assertNull(vm.state.value.telemetrySectionError)
        assertNull(vm.state.value.ownerInfoSectionError)
        assertNull(vm.state.value.neighborsSectionError)
        scope.cancel()
    }

    @Test
    fun telemetryPrefixMismatchIsIgnored() = runTest {
        val h = StatusHarness(backgroundScope)
        val vm = viewModel(h)
        h.nodeSession.telemetryResponse = testTelemetryResponse(
            publicKey = ByteArray(32) { 0x11 },
            points = listOf(voltagePoint()),
        )

        vm.requestTelemetry()
        runCurrent()

        assertFalse(vm.state.value.telemetryLoaded)
        assertTrue(vm.state.value.telemetryDataPoints.isEmpty())
        assertFalse(vm.state.value.isLoadingTelemetry)
    }

    @Test
    fun keepingRepeaterOwnerInfoCollapsedDoesNotRadio() = runTest {
        val quiet = quietRepeaterStatus()
        val vm = quiet.vm
        assertFalse(vm.state.value.ownerInfoExpanded)
        assertFalse(vm.state.value.ownerInfoLoaded)
        assertFalse(vm.state.value.isLoadingOwnerInfo)
        assertNull(vm.state.value.ownerInfoSectionError)
        assertNull(vm.state.value.ownerInfo)
        assertNull(vm.state.value.firmwareVersion)

        vm.setOwnerInfoExpanded(false)
        runCurrent()

        quiet.assertStillQuiet()
        assertFalse(vm.state.value.ownerInfoExpanded)
        assertFalse(vm.state.value.ownerInfoLoaded)
        assertFalse(vm.state.value.isLoadingOwnerInfo)
        assertNull(vm.state.value.ownerInfoSectionError)
        assertNull(vm.state.value.ownerInfo)
        assertNull(vm.state.value.firmwareVersion)
    }

    @Test
    fun ownerInfoLoadsOnFirstExpand() = runTest {
        val h = StatusHarness(backgroundScope)
        val vm = viewModel(h)
        h.nodeSession.ownerInfoResponse = OwnerInfoResponse(
            firmwareVersion = "1.0",
            nodeName = "Test Node",
            ownerInfo = "Alice",
        )

        vm.setOwnerInfoExpanded(true)
        runCurrent()

        assertTrue(vm.state.value.ownerInfoLoaded)
        assertEquals("Alice", vm.state.value.ownerInfo)
        assertEquals("1.0", vm.state.value.firmwareVersion)
        assertEquals(1, h.nodeSession.requestOwnerInfoCalls.size)
    }

    @Test
    fun anEmptyFirmwareStringLeavesTheFirmwareRowHidden() = runTest {
        // Nodes predating owner-info report an empty firmware string; a blank row would read as a value.
        val h = StatusHarness(backgroundScope)
        val vm = viewModel(h)
        h.nodeSession.ownerInfoResponse = OwnerInfoResponse(
            firmwareVersion = "",
            nodeName = "Test Node",
            ownerInfo = "Alice",
        )

        vm.setOwnerInfoExpanded(true)
        runCurrent()

        assertNull(vm.state.value.firmwareVersion)
    }

    @Test
    fun statusWithMismatchedPrefixIsIgnored() = runTest {
        val h = StatusHarness(backgroundScope)
        val vm = viewModel(h)
        h.nodeSession.statusResponse = testStatusResponse(publicKey = ByteArray(32) { 0x11 })

        vm.requestStatus()
        runCurrent()

        assertFalse(vm.state.value.statusLoaded)
        assertNull(vm.state.value.status)
        assertFalse(vm.state.value.isLoadingStatus)
    }

    @Test
    fun keepingRepeaterNeighborsCollapsedDoesNotRadio() = runTest {
        val quiet = quietRepeaterStatus()
        val vm = quiet.vm
        assertFalse(vm.state.value.neighborsExpanded)
        assertFalse(vm.state.value.neighborsLoaded)
        assertFalse(vm.state.value.isLoadingNeighbors)
        assertNull(vm.state.value.neighborsSectionError)
        assertTrue(vm.state.value.neighbors.isEmpty())

        vm.setNeighborsExpanded(false)
        runCurrent()

        quiet.assertStillQuiet()
        assertFalse(vm.state.value.neighborsExpanded)
        assertFalse(vm.state.value.neighborsLoaded)
        assertFalse(vm.state.value.isLoadingNeighbors)
        assertNull(vm.state.value.neighborsSectionError)
        assertTrue(vm.state.value.neighbors.isEmpty())
    }

    @Test
    fun neighborsLoadOnFirstExpand() = runTest {
        val h = StatusHarness(backgroundScope)
        val vm = viewModel(h)
        h.nodeSession.neighboursResponse = testNeighboursResponse(
            totalCount = 1,
            neighbours = listOf(testNeighbour(prefix = 0x42, snr = 7.5)),
        )

        assertFalse(vm.state.value.neighborsLoaded)
        vm.setNeighborsExpanded(true)
        runCurrent()

        assertTrue(vm.state.value.neighborsExpanded)
        assertTrue(vm.state.value.neighborsLoaded)
        assertEquals(1, vm.state.value.neighbors.size)
        assertEquals(7.5, vm.state.value.neighbors[0].snr)
        assertTrue(h.nodeSession.requestNeighboursCalls.isNotEmpty())

        vm.setNeighborsExpanded(false)
        vm.setNeighborsExpanded(true)
        runCurrent()
        // No second fetch until explicit reload.
        assertEquals(1, h.nodeSession.requestNeighboursCalls.size)
    }
}

/**
 * Snapshot deltas, neighbor discovery, and the battery curve.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NodeStatusViewModelSnapshotTest {
    @Test
    fun statusLoadExposesDeltaAgainstPreviousSnapshot() = runTest {
        val h = StatusHarness(backgroundScope)
        val publicKey = testPublicKey()
        val session = testSession(publicKey = publicKey, permissionLevel = RoomPermissionLevel.ADMIN)
        h.store.saveSession(session)
        var clock = 1_000.0
        val snapshots = NodeSnapshotService(InMemoryNodeSnapshotStore(now = { clock }))
        snapshots.recordSnapshot(
            nodePublicKey = publicKey,
            status = NodeStatusMetrics(
                batteryMillivolts = 3800,
                lastSNR = 7.0,
                lastRSSI = -90,
                noiseFloor = -118,
                uptimeSeconds = 100,
                rxAirtimeSeconds = null,
                packetsSent = null,
                packetsReceived = null,
                receiveErrors = null,
                sentDirect = null,
                sentFlood = null,
                receivedDirect = null,
                receivedFlood = null,
                directDuplicates = null,
                floodDuplicates = null,
            ),
        )
        clock = 10_000.0
        val vm = NodeStatusViewModel(
            scope = backgroundScope,
            session = session,
            dependencies = NodeStatusViewModel.Dependencies(
                repeaterAdminService = { h.admin },
                nodeSnapshotService = { snapshots },
                nowEpochSeconds = { clock },
            ),
        )
        h.nodeSession.statusResponse = testStatusResponse(
            publicKey = publicKey,
            battery = 3900,
            lastSNR = 8.5,
            lastRSSI = -87,
            noiseFloor = -120,
        )

        vm.requestStatus()
        runCurrent()

        assertNotNull(vm.state.value.previousStatusSnapshot)
        val deltas = vm.state.value.statusDeltas
        assertEquals(0.1, deltas.batteryVolts!!, absoluteTolerance = 1e-9)
        assertEquals(1.5, deltas.snr!!, absoluteTolerance = 1e-9)
        assertEquals(3.0, deltas.rssi!!, absoluteTolerance = 1e-9)
        assertEquals(-2.0, deltas.noiseFloor!!, absoluteTolerance = 1e-9)
    }

    @Test
    fun neighborsLoadExposesBaselineForNewAndDisappeared() = runTest {
        val h = StatusHarness(backgroundScope)
        val publicKey = testPublicKey()
        val session = testSession(publicKey = publicKey, permissionLevel = RoomPermissionLevel.ADMIN)
        h.store.saveSession(session)
        val oldPrefix = ByteArray(6) { 0x11 }
        val keepPrefix = ByteArray(6) { 0x22 }
        var clock = 1_000.0
        val snapshotService = NodeSnapshotService(InMemoryNodeSnapshotStore(now = { clock }))
        snapshotService.recordSnapshot(
            nodePublicKey = publicKey,
            neighbors = listOf(
                NeighborSnapshotEntry(publicKeyPrefix = oldPrefix, snr = 3.0, secondsAgo = 60),
                NeighborSnapshotEntry(publicKeyPrefix = keepPrefix, snr = 5.0, secondsAgo = 30),
            ),
        )
        clock = 10_000.0
        val vm = NodeStatusViewModel(
            scope = backgroundScope,
            session = session,
            dependencies = NodeStatusViewModel.Dependencies(
                repeaterAdminService = { h.admin },
                nodeSnapshotService = { snapshotService },
                nowEpochSeconds = { clock },
            ),
        )
        h.nodeSession.neighboursResponse = testNeighboursResponse(
            totalCount = 2,
            neighbours = listOf(
                testNeighbour(prefix = 0x22, snr = 6.0),
                testNeighbour(prefix = 0x33, snr = 4.0),
            ),
        )

        vm.requestNeighbors()
        runCurrent()

        assertNotNull(vm.state.value.previousNeighborSnapshot)
        assertTrue(vm.state.value.seenNeighborPrefixes.isNotEmpty())
        assertTrue(
            NodeStatusDeltas.isNewNeighbor(
                prefix = ByteArray(6) { 0x33 },
                previousNeighborSnapshot = vm.state.value.previousNeighborSnapshot,
                seenPrefixes = vm.state.value.seenNeighborPrefixes,
            ),
        )
        val disappeared = NodeStatusDeltas.disappearedNeighbors(
            vm.state.value.neighbors,
            vm.state.value.previousNeighborSnapshot?.neighborSnapshots,
        )
        assertEquals(1, disappeared.size)
        assertTrue(disappeared[0].publicKeyPrefix.contentEquals(oldPrefix))
    }

    @Test
    fun startDiscoverySendsCommandAndPollsNeighbors() = runTest {
        val h = StatusHarness(backgroundScope)
        val vm = viewModel(h)
        h.nodeSession.neighboursResponse = testNeighboursResponse(
            totalCount = 1,
            neighbours = listOf(testNeighbour(prefix = 0x55, snr = 4.0)),
        )
        runCurrent() // CLI event monitor

        vm.startDiscovery()
        runCurrent()
        assertTrue(vm.state.value.isDiscovering)
        assertEquals(60, vm.state.value.discoverySecondsRemaining)
        // The CLI coordinator prepends a wire prefix ("XX|") the firmware echoes back for correlation.
        assertTrue(h.nodeSession.sendCommandCalls.single().second.endsWith("discover.neighbors"))

        h.nodeSession.emitCliResponse("OK")
        runCurrent()

        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(vm.state.value.discoverySecondsRemaining <= 55)
        assertTrue(h.nodeSession.requestNeighboursCalls.isNotEmpty())
        assertEquals(1, vm.state.value.neighbors.size)

        vm.stopDiscovery()
        runCurrent()
        assertFalse(vm.state.value.isDiscovering)
        assertEquals(0, vm.state.value.discoverySecondsRemaining)
    }

    @Test
    fun startDiscoveryIgnoredForGuestSession() = runTest {
        val h = StatusHarness(backgroundScope)
        val session = testSession(isConnected = true, permissionLevel = RoomPermissionLevel.GUEST)
        h.store.saveSession(session)
        val vm = NodeStatusViewModel(
            scope = backgroundScope,
            session = session,
            dependencies = NodeStatusViewModel.Dependencies(
                repeaterAdminService = { h.admin },
            ),
        )
        runCurrent()
        vm.startDiscovery()
        runCurrent()
        assertFalse(vm.state.value.isDiscovering)
        assertTrue(h.nodeSession.sendCommandCalls.isEmpty())
    }

    @Test
    fun aFailedDiscoverCommandStopsDiscovery() = runTest {
        val h = StatusHarness(backgroundScope)
        val vm = viewModel(h)
        runCurrent()
        h.nodeSession.sendCommandError = IllegalStateException("link down")

        vm.startDiscovery()
        runCurrent()

        assertFalse(vm.state.value.isDiscovering)
        assertEquals(0, vm.state.value.discoverySecondsRemaining)
        assertEquals(NodeSectionError.Message("connection lost: link down"), vm.state.value.neighborsSectionError)
    }

    @Test
    fun aCancelledDiscoverCommandClearsDiscovery() = runTest {
        val h = StatusHarness(backgroundScope)
        val vm = viewModel(h)
        runCurrent()
        h.nodeSession.sendCommandError = CancellationException("superseded")

        vm.startDiscovery()
        runCurrent()

        assertFalse(vm.state.value.isDiscovering)
        assertEquals(0, vm.state.value.discoverySecondsRemaining)
        assertNull(vm.state.value.neighborsSectionError)
    }

    @Test
    fun aFailedNeighborPollKeepsDiscoveryRunning() = runTest {
        val h = StatusHarness(backgroundScope)
        val vm = viewModel(h)
        runCurrent()
        vm.startDiscovery()
        runCurrent()
        h.nodeSession.emitCliResponse("OK")
        runCurrent()
        h.nodeSession.neighboursError = IllegalStateException("poll down")

        val afterOnePoll =
            NodeStatusViewModel.DISCOVERY_DURATION_SECONDS - NodeStatusViewModel.DISCOVERY_POLL_INTERVAL_SECONDS
        val afterTwoPolls = afterOnePoll - NodeStatusViewModel.DISCOVERY_POLL_INTERVAL_SECONDS
        advanceTimeBy(NodeStatusViewModel.DISCOVERY_POLL_INTERVAL_SECONDS * NodeStatusViewModel.DISCOVERY_TICK_MS)
        runCurrent()
        assertTrue(vm.state.value.isDiscovering)
        assertEquals(afterOnePoll, vm.state.value.discoverySecondsRemaining)
        assertEquals(NodeSectionError.Message("poll down"), vm.state.value.neighborsSectionError)

        h.nodeSession.neighboursError = null
        advanceTimeBy(NodeStatusViewModel.DISCOVERY_POLL_INTERVAL_SECONDS * NodeStatusViewModel.DISCOVERY_TICK_MS)
        runCurrent()
        assertTrue(vm.state.value.isDiscovering)
        assertEquals(afterTwoPolls, vm.state.value.discoverySecondsRemaining)
        assertNull(vm.state.value.neighborsSectionError)
    }

    @Test
    fun aCancelledNeighborPollStopsTheCountdownWithoutASectionError() = runTest {
        val h = StatusHarness(backgroundScope)
        val vm = viewModel(h)
        runCurrent()
        vm.startDiscovery()
        runCurrent()
        h.nodeSession.emitCliResponse("OK")
        runCurrent()
        h.nodeSession.neighboursError = CancellationException("superseded")

        val afterOnePoll =
            NodeStatusViewModel.DISCOVERY_DURATION_SECONDS - NodeStatusViewModel.DISCOVERY_POLL_INTERVAL_SECONDS
        advanceTimeBy(NodeStatusViewModel.DISCOVERY_POLL_INTERVAL_SECONDS * NodeStatusViewModel.DISCOVERY_TICK_MS)
        runCurrent()
        val remaining = vm.state.value.discoverySecondsRemaining
        assertEquals(afterOnePoll, remaining)
        assertNull(vm.state.value.neighborsSectionError)

        advanceTimeBy(NodeStatusViewModel.DISCOVERY_POLL_INTERVAL_SECONDS * NodeStatusViewModel.DISCOVERY_TICK_MS)
        runCurrent()
        assertEquals(remaining, vm.state.value.discoverySecondsRemaining)
        assertNull(vm.state.value.neighborsSectionError)
    }

    @Test
    fun loadAndSaveOcvPreset() = runTest {
        val h = StatusHarness(backgroundScope)
        val publicKey = testPublicKey()
        val session = testSession(publicKey = publicKey, permissionLevel = RoomPermissionLevel.ADMIN)
        h.store.saveSession(session)
        val radioId = Uuid.normalized("R0000000-0000-0000-0000-000000000001")
        val contactId = Uuid.normalized("C0000000-0000-0000-0000-000000000001")
        val store = InMemoryContactStore()
        store.saveContact(
            repeaterContact(
                id = contactId,
                radioID = radioId,
                publicKey = publicKey,
                preset = OCVPreset.LI_FE_PO4,
            ),
        )
        val contactService = ContactService(
            session = NoOpContactSession,
            store = store,
            now = { 0L },
        )
        val vm = NodeStatusViewModel(
            scope = backgroundScope,
            session = session,
            dependencies = NodeStatusViewModel.Dependencies(
                repeaterAdminService = { h.admin },
                contactService = { contactService },
                radioId = { radioId },
            ),
        )
        vm.setBatteryCurveExpanded(true)
        runCurrent()
        assertTrue(vm.state.value.ocvLoaded)
        assertEquals(OCVPreset.LI_FE_PO4, vm.state.value.ocvPreset)

        vm.selectOcvPreset(OCVPreset.LI_ION)
        runCurrent()
        assertEquals(OCVPreset.LI_ION, vm.state.value.ocvPreset)
        val saved = store.fetchContact(contactId)
        assertEquals(OCVPreset.LI_ION.rawValue, saved?.ocvPreset)
        assertNull(saved?.customOCVArrayString)
    }

    @Test
    fun selectOcvPresetWhileDisconnectedDoesNotPersist() = runTest {
        val h = StatusHarness(backgroundScope)
        val publicKey = testPublicKey()
        val session = testSession(publicKey = publicKey, permissionLevel = RoomPermissionLevel.ADMIN)
        h.store.saveSession(session)
        val radioId = Uuid.normalized("R0000000-0000-0000-0000-000000000002")
        val contactId = Uuid.normalized("C0000000-0000-0000-0000-000000000003")
        val store = InMemoryContactStore()
        store.saveContact(
            repeaterContact(
                id = contactId,
                radioID = radioId,
                publicKey = publicKey,
                preset = OCVPreset.LI_FE_PO4,
            ),
        )
        val contactService = ContactService(
            session = NoOpContactSession,
            store = store,
            now = { 0L },
        )
        val vm = NodeStatusViewModel(
            scope = backgroundScope,
            session = session,
            dependencies = NodeStatusViewModel.Dependencies(
                repeaterAdminService = { h.admin },
                contactService = { contactService },
                radioId = { radioId },
                isRadioConnected = { false },
            ),
        )
        runCurrent()
        vm.selectOcvPreset(OCVPreset.LI_ION)
        runCurrent()
        assertEquals(
            OCVPreset.LI_FE_PO4.rawValue,
            store.fetchContact(contactId)?.ocvPreset,
        )
        assertEquals(
            NodeSectionError.Message(NodeStatusViewModel.OCV_SAVE_NO_CONTACT_KEY),
            vm.state.value.ocvError,
        )
    }

    @Test
    fun commitOcvValuesPersistsCustomCurve() = runTest {
        val h = StatusHarness(backgroundScope)
        val publicKey = testPublicKey()
        val session = testSession(publicKey = publicKey, permissionLevel = RoomPermissionLevel.ADMIN)
        h.store.saveSession(session)
        val radioId = Uuid.normalized("R0000000-0000-0000-0000-000000000002")
        val contactId = Uuid.normalized("C0000000-0000-0000-0000-000000000002")
        val store = InMemoryContactStore()
        store.saveContact(
            repeaterContact(
                id = contactId,
                radioID = radioId,
                publicKey = publicKey,
                preset = OCVPreset.LI_ION,
            ),
        )
        val contactService = ContactService(
            session = NoOpContactSession,
            store = store,
            now = { 0L },
        )
        val vm = NodeStatusViewModel(
            scope = backgroundScope,
            session = session,
            dependencies = NodeStatusViewModel.Dependencies(
                repeaterAdminService = { h.admin },
                contactService = { contactService },
                radioId = { radioId },
            ),
        )
        vm.setBatteryCurveExpanded(true)
        runCurrent()
        assertTrue(vm.state.value.ocvLoaded)

        val custom = listOf(4000, 3900, 3800, 3700, 3600, 3500, 3400, 3300, 3200, 3100, 3000)
        vm.commitOcvValues(custom)
        runCurrent()
        assertEquals(OCVPreset.CUSTOM, vm.state.value.ocvPreset)
        assertEquals(custom, vm.state.value.ocvValues)
        val saved = store.fetchContact(contactId)
        assertEquals(OCVPreset.CUSTOM.rawValue, saved?.ocvPreset)
        assertEquals(BatteryOcv.encodeCustomArray(custom), saved?.customOCVArrayString)
    }

    @Test
    fun commitOcvValuesRejectsAscendingWithoutPersisting() = runTest {
        val h = StatusHarness(backgroundScope)
        val publicKey = testPublicKey()
        val session = testSession(publicKey = publicKey, permissionLevel = RoomPermissionLevel.ADMIN)
        h.store.saveSession(session)
        val radioId = Uuid.normalized("R0000000-0000-0000-0000-000000000003")
        val contactId = Uuid.normalized("C0000000-0000-0000-0000-000000000003")
        val store = InMemoryContactStore()
        store.saveContact(
            repeaterContact(
                id = contactId,
                radioID = radioId,
                publicKey = publicKey,
                preset = OCVPreset.LI_ION,
            ),
        )
        val contactService = ContactService(
            session = NoOpContactSession,
            store = store,
            now = { 0L },
        )
        val vm = NodeStatusViewModel(
            scope = backgroundScope,
            session = session,
            dependencies = NodeStatusViewModel.Dependencies(
                repeaterAdminService = { h.admin },
                contactService = { contactService },
                radioId = { radioId },
            ),
        )
        runCurrent()
        val bad = listOf(4190, 4050, 4100, 3890, 3800, 3720, 3630, 3530, 3420, 3300, 3100)
        vm.commitOcvValues(bad)
        runCurrent()
        assertEquals(OCVPreset.LI_ION, vm.state.value.ocvPreset)
        assertEquals("not_descending", (vm.state.value.ocvError as? NodeSectionError.Message)?.text)
        assertNull(store.fetchContact(contactId)?.customOCVArrayString)
    }

    @Test
    fun aFailedOcvLoadMarksTheCurveUnavailable() = runTest {
        val store = object : ContactStore by InMemoryContactStore() {
            override suspend fun fetchContact(radioID: Uuid, publicKey: ByteArray): ContactDto? = error("disk full")
        }
        val vm = ocvStatusViewModel(store)
        vm.setBatteryCurveExpanded(true)
        runCurrent()
        assertTrue(vm.state.value.ocvLoaded)
        assertEquals(
            NodeSectionError.Message(NodeStatusViewModel.OCV_LOAD_FAILED_KEY),
            vm.state.value.ocvError,
        )
    }

    @Test
    fun aCancelledOcvLoadLeavesTheCurveUnset() = runTest {
        var failure: Throwable? = CancellationException("superseded")
        val backing = InMemoryContactStore()
        val publicKey = testPublicKey()
        backing.saveContact(repeaterContact(publicKey, OCVPreset.LI_FE_PO4))
        val store = object : ContactStore by backing {
            override suspend fun fetchContact(radioID: Uuid, publicKey: ByteArray): ContactDto? {
                failure?.let { throw it }
                return backing.fetchContact(radioID, publicKey)
            }
        }
        val vm = ocvStatusViewModel(store, publicKey)
        vm.setBatteryCurveExpanded(true)
        runCurrent()
        assertFalse(vm.state.value.ocvLoaded)
        assertNull(vm.state.value.ocvError)

        failure = null
        vm.loadOcvSettings()
        runCurrent()
        assertTrue(vm.state.value.ocvLoaded)
        assertEquals(OCVPreset.LI_FE_PO4, vm.state.value.ocvPreset)
        assertNull(vm.state.value.ocvError)
    }

    @Test
    fun aFailedOcvSaveReportsTheSaveError() = runTest {
        val backing = InMemoryContactStore()
        val publicKey = testPublicKey()
        backing.saveContact(repeaterContact(publicKey, OCVPreset.LI_FE_PO4))
        val store = object : ContactStore by backing {
            override suspend fun updateContactFields(id: Uuid, update: (ContactDto) -> ContactDto): ContactDto? {
                error("disk full")
            }
        }
        val vm = ocvStatusViewModel(store, publicKey)
        vm.setBatteryCurveExpanded(true)
        runCurrent()
        assertEquals(OCVPreset.LI_FE_PO4, vm.state.value.ocvPreset)

        vm.selectOcvPreset(OCVPreset.LI_ION)
        runCurrent()
        assertFalse(vm.state.value.isSavingOcv)
        assertEquals(
            NodeSectionError.Message(NodeStatusViewModel.OCV_SAVE_FAILED_KEY),
            vm.state.value.ocvError,
        )
        assertEquals(OCVPreset.LI_FE_PO4.rawValue, backing.fetchContact(repeaterContactId)?.ocvPreset)
    }

    @Test
    fun aCancelledOcvSaveClearsTheBusyFlagWithoutAnError() = runTest {
        var saveFailure: Throwable? = null
        val backing = InMemoryContactStore()
        val publicKey = testPublicKey()
        backing.saveContact(repeaterContact(publicKey, OCVPreset.LI_FE_PO4))
        val store = object : ContactStore by backing {
            override suspend fun updateContactFields(id: Uuid, update: (ContactDto) -> ContactDto): ContactDto? {
                saveFailure?.let { throw it }
                return backing.updateContactFields(id, update)
            }
        }
        val vm = ocvStatusViewModel(store, publicKey)
        vm.setBatteryCurveExpanded(true)
        runCurrent()
        saveFailure = CancellationException("superseded")

        vm.selectOcvPreset(OCVPreset.LI_ION)
        runCurrent()
        assertFalse(vm.state.value.isSavingOcv)
        assertNull(vm.state.value.ocvError)
        assertEquals(OCVPreset.LI_FE_PO4.rawValue, backing.fetchContact(repeaterContactId)?.ocvPreset)

        saveFailure = null
        vm.selectOcvPreset(OCVPreset.LILY_GO_T_BEAM_1W)
        runCurrent()
        assertFalse(vm.state.value.isSavingOcv)
        assertNull(vm.state.value.ocvError)
        assertEquals(
            OCVPreset.LILY_GO_T_BEAM_1W.rawValue,
            backing.fetchContact(repeaterContactId)?.ocvPreset,
        )
    }

    private val repeaterRadioId = Uuid.normalized("R0000000-0000-0000-0000-0000000000A1")
    private val repeaterContactId = Uuid.normalized("C0000000-0000-0000-0000-0000000000A1")

    private suspend fun TestScope.ocvStatusViewModel(
        store: ContactStore,
        publicKey: ByteArray = testPublicKey(),
    ): NodeStatusViewModel {
        val harness = StatusHarness(backgroundScope)
        val session = testSession(publicKey = publicKey, permissionLevel = RoomPermissionLevel.ADMIN)
        harness.store.saveSession(session)
        val contactService = ContactService(session = NoOpContactSession, store = store, now = { 0L })
        return NodeStatusViewModel(
            scope = backgroundScope,
            session = session,
            dependencies = NodeStatusViewModel.Dependencies(
                repeaterAdminService = { harness.admin },
                contactService = { contactService },
                radioId = { repeaterRadioId },
            ),
        )
    }

    private fun repeaterContact(
        publicKey: ByteArray,
        preset: OCVPreset,
        id: Uuid = repeaterContactId,
        radioID: Uuid = repeaterRadioId,
    ): ContactDto = ContactDto(
        id = id,
        radioID = radioID,
        publicKey = publicKey,
        name = "Repeater",
        typeRawValue = ContactType.REPEATER.raw,
        flags = 0,
        outPathLength = 0,
        outPath = ByteArray(0),
        lastAdvertTimestamp = 0,
        latitude = 0.0,
        longitude = 0.0,
        lastModified = 0,
        isBlocked = false,
        isMuted = false,
        isFavorite = false,
        unreadCount = 0,
        ocvPreset = preset.rawValue,
    )

    @Test
    fun transientNoResponseYetRetriesInsteadOfFailingTheSection() = runTest {
        val h = StatusHarness(backgroundScope)
        val vm = viewModel(h)
        // Firmware reports "no response yet" while the multi-hop reply is still in flight.
        h.nodeSession.statusErrorForCall = { call ->
            if (call <= 2) {
                RemoteNodeError.SessionError(
                    MeshError.DeviceError(RemoteSectionRetryPolicy.REMOTE_NODE_NO_RESPONSE_YET),
                )
            } else {
                null
            }
        }

        vm.requestStatus()
        runCurrent()
        // Step past the 500 ms and 1 s rungs of the ladder.
        advanceTimeBy(RETRY_LADDER_STEP)
        runCurrent()

        assertEquals(3, h.nodeSession.requestStatusCalls.size)
        assertNull(vm.state.value.statusSectionError)
        assertTrue(vm.state.value.statusLoaded)
        assertNotNull(vm.state.value.status)
    }

    @Test
    fun statusSnapshotRecordsRepeaterRxAirtimeAndReceiveErrors() = runTest {
        val h = StatusHarness(backgroundScope)
        val publicKey = testPublicKey()
        val session = testSession(publicKey = publicKey, permissionLevel = RoomPermissionLevel.ADMIN)
        h.store.saveSession(session)
        val store = InMemoryNodeSnapshotStore()
        val snapshots = NodeSnapshotService(store)
        h.nodeSession.statusResponse = testStatusResponse(publicKey = publicKey)
        val vm = NodeStatusViewModel(
            scope = backgroundScope,
            session = session,
            dependencies = NodeStatusViewModel.Dependencies(
                repeaterAdminService = { h.admin },
                nodeSnapshotService = { snapshots },
            ),
        )

        vm.requestStatus()
        runCurrent()

        val snapshot = assertNotNull(store.fetchLatestSnapshot(publicKey))
        assertEquals(100L, snapshot.rxAirtimeSeconds)
        assertEquals(3L, snapshot.receiveErrors)
    }

    private companion object {
        val RETRY_LADDER_STEP = 4.seconds
    }
}

/** Independent expanded status, telemetry, owner-info and neighbor probes. */
private const val SECTION_PROBE_COUNT = 4

private class StatusSectionProbeError : Error("probe")

/** Collapsed repeater status must not resolve data providers. */
@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.quietRepeaterStatus(): QuietRepeaterStatus {
    val reads = QuietRepeaterStatusReads()
    val vm = NodeStatusViewModel(
        scope = backgroundScope,
        session = testSession(),
        dependencies = NodeStatusViewModel.Dependencies(
            repeaterAdminService = {
                reads.adminReads += 1
                null
            },
            contactService = {
                reads.contactReads += 1
                null
            },
            radioId = {
                reads.radioReads += 1
                null
            },
        ),
    )
    runCurrent()
    val adminAfterInit = reads.adminReads
    assertEquals(0, adminAfterInit)
    assertEquals(0, reads.contactReads)
    assertEquals(0, reads.radioReads)
    return QuietRepeaterStatus(vm, adminAfterInit, reads)
}

private class QuietRepeaterStatus(
    val vm: NodeStatusViewModel,
    private val adminAfterInit: Int,
    val reads: QuietRepeaterStatusReads,
) {
    fun assertStillQuiet() {
        assertEquals(adminAfterInit, reads.adminReads)
        assertEquals(0, reads.contactReads)
        assertEquals(0, reads.radioReads)
    }
}

private class QuietRepeaterStatusReads {
    var adminReads = 0
    var contactReads = 0
    var radioReads = 0
}
