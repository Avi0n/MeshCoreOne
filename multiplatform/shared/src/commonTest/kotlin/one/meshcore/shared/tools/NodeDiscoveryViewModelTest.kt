package one.meshcore.shared.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import one.meshcore.shared.contact.AddContactError
import one.meshcore.shared.contact.ContactFrame
import one.meshcore.shared.contact.ContactProtocolErrorCode
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.ContactSession
import one.meshcore.shared.contact.ContactStore
import one.meshcore.shared.contact.FakeContactSession
import one.meshcore.shared.contact.InMemoryContactStore
import one.meshcore.shared.contact.InMemoryDiscoveredNodeStore
import one.meshcore.shared.meshcore.DiscoverResponse
import one.meshcore.shared.meshcore.MeshEvent
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.model.toUppercaseHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NodeDiscoveryViewModelTest {

    @Test
    fun tagToLittleEndianBytesMatchesKnownVector() {
        // 0x44332211 LE → 11 22 33 44
        val bytes = NodeDiscoveryViewModel.tagToLittleEndianBytes(0x4433_2211L)
        assertEquals(listOf(0x11, 0x22, 0x33, 0x44), bytes.map { it.toInt() and 0xFF })
    }

    @Test
    fun scanCollectsTaggedDiscoverResponsesAndStopsAfterWindow() = runTest {
        val events = MutableSharedFlow<MeshEvent>(extraBufferCapacity = 8)
        val tag = 0x1122_3344L
        val tagBytes = NodeDiscoveryViewModel.tagToLittleEndianBytes(tag)
        var unknownNodeLabel = NODE_DISCOVERY_UNKNOWN_NODE
        val radio = Uuid.normalized("A0000000-0000-0000-0000-000000000099")
        val service = ContactService(
            session = FakeContactSession(),
            store = InMemoryContactStore(),
            now = { NOW },
        )
        val vm = NodeDiscoveryViewModel(
            dependencies = NodeDiscoveryViewModel.Dependencies(
                sendDiscover = { _, _ -> tag },
                events = { events },
                contactService = { service },
                discoveredNodeStore = { InMemoryDiscoveredNodeStore() },
                radioId = { radio },
                errorText = { NODE_DISCOVERY_LOCALIZED_ERROR },
                notConnectedMessage = { NODE_DISCOVERY_NOT_CONNECTED },
                scanFailedMessage = { NODE_DISCOVERY_SCAN_FAILED },
                unknownNodeLabel = { unknownNodeLabel },
            ),
            scanDurationMs = 1_000L,
            nowEpochSeconds = { NOW },
        )
        vm.scan(this)
        runCurrent()
        events.emit(discoverEvent(tagBytes, snr = 8.5, keyByte = 0xAB))
        events.emit(discoverEvent(ByteArray(4) { 0 }, snr = 1.0, keyByte = 0xCD)) // wrong tag
        runCurrent()
        assertEquals(1, vm.state.value.sortedResults.size)
        assertEquals(8.5, vm.state.value.sortedResults.first().snr)
        assertEquals("$NODE_DISCOVERY_UNKNOWN_NODE (ABABABAB)", vm.state.value.sortedResults.first().name)

        unknownNodeLabel = "Nœud inconnu"
        events.emit(discoverEvent(tagBytes, snr = 7.5, keyByte = 0xCD))
        runCurrent()
        assertEquals(
            "Nœud inconnu (CDCDCDCD)",
            vm.state.value.results.single { it.publicKey.first() == 0xCD.toByte() }.name,
        )
        assertTrue(vm.state.value.isScanning)
        advanceTimeBy(1_100L)
        runCurrent()
        assertFalse(vm.state.value.isScanning)
        assertTrue(vm.state.value.hasScannedOnce)
    }

    @Test
    fun ownerReplacementCancelsRetiredScanAndClearsRadioOwnedState() = runTest {
        val tag = 0x1122_3344L
        val releaseRetiredCollector = CompletableDeferred<Unit>()
        val retiredEvents = retiringEventFlow(tag, releaseRetiredCollector)
        val vm = scanningViewModel(events = { retiredEvents }, tag = tag)
        vm.setSortOrder(NodeDiscoverySortOrder.NAME)
        vm.scan(this)
        runCurrent()
        assertTrue(vm.state.value.isScanning)
        assertEquals(1, vm.state.value.results.size)

        vm.stopScan(clearOwnedState = true)
        runCurrent()
        releaseRetiredCollector.complete(Unit)
        runCurrent()

        assertFalse(vm.state.value.isScanning)
        assertFalse(vm.state.value.hasScannedOnce)
        assertTrue(vm.state.value.results.isEmpty())
        assertTrue(vm.state.value.addedPublicKeyHexes.isEmpty())
        assertNull(vm.state.value.errorMessage)
        assertEquals(NodeDiscoverySortOrder.NAME, vm.state.value.sortOrder)
    }

    @Test
    fun retiredScanCompletionCannotStopReplacementScan() = runTest {
        val tag = 0x1122_3344L
        val releaseRetiredCollector = CompletableDeferred<Unit>()
        val replacementEvents = MutableSharedFlow<MeshEvent>()
        var events: Flow<MeshEvent> = retiringEventFlow(tag, releaseRetiredCollector)
        val vm = scanningViewModel(events = { events }, tag = tag)
        vm.scan(this)
        runCurrent()
        assertTrue(vm.state.value.isScanning)

        events = replacementEvents
        vm.scan(this)
        runCurrent()
        assertTrue(vm.state.value.isScanning)

        releaseRetiredCollector.complete(Unit)
        runCurrent()

        assertTrue(vm.state.value.isScanning)
        assertFalse(vm.state.value.hasScannedOnce)
        vm.stopScan()
    }

    @Test
    fun scanWithoutEventsShowsNotConnectedAndDoesNotScan() = runTest {
        var sent = 0
        val vm = NodeDiscoveryViewModel(
            dependencies = NodeDiscoveryViewModel.Dependencies(
                sendDiscover = { _, _ ->
                    sent += 1
                    1L
                },
                events = { null },
                contactService = { null },
                discoveredNodeStore = { null },
                radioId = { null },
                notConnectedMessage = { filter -> "no radio for ${filter.name}" },
                errorText = { NODE_DISCOVERY_LOCALIZED_ERROR },
                scanFailedMessage = { NODE_DISCOVERY_SCAN_FAILED },
                unknownNodeLabel = { NODE_DISCOVERY_UNKNOWN_NODE },
            ),
        )
        vm.setFilter(NodeDiscoveryFilter.SENSORS)
        vm.scan(this)
        runCurrent()
        assertEquals(0, sent)
        assertEquals("no radio for SENSORS", vm.state.value.errorMessage)
        assertFalse(vm.state.value.isScanning)
        assertFalse(vm.state.value.hasScannedOnce)
    }

    @Test
    fun choosingASortOrderDoesNotScan() = runTest {
        var sent = 0
        val events = MutableSharedFlow<MeshEvent>(extraBufferCapacity = 1)
        val vm = NodeDiscoveryViewModel(
            dependencies = NodeDiscoveryViewModel.Dependencies(
                sendDiscover = { _, _ ->
                    sent += 1
                    1L
                },
                events = { events },
                contactService = { null },
                discoveredNodeStore = { null },
                radioId = { null },
                errorText = { NODE_DISCOVERY_LOCALIZED_ERROR },
                notConnectedMessage = { NODE_DISCOVERY_NOT_CONNECTED },
                scanFailedMessage = { NODE_DISCOVERY_SCAN_FAILED },
                unknownNodeLabel = { NODE_DISCOVERY_UNKNOWN_NODE },
            ),
        )
        assertEquals(NodeDiscoverySortOrder.SNR, vm.state.value.sortOrder)

        vm.setSortOrder(NodeDiscoverySortOrder.NAME)
        runCurrent()

        assertEquals(0, sent)
        assertEquals(NodeDiscoverySortOrder.NAME, vm.state.value.sortOrder)
        assertFalse(vm.state.value.isScanning)
        assertFalse(vm.state.value.hasScannedOnce)
        assertNull(vm.state.value.errorMessage)
        assertTrue(vm.state.value.sortedResults.isEmpty())
    }

    @Test
    fun choosingADiscoverFilterDoesNotScan() = runTest {
        var sent = 0
        val events = MutableSharedFlow<MeshEvent>(extraBufferCapacity = 1)
        val vm = NodeDiscoveryViewModel(
            dependencies = NodeDiscoveryViewModel.Dependencies(
                sendDiscover = { _, _ ->
                    sent += 1
                    1L
                },
                events = { events },
                contactService = { null },
                discoveredNodeStore = { null },
                radioId = { null },
                errorText = { NODE_DISCOVERY_LOCALIZED_ERROR },
                notConnectedMessage = { NODE_DISCOVERY_NOT_CONNECTED },
                scanFailedMessage = { NODE_DISCOVERY_SCAN_FAILED },
                unknownNodeLabel = { NODE_DISCOVERY_UNKNOWN_NODE },
            ),
        )
        assertEquals(NodeDiscoveryFilter.REPEATERS, vm.state.value.filter)
        assertEquals(NodeDiscoverySortOrder.SNR, vm.state.value.sortOrder)

        vm.setFilter(NodeDiscoveryFilter.SENSORS)
        runCurrent()

        assertEquals(0, sent)
        assertEquals(NodeDiscoveryFilter.SENSORS, vm.state.value.filter)
        assertEquals(NodeDiscoverySortOrder.SNR, vm.state.value.sortOrder)
        assertFalse(vm.state.value.isScanning)
        assertFalse(vm.state.value.hasScannedOnce)
        assertNull(vm.state.value.errorMessage)
        assertTrue(vm.state.value.results.isEmpty())
    }

    @Test
    fun clearingADiscoverErrorDoesNotScan() = runTest {
        var sent = 0
        val vm = NodeDiscoveryViewModel(
            dependencies = NodeDiscoveryViewModel.Dependencies(
                sendDiscover = { _, _ ->
                    sent += 1
                    1L
                },
                events = { null },
                contactService = { null },
                discoveredNodeStore = { null },
                radioId = { null },
                notConnectedMessage = { _ -> "no radio" },
                errorText = { NODE_DISCOVERY_LOCALIZED_ERROR },
                scanFailedMessage = { NODE_DISCOVERY_SCAN_FAILED },
                unknownNodeLabel = { NODE_DISCOVERY_UNKNOWN_NODE },
            ),
        )
        vm.scan(this)
        runCurrent()
        assertEquals(0, sent)
        assertEquals("no radio", vm.state.value.errorMessage)
        assertFalse(vm.state.value.isScanning)
        assertFalse(vm.state.value.hasScannedOnce)

        vm.clearError()
        runCurrent()

        assertEquals(0, sent)
        assertNull(vm.state.value.errorMessage)
        assertNull(vm.state.value.addError)
        assertFalse(vm.state.value.isScanning)
        assertFalse(vm.state.value.hasScannedOnce)
        assertTrue(vm.state.value.results.isEmpty())
    }

    @Test
    fun stoppingAScanDoesNotSendAnotherDiscover() = runTest {
        var sent = 0
        val events = MutableSharedFlow<MeshEvent>(extraBufferCapacity = 1)
        val vm = NodeDiscoveryViewModel(
            dependencies = NodeDiscoveryViewModel.Dependencies(
                sendDiscover = { _, _ ->
                    sent += 1
                    1L
                },
                events = { events },
                contactService = { null },
                discoveredNodeStore = { null },
                radioId = { null },
                errorText = { NODE_DISCOVERY_LOCALIZED_ERROR },
                notConnectedMessage = { NODE_DISCOVERY_NOT_CONNECTED },
                scanFailedMessage = { NODE_DISCOVERY_SCAN_FAILED },
                unknownNodeLabel = { NODE_DISCOVERY_UNKNOWN_NODE },
            ),
            scanDurationMs = 60_000L,
        )
        vm.scan(this)
        runCurrent()
        assertEquals(1, sent)
        assertTrue(vm.state.value.isScanning)

        vm.stopScan()
        runCurrent()

        assertEquals(1, sent)
        assertFalse(vm.state.value.isScanning)
        assertTrue(vm.state.value.hasScannedOnce)
        assertNull(vm.state.value.errorMessage)
    }

    @Test
    fun keepingTheDiscoverNodesSignalSortDoesNotRadio() = runTest {
        var discoverReads = 0
        var eventReads = 0
        var serviceReads = 0
        var storeReads = 0
        var radioReads = 0
        val vm = NodeDiscoveryViewModel(
            dependencies = NodeDiscoveryViewModel.Dependencies(
                { _, _ ->
                    discoverReads += 1
                    0L
                },
                {
                    eventReads += 1
                    null
                },
                {
                    serviceReads += 1
                    null
                },
                {
                    storeReads += 1
                    null
                },
                {
                    radioReads += 1
                    null
                },
                errorText = { NODE_DISCOVERY_LOCALIZED_ERROR },
                notConnectedMessage = { NODE_DISCOVERY_NOT_CONNECTED },
                scanFailedMessage = { NODE_DISCOVERY_SCAN_FAILED },
                unknownNodeLabel = { NODE_DISCOVERY_UNKNOWN_NODE },
            ),
        )
        assertEquals(NodeDiscoverySortOrder.SNR, vm.state.value.sortOrder)
        assertEquals(NodeDiscoveryFilter.REPEATERS, vm.state.value.filter)
        assertFalse(vm.state.value.isScanning)
        assertFalse(vm.state.value.hasScannedOnce)
        assertTrue(vm.state.value.results.isEmpty())
        assertEquals(0, discoverReads)
        assertEquals(0, eventReads)
        assertEquals(0, serviceReads)
        assertEquals(0, storeReads)
        assertEquals(0, radioReads)

        vm.setSortOrder(NodeDiscoverySortOrder.SNR)

        assertEquals(0, discoverReads)
        assertEquals(0, eventReads)
        assertEquals(0, serviceReads)
        assertEquals(0, storeReads)
        assertEquals(0, radioReads)
        assertEquals(NodeDiscoverySortOrder.SNR, vm.state.value.sortOrder)
        assertEquals(NodeDiscoveryFilter.REPEATERS, vm.state.value.filter)
        assertFalse(vm.state.value.isScanning)
        assertFalse(vm.state.value.hasScannedOnce)
        assertTrue(vm.state.value.results.isEmpty())
        assertNull(vm.state.value.errorMessage)
    }

    @Test
    fun keepingTheRepeaterDiscoveryFilterDoesNotRadio() = runTest {
        var discoverReads = 0
        var eventReads = 0
        var serviceReads = 0
        var storeReads = 0
        var radioReads = 0
        val vm = NodeDiscoveryViewModel(
            dependencies = NodeDiscoveryViewModel.Dependencies(
                { _, _ ->
                    discoverReads += 1
                    0L
                },
                {
                    eventReads += 1
                    null
                },
                {
                    serviceReads += 1
                    null
                },
                {
                    storeReads += 1
                    null
                },
                {
                    radioReads += 1
                    null
                },
                errorText = { NODE_DISCOVERY_LOCALIZED_ERROR },
                notConnectedMessage = { NODE_DISCOVERY_NOT_CONNECTED },
                scanFailedMessage = { NODE_DISCOVERY_SCAN_FAILED },
                unknownNodeLabel = { NODE_DISCOVERY_UNKNOWN_NODE },
            ),
        )
        assertEquals(NodeDiscoveryFilter.REPEATERS, vm.state.value.filter)
        assertEquals(NodeDiscoverySortOrder.SNR, vm.state.value.sortOrder)
        assertFalse(vm.state.value.isScanning)
        assertFalse(vm.state.value.hasScannedOnce)
        assertTrue(vm.state.value.results.isEmpty())
        assertNull(vm.state.value.errorMessage)
        assertEquals(0, discoverReads)
        assertEquals(0, eventReads)
        assertEquals(0, serviceReads)
        assertEquals(0, storeReads)
        assertEquals(0, radioReads)

        vm.setFilter(NodeDiscoveryFilter.REPEATERS)

        assertEquals(0, discoverReads)
        assertEquals(0, eventReads)
        assertEquals(0, serviceReads)
        assertEquals(0, storeReads)
        assertEquals(0, radioReads)
        assertEquals(NodeDiscoveryFilter.REPEATERS, vm.state.value.filter)
        assertEquals(NodeDiscoverySortOrder.SNR, vm.state.value.sortOrder)
        assertFalse(vm.state.value.isScanning)
        assertFalse(vm.state.value.hasScannedOnce)
        assertTrue(vm.state.value.results.isEmpty())
        assertNull(vm.state.value.errorMessage)
    }

    @Test
    fun stoppingAnIdleNodeDiscoveryScanDoesNotRadio() = runTest {
        var discoverReads = 0
        var eventReads = 0
        var serviceReads = 0
        var storeReads = 0
        var radioReads = 0
        val vm = NodeDiscoveryViewModel(
            dependencies = NodeDiscoveryViewModel.Dependencies(
                { _, _ ->
                    discoverReads += 1
                    0L
                },
                {
                    eventReads += 1
                    null
                },
                {
                    serviceReads += 1
                    null
                },
                {
                    storeReads += 1
                    null
                },
                {
                    radioReads += 1
                    null
                },
                errorText = { NODE_DISCOVERY_LOCALIZED_ERROR },
                notConnectedMessage = { NODE_DISCOVERY_NOT_CONNECTED },
                scanFailedMessage = { NODE_DISCOVERY_SCAN_FAILED },
                unknownNodeLabel = { NODE_DISCOVERY_UNKNOWN_NODE },
            ),
        )
        assertFalse(vm.state.value.isScanning)
        assertFalse(vm.state.value.hasScannedOnce)
        assertEquals(NodeDiscoveryFilter.REPEATERS, vm.state.value.filter)
        assertEquals(NodeDiscoverySortOrder.SNR, vm.state.value.sortOrder)
        assertNull(vm.state.value.errorMessage)
        assertNull(vm.state.value.addError)
        assertTrue(vm.state.value.results.isEmpty())
        assertEquals(0, discoverReads)
        assertEquals(0, eventReads)
        assertEquals(0, serviceReads)
        assertEquals(0, storeReads)
        assertEquals(0, radioReads)

        vm.stopScan()
        runCurrent()

        assertEquals(0, discoverReads)
        assertEquals(0, eventReads)
        assertEquals(0, serviceReads)
        assertEquals(0, storeReads)
        assertEquals(0, radioReads)
        assertFalse(vm.state.value.isScanning)
        assertFalse(vm.state.value.hasScannedOnce)
        assertEquals(NodeDiscoveryFilter.REPEATERS, vm.state.value.filter)
        assertEquals(NodeDiscoverySortOrder.SNR, vm.state.value.sortOrder)
        assertNull(vm.state.value.errorMessage)
        assertNull(vm.state.value.addError)
        assertTrue(vm.state.value.results.isEmpty())
    }

    @Test
    fun aSendFailureShowsScanFailedNotEnglish() = runTest {
        val events = MutableSharedFlow<MeshEvent>(extraBufferCapacity = 1)
        val vm = NodeDiscoveryViewModel(
            dependencies = NodeDiscoveryViewModel.Dependencies(
                sendDiscover = { _, _ -> error("radio down") },
                events = { events },
                contactService = { null },
                discoveredNodeStore = { null },
                radioId = { null },
                scanFailedMessage = { "scan fail" },
                errorText = { NODE_DISCOVERY_LOCALIZED_ERROR },
                notConnectedMessage = { NODE_DISCOVERY_NOT_CONNECTED },
                unknownNodeLabel = { NODE_DISCOVERY_UNKNOWN_NODE },
            ),
            scanDurationMs = 1_000L,
        )
        vm.scan(this)
        runCurrent()
        assertEquals("scan fail", vm.state.value.errorMessage)
        assertFalse(vm.state.value.isScanning)
        assertTrue(vm.state.value.hasScannedOnce)
    }

    @Test
    fun persistFaultOnNamePreloadStillScans() = runTest {
        var sent = 0
        val events = MutableSharedFlow<MeshEvent>(extraBufferCapacity = 1)
        val radio = Uuid.normalized("A0000000-0000-0000-0000-000000000099")
        val store = object : ContactStore by InMemoryContactStore() {
            override suspend fun fetchContacts(radioID: Uuid) = error("db locked")
        }
        val vm = NodeDiscoveryViewModel(
            dependencies = NodeDiscoveryViewModel.Dependencies(
                sendDiscover = { _, _ ->
                    sent += 1
                    1L
                },
                events = { events },
                contactService = {
                    ContactService(
                        session = FakeContactSession(),
                        store = store,
                        now = { NOW },
                    )
                },
                discoveredNodeStore = { InMemoryDiscoveredNodeStore() },
                radioId = { radio },
                errorText = { NODE_DISCOVERY_LOCALIZED_ERROR },
                notConnectedMessage = { NODE_DISCOVERY_NOT_CONNECTED },
                scanFailedMessage = { NODE_DISCOVERY_SCAN_FAILED },
                unknownNodeLabel = { NODE_DISCOVERY_UNKNOWN_NODE },
            ),
            scanDurationMs = 1_000L,
            nowEpochSeconds = { NOW },
        )

        vm.scan(this)
        runCurrent()

        assertEquals(1, sent)
        assertNull(vm.state.value.errorMessage)
        assertTrue(vm.state.value.isScanning)
    }

    private fun discoverEvent(tag: ByteArray, snr: Double, keyByte: Int): MeshEvent =
        MeshEvent.DiscoverResponseReceived(
            DiscoverResponse(
                nodeType = ContactType.REPEATER.raw,
                snrIn = 5.0,
                snr = snr,
                rssi = -90,
                pathLength = 1,
                tag = tag,
                publicKey = ByteArray(32) { keyByte.toByte() },
            ),
        )

    @Test
    fun addNodeMarksPublicKeyAsAdded() = runTest {
        val radio = Uuid.normalized("A0000000-0000-0000-0000-000000000099")
        val publicKey = ByteArray(32) { 0x11 }
        val session = FakeContactSession()
        val store = InMemoryContactStore()
        val service = ContactService(
            session = session,
            store = store,
            now = { NOW },
        )
        val vm = NodeDiscoveryViewModel(
            dependencies = NodeDiscoveryViewModel.Dependencies(
                sendDiscover = { _, _ -> 1L },
                events = { null },
                contactService = { service },
                discoveredNodeStore = { InMemoryDiscoveredNodeStore() },
                radioId = { radio },
                isRadioConnected = { true },
                errorText = { NODE_DISCOVERY_LOCALIZED_ERROR },
                notConnectedMessage = { NODE_DISCOVERY_NOT_CONNECTED },
                scanFailedMessage = { NODE_DISCOVERY_SCAN_FAILED },
                unknownNodeLabel = { NODE_DISCOVERY_UNKNOWN_NODE },
            ),
            nowEpochSeconds = { NOW },
        )
        val result = NodeDiscoveryResult(
            name = "Hilltop",
            publicKey = publicKey,
            nodeType = ContactType.REPEATER.raw,
            snr = 9.0,
            snrIn = 4.0,
            rssi = -80,
            scanFilter = NodeDiscoveryFilter.REPEATERS,
            receivedAtEpochSeconds = NOW,
        )
        vm.addNode(result)
        assertTrue(vm.state.value.isAdded(result))
        assertEquals(1, session.addedContacts.size)
        assertEquals(publicKey.toUppercaseHex(), store.fetchContacts(radio).single().publicKey.toUppercaseHex())
        assertEquals(0, session.addedContacts.single().lastModified)
    }

    @Test
    fun discoveryContactTypeFallsBackUnknownToRepeater() {
        assertEquals(ContactType.REPEATER, discoveryContactType(0xFF))
        assertEquals(ContactType.CHAT, discoveryContactType(ContactType.CHAT.raw))
        assertEquals(ContactType.ROOM, discoveryContactType(ContactType.ROOM.raw))
    }

    @Test
    fun addNodeFallsBackUnknownTypeToRepeater() = runTest {
        val radio = Uuid.normalized("A0000000-0000-0000-0000-000000000099")
        val session = FakeContactSession()
        val store = InMemoryContactStore()
        val service = ContactService(
            session = session,
            store = store,
            now = { NOW },
        )
        val vm = NodeDiscoveryViewModel(
            dependencies = NodeDiscoveryViewModel.Dependencies(
                sendDiscover = { _, _ -> 1L },
                events = { null },
                contactService = { service },
                discoveredNodeStore = { InMemoryDiscoveredNodeStore() },
                radioId = { radio },
                isRadioConnected = { true },
                errorText = { NODE_DISCOVERY_LOCALIZED_ERROR },
                notConnectedMessage = { NODE_DISCOVERY_NOT_CONNECTED },
                scanFailedMessage = { NODE_DISCOVERY_SCAN_FAILED },
                unknownNodeLabel = { NODE_DISCOVERY_UNKNOWN_NODE },
            ),
            nowEpochSeconds = { NOW },
        )
        val result = NodeDiscoveryResult(
            name = "Mystery",
            publicKey = ByteArray(32) { 0x22 },
            nodeType = 0xFF,
            snr = 1.0,
            snrIn = 1.0,
            rssi = -90,
            scanFilter = NodeDiscoveryFilter.REPEATERS,
            receivedAtEpochSeconds = NOW,
        )
        vm.addNode(result)
        assertEquals(ContactType.REPEATER.raw, session.addedContacts.single().typeRawValue)
        assertEquals(ContactType.REPEATER.raw, store.fetchContacts(radio).single().typeRawValue)
        assertTrue(vm.state.value.isAdded(result))
    }

    @Test
    fun addNodeTableFullMapsToNodeListFull() = runTest {
        val radio = Uuid.normalized("A0000000-0000-0000-0000-000000000099")
        val session = FakeContactSession().apply { addContactError = ContactProtocolErrorCode.TABLE_FULL }
        val service = ContactService(
            session = session,
            store = InMemoryContactStore(),
            now = { NOW },
        )
        val vm = NodeDiscoveryViewModel(
            dependencies = NodeDiscoveryViewModel.Dependencies(
                sendDiscover = { _, _ -> 1L },
                events = { null },
                contactService = { service },
                discoveredNodeStore = { InMemoryDiscoveredNodeStore() },
                radioId = { radio },
                isRadioConnected = { true },
                maxContacts = { 200 },
                errorText = { NODE_DISCOVERY_LOCALIZED_ERROR },
                notConnectedMessage = { NODE_DISCOVERY_NOT_CONNECTED },
                scanFailedMessage = { NODE_DISCOVERY_SCAN_FAILED },
                unknownNodeLabel = { NODE_DISCOVERY_UNKNOWN_NODE },
            ),
        )
        vm.addNode(
            NodeDiscoveryResult(
                name = "Hilltop",
                publicKey = ByteArray(32) { 0x11 },
                nodeType = ContactType.REPEATER.raw,
                snr = 9.0,
                snrIn = 4.0,
                rssi = -80,
                scanFilter = NodeDiscoveryFilter.REPEATERS,
                receivedAtEpochSeconds = NOW,
            ),
        )
        val error = vm.state.value.addError
        assertEquals(AddContactError.NodeListFull(200), error)
        vm.clearError()
        assertNull(vm.state.value.addError)
        assertNull(vm.state.value.errorMessage)
    }

    @Test
    fun addWhileDisconnectedDoesNotReachTheRadio() = runTest {
        val radio = Uuid.normalized("A0000000-0000-0000-0000-000000000099")
        val session = FakeContactSession()
        val store = InMemoryContactStore()
        val service = ContactService(
            session = session,
            store = store,
            now = { NOW },
        )
        val vm = NodeDiscoveryViewModel(
            dependencies = NodeDiscoveryViewModel.Dependencies(
                sendDiscover = { _, _ -> 1L },
                events = { null },
                contactService = { service },
                discoveredNodeStore = { InMemoryDiscoveredNodeStore() },
                radioId = { radio },
                isRadioConnected = { false },
                errorText = { NODE_DISCOVERY_LOCALIZED_ERROR },
                notConnectedMessage = { NODE_DISCOVERY_NOT_CONNECTED },
                scanFailedMessage = { NODE_DISCOVERY_SCAN_FAILED },
                unknownNodeLabel = { NODE_DISCOVERY_UNKNOWN_NODE },
            ),
        )
        vm.addNode(
            NodeDiscoveryResult(
                name = "Hilltop",
                publicKey = ByteArray(32) { 0x11 },
                nodeType = ContactType.REPEATER.raw,
                snr = 9.0,
                snrIn = 4.0,
                rssi = -80,
                scanFilter = NodeDiscoveryFilter.REPEATERS,
                receivedAtEpochSeconds = NOW,
            ),
        )
        assertTrue(session.addedContacts.isEmpty())
        assertTrue(store.fetchContacts(radio).isEmpty())
        assertNull(vm.state.value.addError)
        assertNull(vm.state.value.addingPublicKeyHex)
        assertTrue(vm.state.value.addedPublicKeyHexes.isEmpty())
    }

    @Test
    fun unresolvedHitsUseTheInjectedUnknownLabelAndFourBytePrefix() {
        val key = ByteArray(32) { 0xDE.toByte() }
        assertEquals(
            "Unbekannt (DEDEDEDE)",
            unknownDiscoveredNodeName(unknownLabel = "Unbekannt", publicKey = key),
        )
    }

    @Test
    fun blankStoredNamesDoNotOverrideTheUnknownFallback() {
        assertEquals(null, storedDiscoveryName(""))
        assertEquals(null, storedDiscoveryName("  "))
        assertEquals("Hilltop", storedDiscoveryName("Hilltop"))
    }

    private fun retiringEventFlow(tag: Long, release: CompletableDeferred<Unit>): Flow<MeshEvent> = flow {
        emit(discoverEvent(NodeDiscoveryViewModel.tagToLittleEndianBytes(tag), snr = 8.5, keyByte = 0xAB))
        try {
            awaitCancellation()
        } finally {
            withContext(NonCancellable) { release.await() }
        }
    }

    private fun scanningViewModel(events: () -> Flow<MeshEvent>?, tag: Long): NodeDiscoveryViewModel =
        NodeDiscoveryViewModel(
            dependencies = NodeDiscoveryViewModel.Dependencies(
                sendDiscover = { _, _ -> tag },
                events = events,
                contactService = { null },
                discoveredNodeStore = { null },
                radioId = { null },
                errorText = { NODE_DISCOVERY_LOCALIZED_ERROR },
                notConnectedMessage = { NODE_DISCOVERY_NOT_CONNECTED },
                scanFailedMessage = { NODE_DISCOVERY_SCAN_FAILED },
                unknownNodeLabel = { NODE_DISCOVERY_UNKNOWN_NODE },
            ),
            scanDurationMs = 60_000L,
            nowEpochSeconds = { NOW },
        )

    private companion object {
        const val NOW = 1_700_000_000L
    }
}

