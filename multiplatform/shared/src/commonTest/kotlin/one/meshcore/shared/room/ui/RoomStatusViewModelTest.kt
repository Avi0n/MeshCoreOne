package one.meshcore.shared.room.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.backup.TelemetrySnapshotEntry
import one.meshcore.shared.battery.OCVPreset
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.ContactStore
import one.meshcore.shared.contact.InMemoryContactStore
import one.meshcore.shared.contact.NoOpContactSession
import one.meshcore.shared.meshcore.TelemetryResponse
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.remotenode.InMemoryNodeSnapshotStore
import one.meshcore.shared.remotenode.LPPDataPoint
import one.meshcore.shared.remotenode.LPPSensorType
import one.meshcore.shared.remotenode.LPPValue
import one.meshcore.shared.remotenode.MeshError
import one.meshcore.shared.remotenode.NodeSnapshotService
import one.meshcore.shared.remotenode.NodeStatusMetrics
import one.meshcore.shared.remotenode.testPublicKey
import one.meshcore.shared.remotenode.ui.NodeSectionError
import one.meshcore.shared.remotenode.voltagePoint
import one.meshcore.shared.room.FakeRoomAdminSession
import one.meshcore.shared.room.InMemoryRoomStore
import one.meshcore.shared.room.RoomAdminService
import one.meshcore.shared.room.RoomServerError
import one.meshcore.shared.room.roomSession
import one.meshcore.shared.room.roomStatus
import one.meshcore.shared.room.roomTelemetry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Section-scoped load/error behavior for room status: posts counters, live LPP telemetry, OCV battery
 * curve, and snapshot capture into [NodeSnapshotService].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoomStatusViewModelTest {
    private class Harness {
        val store = InMemoryRoomStore()
        val fake = FakeRoomAdminSession()
        val admin = RoomAdminService(fake, store)
        val snapshots = NodeSnapshotService(InMemoryNodeSnapshotStore())
    }

    private suspend fun TestScope.viewModel(harness: Harness): RoomStatusViewModel {
        val session = roomSession()
        harness.store.seedSession(session)
        return RoomStatusViewModel(
            scope = backgroundScope,
            session = session,
            dependencies = RoomStatusViewModel.Dependencies(
                roomAdminService = { harness.admin },
                nodeSnapshotService = { harness.snapshots },
            ),
        )
    }

    @Test
    fun closePreventsFurtherRequestsFromReachingTheRadio() = runTest {
        val harness = Harness()
        val vm = viewModel(harness)
        runCurrent()
        val statusCalls = harness.fake.statusCalls.size
        val telemetryCalls = harness.fake.telemetryCalls.size

        vm.close()
        vm.requestStatus()
        vm.requestTelemetry()
        runCurrent()

        assertEquals(statusCalls, harness.fake.statusCalls.size)
        assertEquals(telemetryCalls, harness.fake.telemetryCalls.size)
    }

    @Test
    fun keepingAnUnchangedRoomBatteryPresetDoesNotRadio() = runTest {
        var adminReads = 0
        var contactReads = 0
        var radioReads = 0
        val vm = RoomStatusViewModel(
            scope = backgroundScope,
            session = roomSession(),
            dependencies = RoomStatusViewModel.Dependencies(
                roomAdminService = {
                    adminReads += 1
                    null
                },
                contactService = {
                    contactReads += 1
                    null
                },
                radioId = {
                    radioReads += 1
                    null
                },
            ),
        )
        runCurrent()
        assertEquals(0, adminReads)
        assertEquals(0, contactReads)
        assertEquals(0, radioReads)
        assertEquals(OCVPreset.LI_ION, vm.state.value.ocvPreset)
        assertEquals(OCVPreset.LI_ION.ocvArray, vm.state.value.ocvValues)
        assertFalse(vm.state.value.ocvLoaded)
        assertNull(vm.state.value.ocvError)
        assertFalse(vm.state.value.batteryCurveExpanded)

        vm.commitOcvValues(OCVPreset.LI_ION.ocvArray)
        runCurrent()

        assertEquals(0, adminReads)
        assertEquals(0, contactReads)
        assertEquals(0, radioReads)
        assertEquals(OCVPreset.LI_ION, vm.state.value.ocvPreset)
        assertEquals(OCVPreset.LI_ION.ocvArray, vm.state.value.ocvValues)
        assertFalse(vm.state.value.ocvLoaded)
        assertNull(vm.state.value.ocvError)
        assertFalse(vm.state.value.batteryCurveExpanded)
    }

    @Test
    fun keepingRoomStatusCollapsedDoesNotRadio() = runTest {
        var adminReads = 0
        var contactReads = 0
        var radioReads = 0
        val vm = RoomStatusViewModel(
            scope = backgroundScope,
            session = roomSession(),
            dependencies = RoomStatusViewModel.Dependencies(
                roomAdminService = {
                    adminReads += 1
                    null
                },
                contactService = {
                    contactReads += 1
                    null
                },
                radioId = {
                    radioReads += 1
                    null
                },
            ),
        )
        runCurrent()
        assertEquals(0, adminReads)
        assertEquals(0, contactReads)
        assertEquals(0, radioReads)
        assertFalse(vm.state.value.statusExpanded)
        assertFalse(vm.state.value.statusLoaded)
        assertFalse(vm.state.value.isLoadingStatus)
        assertNull(vm.state.value.statusSectionError)
        assertNull(vm.state.value.status)

        vm.setStatusExpanded(false)
        runCurrent()

        assertEquals(0, adminReads)
        assertEquals(0, contactReads)
        assertEquals(0, radioReads)
        assertFalse(vm.state.value.statusExpanded)
        assertFalse(vm.state.value.statusLoaded)
        assertFalse(vm.state.value.isLoadingStatus)
        assertNull(vm.state.value.statusSectionError)
        assertNull(vm.state.value.status)
    }

    @Test
    fun firstExpandLoadsStatusWithRoomPosts() = runTest {
        val h = Harness()
        val vm = viewModel(h)
        h.fake.statusResponse = roomStatus(posted = 42, pushed = 7)

        assertFalse(vm.state.value.statusLoaded)
        vm.setStatusExpanded(true)
        runCurrent()

        assertTrue(vm.state.value.statusExpanded)
        assertTrue(vm.state.value.statusLoaded)
        val status = assertNotNull(vm.state.value.status)
        assertEquals(42, status.roomServerPostedCount)
        assertEquals(7, status.roomServerPostPushCount)
        assertEquals(1, h.fake.statusCalls.size)

        vm.setStatusExpanded(false)
        vm.setStatusExpanded(true)
        runCurrent()
        assertEquals(1, h.fake.statusCalls.size)
    }

    @Test
    fun requestStatusUnknownSessionSurfacesFailed() = runTest {
        val h = Harness()
        val vm = RoomStatusViewModel(
            scope = backgroundScope,
            session = roomSession(),
            dependencies = RoomStatusViewModel.Dependencies(roomAdminService = { h.admin }),
        )
        vm.requestStatus()
        runCurrent()
        assertIs<NodeSectionError.Failed>(vm.state.value.statusSectionError)
        assertFalse(vm.state.value.isLoadingStatus)
        assertNull(vm.state.value.status)
    }

    @Test
    fun firstTelemetryExpandDecodesPointsAndRecordsSnapshot() = runTest {
        val h = Harness()
        val publicKey = testPublicKey()
        val session = roomSession(publicKey = publicKey)
        h.store.seedSession(session)
        h.fake.telemetryResponse = TelemetryResponse(
            publicKeyPrefix = publicKey.copyOf(6),
            tag = null,
            rawData = ByteArray(0),
            points = listOf(voltagePoint()),
        )
        val vm = RoomStatusViewModel(
            scope = backgroundScope,
            session = session,
            dependencies = RoomStatusViewModel.Dependencies(
                roomAdminService = { h.admin },
                nodeSnapshotService = { h.snapshots },
            ),
        )

        vm.setTelemetryExpanded(true)
        runCurrent()

        assertTrue(vm.state.value.telemetryLoaded)
        assertEquals(1, h.fake.telemetryCalls.size)
        assertNull(vm.state.value.telemetrySectionError)
        assertEquals(1, vm.state.value.telemetryDataPoints.size)
        val volts = (vm.state.value.telemetryDataPoints[0].value as LPPValue.FloatValue).value
        assertEquals(3.99, volts, absoluteTolerance = 0.001)

        vm.setTelemetryExpanded(false)
        vm.setTelemetryExpanded(true)
        runCurrent()
        assertEquals(1, h.fake.telemetryCalls.size)

        val rows = h.snapshots.fetchSnapshots(publicKey)
        assertEquals(1, rows.size)
        assertEquals(
            listOf(TelemetrySnapshotEntry(1, "Voltage", 3.99)),
            rows[0].telemetryEntries.orEmpty(),
        )
    }

    @Test
    fun emptyTelemetryRawMarksLoadedWithNoSensorRows() = runTest {
        val h = Harness()
        val vm = viewModel(h)
        h.fake.telemetryResponse = roomTelemetry()

        vm.setTelemetryExpanded(true)
        runCurrent()

        assertTrue(vm.state.value.telemetryLoaded)
        assertTrue(vm.state.value.telemetryDataPoints.isEmpty())
    }

    @Test
    fun gpsOnlyTelemetryRecordsALocationSnapshot() = runTest {
        val h = Harness()
        val publicKey = testPublicKey()
        val session = roomSession(publicKey = publicKey)
        h.store.seedSession(session)
        h.fake.telemetryResponse = TelemetryResponse(
            publicKeyPrefix = publicKey.copyOf(6),
            tag = null,
            rawData = ByteArray(0),
            points = listOf(
                LPPDataPoint(1, LPPSensorType.GPS, LPPValue.Gps(GPS_LATITUDE, GPS_LONGITUDE, GPS_ALTITUDE)),
            ),
        )
        val vm = RoomStatusViewModel(
            scope = backgroundScope,
            session = session,
            dependencies = RoomStatusViewModel.Dependencies(
                roomAdminService = { h.admin },
                nodeSnapshotService = { h.snapshots },
            ),
        )

        vm.requestTelemetry()
        runCurrent()

        val fix = assertNotNull(vm.state.value.currentLocationFix)
        assertEquals(GPS_LATITUDE, fix.latitude)
        assertEquals(GPS_LONGITUDE, fix.longitude)
        val rows = h.snapshots.fetchSnapshots(publicKey)
        assertEquals(1, rows.size)
        assertEquals(GPS_LATITUDE, rows[0].latitude)
        assertEquals(GPS_LONGITUDE, rows[0].longitude)
        assertTrue(rows[0].telemetryEntries.isNullOrEmpty())
    }

    @Test
    fun statusTimeoutSurfacesTheTimeoutSectionError() = runTest {
        val h = Harness()
        val vm = viewModel(h)
        // The room session adapter reports a device timeout as a bare MeshError; the section must show the
        // localized timeout state rather than the error's developer text.
        h.fake.statusError = MeshError.Timeout

        vm.requestStatus()
        runCurrent()

        assertIs<NodeSectionError.Timeout>(vm.state.value.statusSectionError)
        assertFalse(vm.state.value.statusLoaded)
        assertFalse(vm.state.value.isLoadingStatus)
    }

    @Test
    fun telemetryTimeoutSurfacesSectionError() = runTest {
        val h = Harness()
        val vm = viewModel(h)
        h.fake.telemetryError = RoomServerError.SessionError(MeshError.Timeout)

        vm.requestTelemetry()
        runCurrent()

        assertIs<NodeSectionError.Timeout>(vm.state.value.telemetrySectionError)
        assertFalse(vm.state.value.telemetryLoaded)
        assertFalse(vm.state.value.isLoadingTelemetry)
    }

    @Test
    fun requestTelemetryUnknownSessionSurfacesFailed() = runTest {
        val h = Harness()
        val vm = RoomStatusViewModel(
            scope = backgroundScope,
            session = roomSession(),
            dependencies = RoomStatusViewModel.Dependencies(roomAdminService = { h.admin }),
        )
        vm.requestTelemetry()
        runCurrent()
        assertIs<NodeSectionError.Failed>(vm.state.value.telemetrySectionError)
        assertFalse(vm.state.value.isLoadingTelemetry)
        assertFalse(vm.state.value.telemetryLoaded)
    }

    @Test
    fun aFailedStatusRequestSurfacesTheSectionMessage() = runTest {
        val h = Harness()
        val vm = viewModel(h)
        h.fake.statusError = IllegalStateException("link down")

        vm.requestStatus()
        runCurrent()

        assertEquals(NodeSectionError.Message("link down"), vm.state.value.statusSectionError)
        assertFalse(vm.state.value.isLoadingStatus)
        assertNull(vm.state.value.status)
    }

    @Test
    fun aCancelledStatusRequestClearsLoadingWithoutASectionError() = runTest {
        val h = Harness()
        val vm = viewModel(h)
        h.fake.statusError = CancellationException("superseded")

        vm.requestStatus()
        runCurrent()

        assertFalse(vm.state.value.isLoadingStatus)
        assertNull(vm.state.value.statusSectionError)
        assertNull(vm.state.value.status)
    }

    @Test
    fun aFailedTelemetryRequestSurfacesTheSectionMessage() = runTest {
        val h = Harness()
        val vm = viewModel(h)
        h.fake.telemetryError = IllegalStateException("link down")

        vm.requestTelemetry()
        runCurrent()

        assertEquals(NodeSectionError.Message("link down"), vm.state.value.telemetrySectionError)
        assertFalse(vm.state.value.isLoadingTelemetry)
        assertFalse(vm.state.value.telemetryLoaded)
    }

    @Test
    fun aCancelledTelemetryRequestClearsLoadingWithoutASectionError() = runTest {
        val h = Harness()
        val vm = viewModel(h)
        h.fake.telemetryError = CancellationException("superseded")

        vm.requestTelemetry()
        runCurrent()

        assertFalse(vm.state.value.isLoadingTelemetry)
        assertNull(vm.state.value.telemetrySectionError)
        assertFalse(vm.state.value.telemetryLoaded)
    }

    @Test
    fun aSectionRequestErrorPropagatesWithoutASectionError() = runTest {
        val errors = mutableListOf<Throwable>()
        val scope = CoroutineScope(
            SupervisorJob() +
                UnconfinedTestDispatcher(testScheduler) +
                CoroutineExceptionHandler { _, error -> errors += error },
        )
        val h = Harness()
        val session = roomSession()
        h.store.seedSession(session)
        val statusProbe = RoomStatusProbeError()
        val telemetryProbe = RoomStatusProbeError()
        h.fake.statusError = statusProbe
        h.fake.telemetryError = telemetryProbe
        val vm = RoomStatusViewModel(
            scope = scope,
            session = session,
            dependencies = RoomStatusViewModel.Dependencies(roomAdminService = { h.admin }),
        )

        vm.setStatusExpanded(true)
        vm.setTelemetryExpanded(true)

        assertEquals(2, errors.size)
        assertSame(statusProbe, errors[0])
        assertSame(telemetryProbe, errors[1])
        assertTrue(vm.state.value.isLoadingStatus)
        assertTrue(vm.state.value.isLoadingTelemetry)
        assertNull(vm.state.value.statusSectionError)
        assertNull(vm.state.value.telemetrySectionError)
        scope.cancel()
    }

    @Test
    fun statusCaptureRecordsSnapshotWithPosts() = runTest {
        val h = Harness()
        val vm = viewModel(h)
        h.fake.statusResponse = roomStatus(posted = 11, pushed = 3)
        val session = roomSession()

        vm.requestStatus()
        runCurrent()
        runCurrent()

        val latest = h.snapshots.fetchSnapshots(session.publicKey).lastOrNull()
        assertNotNull(latest)
        assertEquals(11, latest.postedCount)
        assertEquals(3, latest.postPushCount)
    }

    @Test
    fun statusLoadExposesPreviousSnapshotDeltas() = runTest {
        val publicKey = testPublicKey()
        val session = roomSession(publicKey = publicKey)
        val store = InMemoryRoomStore()
        store.seedSession(session)
        val fake = FakeRoomAdminSession()
        val admin = RoomAdminService(fake, store)
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
        val vm = RoomStatusViewModel(
            scope = backgroundScope,
            session = session,
            dependencies = RoomStatusViewModel.Dependencies(
                roomAdminService = { admin },
                nodeSnapshotService = { snapshots },
                nowEpochSeconds = { clock },
            ),
        )
        fake.statusResponse = roomStatus().copy(
            publicKeyPrefix = publicKey.copyOf(6),
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
    fun loadAndSaveOcvPreset() = runTest {
        val h = Harness()
        val publicKey = testPublicKey()
        val session = roomSession(publicKey = publicKey)
        h.store.seedSession(session)
        val radioId = Uuid.normalized("R0000000-0000-0000-0000-0000000000A1")
        val contactId = Uuid.normalized("C0000000-0000-0000-0000-0000000000A1")
        val store = InMemoryContactStore()
        store.saveContact(
            ContactDto(
                id = contactId,
                radioID = radioId,
                publicKey = publicKey,
                name = "Room Server",
                typeRawValue = ContactType.ROOM.raw,
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
                ocvPreset = OCVPreset.LI_FE_PO4.rawValue,
            ),
        )
        val contactService = ContactService(
            session = NoOpContactSession,
            store = store,
            now = { 0L },
        )
        val vm = RoomStatusViewModel(
            scope = backgroundScope,
            session = session,
            dependencies = RoomStatusViewModel.Dependencies(
                roomAdminService = { h.admin },
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
    fun commitOcvValuesRejectsAscendingWithoutPersisting() = runTest {
        val h = Harness()
        val publicKey = testPublicKey()
        val session = roomSession(publicKey = publicKey)
        h.store.seedSession(session)
        val radioId = Uuid.normalized("R0000000-0000-0000-0000-0000000000A2")
        val contactId = Uuid.normalized("C0000000-0000-0000-0000-0000000000A2")
        val store = InMemoryContactStore()
        store.saveContact(
            ContactDto(
                id = contactId,
                radioID = radioId,
                publicKey = publicKey,
                name = "Room Server",
                typeRawValue = ContactType.ROOM.raw,
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
                ocvPreset = OCVPreset.LI_ION.rawValue,
            ),
        )
        val contactService = ContactService(
            session = NoOpContactSession,
            store = store,
            now = { 0L },
        )
        val vm = RoomStatusViewModel(
            scope = backgroundScope,
            session = session,
            dependencies = RoomStatusViewModel.Dependencies(
                roomAdminService = { h.admin },
                contactService = { contactService },
                radioId = { radioId },
            ),
        )
        vm.setBatteryCurveExpanded(true)
        runCurrent()
        assertTrue(vm.state.value.ocvLoaded)

        val bad = listOf(3000, 3100, 3200, 3300, 3400, 3500, 3600, 3700, 3800, 3900, 4000)
        vm.commitOcvValues(bad)
        runCurrent()
        assertEquals(OCVPreset.LI_ION, vm.state.value.ocvPreset)
        assertEquals("not_descending", (vm.state.value.ocvError as? NodeSectionError.Message)?.text)
        assertNull(store.fetchContact(contactId)?.customOCVArrayString)
    }

    private companion object {
        const val GPS_LATITUDE = 10.0
        const val GPS_LONGITUDE = 20.0
        const val GPS_ALTITUDE = 100.0
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class RoomStatusOcvFaultTest {
    @Test
    fun aFailedOcvLoadMarksTheCurveUnavailable() = runTest {
        val vm = ocvRoom(
            object : ContactStore by InMemoryContactStore() {
                override suspend fun fetchContact(radioID: Uuid, publicKey: ByteArray): ContactDto? = error("disk full")
            },
        )
        vm.setBatteryCurveExpanded(true)
        runCurrent()
        assertTrue(vm.state.value.ocvLoaded)
        assertEquals(
            NodeSectionError.Message(RoomStatusViewModel.OCV_LOAD_FAILED_KEY),
            vm.state.value.ocvError,
        )
    }

    @Test
    fun aCancelledOcvLoadLeavesTheCurveUnset() = runTest {
        val backing = seededOcvStore()
        var fetches = 0
        val vm = ocvRoom(
            object : ContactStore by backing {
                override suspend fun fetchContact(radioID: Uuid, publicKey: ByteArray): ContactDto? {
                    fetches += 1
                    if (fetches == 1) throw CancellationException("superseded")
                    return backing.fetchContact(radioID, publicKey)
                }
            },
        )
        vm.setBatteryCurveExpanded(true)
        runCurrent()
        assertFalse(vm.state.value.ocvLoaded)
        assertNull(vm.state.value.ocvError)

        vm.setBatteryCurveExpanded(true)
        runCurrent()
        assertTrue(vm.state.value.ocvLoaded)
        assertEquals(OCVPreset.LI_FE_PO4, vm.state.value.ocvPreset)
        assertNull(vm.state.value.ocvError)
    }

    @Test
    fun aFailedOcvSaveReportsTheSaveError() = runTest {
        val backing = seededOcvStore()
        val vm = ocvRoom(
            object : ContactStore by backing {
                override suspend fun updateContactFields(id: Uuid, update: (ContactDto) -> ContactDto): ContactDto? =
                    error("disk full")
            },
        )
        vm.setBatteryCurveExpanded(true)
        runCurrent()
        assertEquals(OCVPreset.LI_FE_PO4, vm.state.value.ocvPreset)

        vm.selectOcvPreset(OCVPreset.LI_ION)
        runCurrent()

        assertEquals(OCVPreset.LI_ION, vm.state.value.ocvPreset)
        assertFalse(vm.state.value.isSavingOcv)
        assertEquals(
            NodeSectionError.Message(RoomStatusViewModel.OCV_SAVE_FAILED_KEY),
            vm.state.value.ocvError,
        )
        assertEquals(OCVPreset.LI_FE_PO4.rawValue, backing.fetchContact(ROOM_OCV_CONTACT_ID)?.ocvPreset)
    }

    @Test
    fun aCancelledOcvSaveClearsTheBusyFlagWithoutAnError() = runTest {
        val backing = seededOcvStore()
        var saves = 0
        val vm = ocvRoom(
            object : ContactStore by backing {
                override suspend fun updateContactFields(id: Uuid, update: (ContactDto) -> ContactDto): ContactDto? {
                    saves += 1
                    if (saves == 1) throw CancellationException("superseded")
                    return backing.updateContactFields(id, update)
                }
            },
        )
        vm.setBatteryCurveExpanded(true)
        runCurrent()
        vm.selectOcvPreset(OCVPreset.LI_ION)
        runCurrent()
        assertEquals(OCVPreset.LI_ION, vm.state.value.ocvPreset)
        assertFalse(vm.state.value.isSavingOcv)
        assertNull(vm.state.value.ocvError)
        assertEquals(OCVPreset.LI_FE_PO4.rawValue, backing.fetchContact(ROOM_OCV_CONTACT_ID)?.ocvPreset)

        vm.selectOcvPreset(OCVPreset.LILY_GO_T_BEAM_1W)
        runCurrent()
        assertEquals(
            OCVPreset.LILY_GO_T_BEAM_1W.rawValue,
            backing.fetchContact(ROOM_OCV_CONTACT_ID)?.ocvPreset,
        )
        assertNull(vm.state.value.ocvError)
        assertFalse(vm.state.value.isSavingOcv)
    }

    @Test
    fun anOcvLoadErrorPropagatesWithoutMarkingTheCurveUnavailable() = runTest {
        val probe = RoomStatusProbeError()
        val errors = mutableListOf<Throwable>()
        val scope = CoroutineScope(
            SupervisorJob() +
                UnconfinedTestDispatcher(testScheduler) +
                CoroutineExceptionHandler { _, error -> errors += error },
        )
        val vm = ocvRoom(
            store = object : ContactStore by InMemoryContactStore() {
                override suspend fun fetchContact(radioID: Uuid, publicKey: ByteArray): ContactDto? = throw probe
            },
            scope = scope,
        )
        vm.setBatteryCurveExpanded(true)
        assertSame(probe, errors.single())
        assertFalse(vm.state.value.ocvLoaded)
        assertNull(vm.state.value.ocvError)
        scope.cancel()
    }

    @Test
    fun anOcvSaveErrorPropagatesWithoutASaveFailure() = runTest {
        val probe = RoomStatusProbeError()
        val errors = mutableListOf<Throwable>()
        val scope = CoroutineScope(
            SupervisorJob() +
                UnconfinedTestDispatcher(testScheduler) +
                CoroutineExceptionHandler { _, error -> errors += error },
        )
        val backing = seededOcvStore()
        val vm = ocvRoom(
            store = object : ContactStore by backing {
                override suspend fun updateContactFields(id: Uuid, update: (ContactDto) -> ContactDto): ContactDto? =
                    throw probe
            },
            scope = scope,
        )

        vm.setBatteryCurveExpanded(true)
        vm.selectOcvPreset(OCVPreset.LI_ION)

        assertSame(probe, errors.single())
        assertNull(vm.state.value.ocvError)
        assertTrue(vm.state.value.isSavingOcv)
        assertEquals(OCVPreset.LI_ION, vm.state.value.ocvPreset)
        assertEquals(OCVPreset.LI_FE_PO4.rawValue, backing.fetchContact(ROOM_OCV_CONTACT_ID)?.ocvPreset)
        scope.cancel()
    }

    private suspend fun TestScope.seededOcvStore(): InMemoryContactStore {
        val store = InMemoryContactStore()
        store.saveContact(roomOcvContact())
        return store
    }

    private fun roomOcvContact() = ContactDto(
        id = ROOM_OCV_CONTACT_ID,
        radioID = ROOM_OCV_RADIO_ID,
        publicKey = testPublicKey(),
        name = "Room Server",
        typeRawValue = ContactType.ROOM.raw,
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
        ocvPreset = OCVPreset.LI_FE_PO4.rawValue,
    )

    private suspend fun TestScope.ocvRoom(
        store: ContactStore,
        scope: CoroutineScope = backgroundScope,
    ): RoomStatusViewModel {
        val roomStore = InMemoryRoomStore()
        val admin = RoomAdminService(FakeRoomAdminSession(), roomStore)
        val session = roomSession(publicKey = testPublicKey())
        roomStore.seedSession(session)
        val contactService = ContactService(session = NoOpContactSession, store = store, now = { 0L })
        return RoomStatusViewModel(
            scope = scope,
            session = session,
            dependencies = RoomStatusViewModel.Dependencies(
                roomAdminService = { admin },
                contactService = { contactService },
                radioId = { ROOM_OCV_RADIO_ID },
            ),
        )
    }

    private companion object {
        val ROOM_OCV_RADIO_ID = Uuid.normalized("R0000000-0000-0000-0000-0000000000A1")
        val ROOM_OCV_CONTACT_ID = Uuid.normalized("C0000000-0000-0000-0000-0000000000A1")
    }
}

private class RoomStatusProbeError : Error("probe")
