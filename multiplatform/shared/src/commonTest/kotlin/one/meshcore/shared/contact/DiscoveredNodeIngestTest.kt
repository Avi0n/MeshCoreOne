package one.meshcore.shared.contact

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.meshcore.MeshContact
import one.meshcore.shared.meshcore.MeshEvent
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.model.toUppercaseHex
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.DebugLogLevel
import one.meshcore.shared.settings.PersistentLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DiscoveredNodeIngestTest {
    private val radio = Uuid.normalized("A0000000-0000-0000-0000-000000000001")
    private val keyA = ByteArray(PUBLIC_KEY_SIZE) { 0x11 }
    private val keyB = ByteArray(PUBLIC_KEY_SIZE) { 0x22 }

    @Test
    fun newContactUpsertsDiscoverRow() = runTest {
        val store = InMemoryDiscoveredNodeStore()
        val contact = meshContact(keyA, name = "Nova", type = ContactType.REPEATER)
        val result = ingestDiscoveredNodeEvent(
            event = MeshEvent.NewContact(contact),
            radioID = radio,
            deps = deps(store),
        )
        assertIs<DiscoveredNodeIngestResult.Upserted>(result)
        assertTrue(result.isNew)
        val row = store.list(radio).single()
        assertEquals("Nova", row.name)
        assertEquals(ContactType.REPEATER.raw, row.typeRawValue)
        assertEquals(NOW, row.lastHeardEpochSeconds)
        assertTrue(row.publicKey.contentEquals(keyA))
    }

    @Test
    fun advertisementUsesLocalContactWhenKnown() = runTest {
        val store = InMemoryDiscoveredNodeStore()
        val local = contactDto(keyB, name = "Known", type = ContactType.CHAT)
        val result = ingestDiscoveredNodeEvent(
            event = MeshEvent.Advertisement(keyB),
            radioID = radio,
            deps = deps(
                store,
                IngestOptions(
                    lookupLocalContact = { key -> if (key.contentEquals(keyB)) local else null },
                ),
            ),
        )
        assertIs<DiscoveredNodeIngestResult.Upserted>(result)
        assertTrue(result.isNew)
        assertEquals("Known", store.list(radio).single().name)
    }

    @Test
    fun advertisementIgnoredWhenUnknownLocallyWithoutDeviceFetch() = runTest {
        // ContactService owns auto-add + getContact; Discover must not race a second fetch mid-sync.
        val store = InMemoryDiscoveredNodeStore()
        val result = ingestDiscoveredNodeEvent(
            event = MeshEvent.Advertisement(keyA),
            radioID = radio,
            deps = deps(store),
        )
        assertIs<DiscoveredNodeIngestResult.Ignored>(result)
        assertTrue(store.list(radio).isEmpty())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun newContactRecordsDiscoverTraceLines() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val store = InMemoryDiscoveredNodeStore()
        val contact = meshContact(keyA, name = "Nova", type = ContactType.REPEATER)
        val result = ingestDiscoveredNodeEvent(
            event = MeshEvent.NewContact(contact),
            radioID = radio,
            deps = deps(
                store,
                IngestOptions(
                    discoverTrace = PersistentLogger(this, category = "discover-trace", record = { recorded += it }),
                ),
            ),
        )
        assertIs<DiscoveredNodeIngestResult.Upserted>(result)
        val received = recorded.single { it.message.contains("B1 0x8A NEW_ADVERT received key=") }
        val upserted = recorded.single { it.message.contains("B2 0x8A upsert key=") }
        assertEquals("discover-trace", received.category)
        assertEquals(DebugLogLevel.INFO, received.level)
        assertTrue(received.message.contains(keyA.toUppercaseHex()))
        assertTrue(upserted.message.contains("isNew=true"))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun collectorKeepsIngestingAfterUpsertFault() = runTest(UnconfinedTestDispatcher()) {
        val inner = InMemoryDiscoveredNodeStore()
        val store = object : DiscoveredNodeStore by inner {
            private var remainingFails = 1

            override suspend fun upsert(node: DiscoveredNodeRecord): Boolean {
                if (remainingFails > 0) {
                    remainingFails -= 1
                    error("db locked")
                }
                return inner.upsert(node)
            }
        }
        val events = MutableSharedFlow<MeshEvent>(extraBufferCapacity = 4)
        val collector = DiscoveredNodeEventCollector(
            DiscoveredNodeCollectorInputs(
                store = store,
                radioId = { radio },
                lookupLocalContact = { _, _ -> null },
                nowEpochSeconds = { NOW },
                discoveryNotifier = ContactDiscoveryNotifier.NoOp,
            ),
        )
        val job = launch { collector.collect(events) }
        events.emit(MeshEvent.NewContact(meshContact(keyA, name = "First", type = ContactType.CHAT)))
        events.emit(MeshEvent.NewContact(meshContact(keyB, name = "Second", type = ContactType.CHAT)))
        job.cancel()
        assertEquals(listOf("Second"), inner.list(radio).map { it.name })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun newContactPersistFaultIsIgnoredAndLogged() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val result = ingestDiscoveredNodeEvent(
            event = MeshEvent.NewContact(meshContact(keyA, name = "First", type = ContactType.CHAT)),
            radioID = radio,
            deps = deps(
                throwingUpsertStore(),
                IngestOptions(
                    logger = PersistentLogger(this, category = "Discover", record = { recorded += it }),
                ),
            ),
        )
        assertIs<DiscoveredNodeIngestResult.Ignored>(result)
        assertTrue(recorded.single().message.contains("Error handling new advert event: db locked"))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun advertisementPersistFaultIsIgnoredAndLogged() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val local = contactDto(keyB, name = "Known", type = ContactType.CHAT)
        val result = ingestDiscoveredNodeEvent(
            event = MeshEvent.Advertisement(keyB),
            radioID = radio,
            deps = deps(
                throwingUpsertStore(),
                IngestOptions(
                    lookupLocalContact = { local },
                    logger = PersistentLogger(this, category = "Discover", record = { recorded += it }),
                ),
            ),
        )
        assertIs<DiscoveredNodeIngestResult.Ignored>(result)
        assertTrue(recorded.single().message.contains("Error handling advert event: db locked"))
    }

    @Test
    fun aCancelledAdvertisementUpsertPropagates() = runTest {
        val cancelled = CancellationException("superseded")
        val store = object : DiscoveredNodeStore by InMemoryDiscoveredNodeStore() {
            override suspend fun upsert(node: DiscoveredNodeRecord): Boolean = throw cancelled
        }
        val local = contactDto(keyB, name = "Known", type = ContactType.CHAT)
        val thrown = assertFailsWith<CancellationException> {
            ingestDiscoveredNodeEvent(
                event = MeshEvent.Advertisement(keyB),
                radioID = radio,
                deps = deps(store, IngestOptions(lookupLocalContact = { local })),
            )
        }
        assertSame(cancelled, thrown)
    }

    @Test
    fun anAdvertisementErrorPropagates() = runTest {
        val fault = DiscoverIngestProbeError()
        val store = object : DiscoveredNodeStore by InMemoryDiscoveredNodeStore() {
            override suspend fun upsert(node: DiscoveredNodeRecord): Boolean = throw fault
        }
        val local = contactDto(keyB, name = "Known", type = ContactType.CHAT)
        val thrown = assertFailsWith<DiscoverIngestProbeError> {
            ingestDiscoveredNodeEvent(
                event = MeshEvent.Advertisement(keyB),
                radioID = radio,
                deps = deps(store, IngestOptions(lookupLocalContact = { local })),
            )
        }
        assertSame(fault, thrown)
    }

    @Test
    fun aCancelledNewContactUpsertPropagates() = runTest {
        val cancelled = CancellationException("superseded")
        val store = object : DiscoveredNodeStore by InMemoryDiscoveredNodeStore() {
            override suspend fun upsert(node: DiscoveredNodeRecord): Boolean = throw cancelled
        }
        val thrown = assertFailsWith<CancellationException> {
            ingestDiscoveredNodeEvent(
                event = MeshEvent.NewContact(meshContact(keyA, name = "First", type = ContactType.CHAT)),
                radioID = radio,
                deps = deps(store),
            )
        }
        assertSame(cancelled, thrown)
    }

    @Test
    fun aNewContactErrorPropagates() = runTest {
        val fault = DiscoverIngestProbeError()
        val store = object : DiscoveredNodeStore by InMemoryDiscoveredNodeStore() {
            override suspend fun upsert(node: DiscoveredNodeRecord): Boolean = throw fault
        }
        val thrown = assertFailsWith<DiscoverIngestProbeError> {
            ingestDiscoveredNodeEvent(
                event = MeshEvent.NewContact(meshContact(keyA, name = "First", type = ContactType.CHAT)),
                radioID = radio,
                deps = deps(store),
            )
        }
        assertSame(fault, thrown)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aCancelledCollectorStopsLaterAdverts() = runTest(UnconfinedTestDispatcher()) {
        val inner = InMemoryDiscoveredNodeStore()
        val cancelled = CancellationException("superseded")
        val store = object : DiscoveredNodeStore by inner {
            override suspend fun upsert(node: DiscoveredNodeRecord): Boolean = throw cancelled
        }
        val events = MutableSharedFlow<MeshEvent>(extraBufferCapacity = 4)
        val collector = DiscoveredNodeEventCollector(
            DiscoveredNodeCollectorInputs(
                store = store,
                radioId = { radio },
                lookupLocalContact = { _, _ -> null },
                nowEpochSeconds = { NOW },
                discoveryNotifier = ContactDiscoveryNotifier.NoOp,
            ),
        )
        val job = launch { collector.collect(events) }
        var cause: Throwable? = null
        job.invokeOnCompletion { cause = it }
        events.emit(MeshEvent.NewContact(meshContact(keyA, name = "First", type = ContactType.CHAT)))
        events.emit(MeshEvent.NewContact(meshContact(keyB, name = "Second", type = ContactType.CHAT)))
        assertSame(cancelled, cause)
        assertTrue(inner.list(radio).isEmpty())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aCollectorErrorStopsLaterAdverts() = runTest(UnconfinedTestDispatcher()) {
        val errors = mutableListOf<Throwable>()
        val scope = CoroutineScope(
            SupervisorJob() +
                UnconfinedTestDispatcher(testScheduler) +
                CoroutineExceptionHandler { _, error -> errors += error },
        )
        val inner = InMemoryDiscoveredNodeStore()
        val fault = DiscoverIngestProbeError()
        val store = object : DiscoveredNodeStore by inner {
            override suspend fun upsert(node: DiscoveredNodeRecord): Boolean = throw fault
        }
        val events = MutableSharedFlow<MeshEvent>(extraBufferCapacity = 4)
        val collector = DiscoveredNodeEventCollector(
            DiscoveredNodeCollectorInputs(
                store = store,
                radioId = { radio },
                lookupLocalContact = { _, _ -> null },
                nowEpochSeconds = { NOW },
                discoveryNotifier = ContactDiscoveryNotifier.NoOp,
            ),
        )
        scope.launch { collector.collect(events) }
        events.emit(MeshEvent.NewContact(meshContact(keyA, name = "First", type = ContactType.CHAT)))
        events.emit(MeshEvent.NewContact(meshContact(keyB, name = "Second", type = ContactType.CHAT)))
        assertSame(fault, errors.single())
        assertTrue(inner.list(radio).isEmpty())
        scope.cancel()
    }

    private fun throwingUpsertStore(): DiscoveredNodeStore =
        object : DiscoveredNodeStore by InMemoryDiscoveredNodeStore() {
            override suspend fun upsert(node: DiscoveredNodeRecord): Boolean = error("db locked")
        }

    @Test
    fun upsertDiscoveredFromLocalContactAfterAutoAdd() = runTest {
        val store = InMemoryDiscoveredNodeStore()
        val local = contactDto(keyA, name = "AutoAdded", type = ContactType.ROOM)
        val result = upsertDiscoveredFromLocalContact(
            radioID = radio,
            contact = local,
            store = store,
            nowEpochSeconds = { NOW },
        )
        assertIs<DiscoveredNodeIngestResult.Upserted>(result)
        assertEquals("AutoAdded", store.list(radio).single().name)
        assertEquals(ContactType.ROOM.raw, store.list(radio).single().typeRawValue)
    }

    @Test
    fun repeatAdvertUpdatesLastHeardNotDuplicate() = runTest {
        val store = InMemoryDiscoveredNodeStore()
        val contact = meshContact(keyA, name = "Node", type = ContactType.CHAT)
        var clock = 100L
        val sharedDeps = deps(store, IngestOptions(nowEpochSeconds = { clock }))
        ingestDiscoveredNodeEvent(
            event = MeshEvent.NewContact(contact),
            radioID = radio,
            deps = sharedDeps,
        )
        clock = 200L
        val second = ingestDiscoveredNodeEvent(
            event = MeshEvent.NewContact(contact.copy(advertisedName = "Node-2")),
            radioID = radio,
            deps = sharedDeps,
        )
        assertIs<DiscoveredNodeIngestResult.Upserted>(second)
        assertTrue(!second.isNew)
        val listed = store.list(radio)
        assertEquals(1, listed.size)
        assertEquals("Node-2", listed.single().name)
        assertEquals(200, listed.single().lastHeardEpochSeconds)
    }

    /**
     * 0x8A is the whole discovery path in manual-add mode: the radio never adds the contact, so if this
     * push does not notify, nothing does.
     */
    @Test
    fun aFirstTimeNewAdvertNotifiesOnceAndARepeatDoesNot() = runTest {
        val store = InMemoryDiscoveredNodeStore()
        val notifier = RecordingContactDiscoveryNotifier()
        val key = ByteArray(32) { 0x51 }
        val event = MeshEvent.NewContact(meshContact(key, "Hilltop", ContactType.REPEATER))

        ingestDiscoveredNodeEvent(event, radio, deps(store, IngestOptions(discoveryNotifier = notifier)))
        ingestDiscoveredNodeEvent(event, radio, deps(store, IngestOptions(discoveryNotifier = notifier)))

        assertEquals(1, notifier.notified.size)
        val notified = notifier.notified.single()
        assertEquals("Hilltop", notified.contactName)
        assertEquals(ContactType.REPEATER, notified.contactType)
        assertEquals(store.list(radio).single().id, notified.contactID)
    }

    @Test
    fun aFirstTimeNewAdvertDuringSyncUpsertsWithoutNotifying() = runTest {
        val store = InMemoryDiscoveredNodeStore()
        val notifier = RecordingContactDiscoveryNotifier()
        val event = MeshEvent.NewContact(meshContact(keyA, "Hilltop", ContactType.REPEATER))

        ingestDiscoveredNodeEvent(
            event,
            radio,
            deps(
                store,
                IngestOptions(discoveryNotifier = notifier, shouldNotifyDiscovery = { false }),
            ),
        )

        assertTrue(notifier.notified.isEmpty())
        assertEquals("Hilltop", store.list(radio).single().name)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun advertisementPersistFaultRecordsErrorAndIgnores() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val store = object : DiscoveredNodeStore by InMemoryDiscoveredNodeStore() {
            override suspend fun upsert(node: DiscoveredNodeRecord): Boolean = error("db locked")
        }
        val local = contactDto(keyB, name = "Known", type = ContactType.CHAT)
        val result = ingestDiscoveredNodeEvent(
            event = MeshEvent.Advertisement(keyB),
            radioID = radio,
            deps = deps(
                store,
                IngestOptions(
                    lookupLocalContact = { key -> if (key.contentEquals(keyB)) local else null },
                    logger = PersistentLogger(this, category = "AdvertisementService", record = { recorded += it }),
                ),
            ),
        )
        assertIs<DiscoveredNodeIngestResult.Ignored>(result)
        val line = recorded.single { it.message.contains("Error handling advert event") }
        assertTrue(line.message.contains("db locked"))
    }

    @Test
    fun unrelatedEventsIgnored() = runTest {
        val store = InMemoryDiscoveredNodeStore()
        val result = ingestDiscoveredNodeEvent(
            event = MeshEvent.ContactsFull,
            radioID = radio,
            deps = deps(store),
        )
        assertIs<DiscoveredNodeIngestResult.Ignored>(result)
    }

    private data class IngestOptions(
        val lookupLocalContact: suspend (ByteArray) -> ContactDto? = { null },
        val nowEpochSeconds: () -> Long = { NOW },
        val discoveryNotifier: ContactDiscoveryNotifier = ContactDiscoveryNotifier.NoOp,
        val shouldNotifyDiscovery: () -> Boolean = { true },
        val discoverTrace: PersistentLogger? = null,
        val logger: PersistentLogger? = null,
    )

    private fun deps(store: DiscoveredNodeStore, options: IngestOptions = IngestOptions()) = DiscoveredNodeIngestDeps(
        store = store,
        lookupLocalContact = options.lookupLocalContact,
        nowEpochSeconds = options.nowEpochSeconds,
        discoveryNotifier = options.discoveryNotifier,
        shouldNotifyDiscovery = options.shouldNotifyDiscovery,
        logger = options.logger,
        discoverTrace = options.discoverTrace,
    )

    private fun meshContact(publicKey: ByteArray, name: String, type: ContactType): MeshContact = MeshContact(
        id = publicKey.joinToString("") { b -> (b.toInt() and 0xFF).toString(16) },
        publicKey = publicKey,
        typeRawValue = type.raw,
        flags = 0,
        outPathLength = 0,
        outPath = ByteArray(0),
        advertisedName = name,
        lastAdvertisement = 50,
        latitude = 0.0,
        longitude = 0.0,
        lastModified = 50,
    )

    private fun contactDto(publicKey: ByteArray, name: String, type: ContactType): ContactDto = ContactDto(
        id = Uuid.normalized("C0000000-0000-0000-0000-000000000001"),
        radioID = radio,
        publicKey = publicKey,
        name = name,
        typeRawValue = type.raw,
        flags = 0,
        outPathLength = 0,
        outPath = ByteArray(0),
        lastAdvertTimestamp = 50,
        latitude = 0.0,
        longitude = 0.0,
        lastModified = 50,
        isBlocked = false,
        isMuted = false,
        isFavorite = false,
        unreadCount = 0,
    )

    companion object {
        private const val PUBLIC_KEY_SIZE = 32
        private const val NOW = 1_700_000_000L
    }
}

private class DiscoverIngestProbeError : Error("probe")