/** Scan and add faults for Tools node discovery. */
@OptIn(ExperimentalCoroutinesApi::class)
class NodeDiscoveryFaultTest {
    @Test
    fun closeRejectsLaterScanAndAddWork() = runTest {
        var dependencyReads = 0
        val vm = NodeDiscoveryViewModel(
            dependencies = NodeDiscoveryViewModel.Dependencies(
                sendDiscover = { _, _ -> error("send") },
                events = {
                    dependencyReads += 1
                    error("events")
                },
                contactService = {
                    dependencyReads += 1
                    error("contacts")
                },
                discoveredNodeStore = { null },
                radioId = { error("radio") },
                isRadioConnected = {
                    dependencyReads += 1
                    error("connected")
                },
                errorText = { NODE_DISCOVERY_LOCALIZED_ERROR },
                notConnectedMessage = { NODE_DISCOVERY_NOT_CONNECTED },
                scanFailedMessage = { NODE_DISCOVERY_SCAN_FAILED },
                unknownNodeLabel = { NODE_DISCOVERY_UNKNOWN_NODE },
            ),
        )

        vm.close()
        vm.scan(this)
        vm.addNode(hilltop())

        assertEquals(0, dependencyReads)
        assertFalse(vm.state.value.isScanning)
    }

    @Test
    fun aCancelledScanStopsWithoutAScanError() = runTest {
        val vm = scanningViewModel { _, _ -> throw CancellationException("superseded") }
        vm.scan(this)
        runCurrent()
        assertNull(vm.state.value.errorMessage)
        assertFalse(vm.state.value.isScanning)
        assertTrue(vm.state.value.hasScannedOnce)
    }

    @Test
    fun aScanErrorStopsWithoutAScanError() = runTest {
        val errors = mutableListOf<Throwable>()
        val scope = CoroutineScope(
            SupervisorJob() + UnconfinedTestDispatcher(testScheduler) +
                CoroutineExceptionHandler { _, error -> errors += error },
        )
        val vm = scanningViewModel { _, _ -> throw NodeDiscoveryProbeError() }
        vm.scan(scope)
        assertTrue(errors.single() is NodeDiscoveryProbeError)
        assertNull(vm.state.value.errorMessage)
        assertFalse(vm.state.value.isScanning)
        assertTrue(vm.state.value.hasScannedOnce)
        scope.cancel()
    }

    @Test
    fun aSaveExceptionReportsTheAddFaultAndClearsTheSpinner() = runTest {
        val session = FakeContactSession()
        val vm = addingViewModel(session) { _, _ -> error("db locked") }
        vm.addNode(hilltop())
        val error = vm.state.value.addError
        assertIs<AddContactError.ServiceMessage>(error)
        assertEquals(NODE_DISCOVERY_LOCALIZED_ERROR, error.message)
        assertNull(vm.state.value.addingPublicKeyHex)
        assertFalse(vm.state.value.isAdded(hilltop()))
        assertEquals(1, session.addedContacts.size)
    }

    @Test
    fun aCancelledSavePropagatesAndClearsTheSpinner() = runTest {
        val session = FakeContactSession()
        val vm = addingViewModel(session) { _, _ -> throw CancellationException("superseded") }
        val cancelled = assertFailsWith<CancellationException> { vm.addNode(hilltop()) }
        assertEquals("superseded", cancelled.message)
        assertNull(vm.state.value.addError)
        assertNull(vm.state.value.addingPublicKeyHex)
        assertFalse(vm.state.value.isAdded(hilltop()))
    }

    @Test
    fun aSaveErrorPropagatesAndClearsTheSpinner() = runTest {
        val session = FakeContactSession()
        val vm = addingViewModel(session) { _, _ -> throw NodeDiscoveryProbeError() }
        assertFailsWith<NodeDiscoveryProbeError> { vm.addNode(hilltop()) }
        assertNull(vm.state.value.addError)
        assertNull(vm.state.value.addingPublicKeyHex)
        assertFalse(vm.state.value.isAdded(hilltop()))
        assertEquals(1, session.addedContacts.size)
    }

    private fun scanningViewModel(sendDiscover: suspend (Int, Boolean) -> Long) = NodeDiscoveryViewModel(
        dependencies = NodeDiscoveryViewModel.Dependencies(
            sendDiscover = sendDiscover,
            events = { MutableSharedFlow<MeshEvent>().asSharedFlow() },
            contactService = { null },
            discoveredNodeStore = { null },
            radioId = { null },
            scanFailedMessage = { "scan fail" },
            errorText = { NODE_DISCOVERY_LOCALIZED_ERROR },
            notConnectedMessage = { NODE_DISCOVERY_NOT_CONNECTED },
            unknownNodeLabel = { NODE_DISCOVERY_UNKNOWN_NODE },
        ),
        scanDurationMs = 1_000L,
    )

    private fun addingViewModel(
        session: ContactSession,
        save: suspend (Uuid, ContactFrame) -> Uuid,
    ): NodeDiscoveryViewModel {
        val radio = Uuid.normalized("A0000000-0000-0000-0000-000000000099")
        val store = object : ContactStore by InMemoryContactStore() {
            override suspend fun saveContact(radioID: Uuid, frame: ContactFrame): Uuid = save(radioID, frame)
        }
        val service = ContactService(session = session, store = store, now = { NOW })
        return NodeDiscoveryViewModel(
            dependencies = NodeDiscoveryViewModel.Dependencies(
                sendDiscover = { _, _ -> 1L },
                events = { null },
                contactService = { service },
                discoveredNodeStore = { InMemoryDiscoveredNodeStore() },
                radioId = { radio },
                isRadioConnected = { true },
                errorText = { NODE_DISCOVERY_LOCALIZED_ERROR },
                notConnectedMessage = { NODE_DISCOVERY_NOT_CONNECTED },
                scanFailedMessage = { NODE_DISCOVERY_SCAN_FAILED },
                unknownNodeLabel = { NODE_DISCOVERY_UNKNOWN_NODE },
            ),
            nowEpochSeconds = { NOW },
        )
    }

    private fun hilltop() = NodeDiscoveryResult(
        name = "Hilltop",
        publicKey = ByteArray(32) { 0x11 },
        nodeType = ContactType.REPEATER.raw,
        snr = 9.0,
        snrIn = 4.0,
        rssi = -80,
        scanFilter = NodeDiscoveryFilter.REPEATERS,
        receivedAtEpochSeconds = NOW,
    )

    private companion object {
        const val NOW = 1_700_000_000L
    }
}

private class NodeDiscoveryProbeError : Error("probe")
