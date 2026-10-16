package one.meshcore.shared.contact

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import one.meshcore.shared.meshcore.MeshEvent
import one.meshcore.shared.meshcore.PathInfo
import one.meshcore.shared.meshcore.TraceInfo
import one.meshcore.shared.meshcore.TraceNode
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.DebugLogLevel
import one.meshcore.shared.settings.PersistentLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Contact-table half of advertisement/path pushes must reach [ContactService] (iOS AdvertisementService).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactPathEventCollectorTest {
    @Test
    fun advertisementAutoAddsContactViaCollector() = runTest {
        val store = InMemoryContactStore()
        val radioID = ContactTestIds.RADIO
        val key = publicKey(0x71)
        val session = FakeContactSession().apply {
            singleContactLookup = meshContact(keyByte = 0x71, name = "LiveAdd", type = ContactType.CHAT)
        }
        val service = testContactService(session = session, store = store)
        val meshEvents = MutableSharedFlow<MeshEvent>(extraBufferCapacity = 4)
        val collector = ContactPathEventCollector(radioID, service, this)
        collector.start(meshEvents)
        yield()

        meshEvents.emit(MeshEvent.Advertisement(key))
        yield()

        val saved = store.fetchContact(radioID, key)
        assertNotNull(saved)
        assertEquals("LiveAdd", saved.name)
        collector.stop()
    }

    @Test
    fun advertisementStampsKnownContactLastHeard() = runTest {
        val store = InMemoryContactStore()
        val radioID = ContactTestIds.RADIO
        val key = publicKey(0x72)
        store.saveContact(
            radioID,
            contactFrame(0x72, name = "Old").copy(lastAdvertTimestamp = 50L),
        )
        val service = testContactService(store = store)
        val meshEvents = MutableSharedFlow<MeshEvent>(extraBufferCapacity = 4)
        val collector = ContactPathEventCollector(radioID, service, this)
        collector.start(meshEvents)
        yield()

        meshEvents.emit(MeshEvent.Advertisement(key))
        yield()

        val heard = store.fetchContact(radioID, key)!!
        assertEquals(50L, heard.lastAdvertTimestamp)
        assertEquals(CONTACT_TEST_NOW_SECONDS, heard.lastHeardTimestamp)
        collector.stop()
    }

    @Test
    fun pathUpdateStillRefreshesOutPath() = runTest {
        val store = InMemoryContactStore()
        val radioID = ContactTestIds.RADIO
        val key = publicKey(0x73)
        store.saveContact(
            radioID,
            contactFrame(0x73, name = "Hop").copy(outPathLength = 255, outPath = ByteArray(0)),
        )
        val devicePath = byteArrayOf(0x01, 0x02)
        val session = FakeContactSession().apply {
            singleContactLookup = meshContact(
                keyByte = 0x73,
                name = "Hop",
                outPathLength = 2,
                outPath = devicePath,
            )
        }
        val service = testContactService(session = session, store = store)
        val meshEvents = MutableSharedFlow<MeshEvent>(extraBufferCapacity = 4)
        val collector = ContactPathEventCollector(radioID, service, this)
        collector.start(meshEvents)
        yield()

        meshEvents.emit(MeshEvent.PathUpdate(key))
        yield()

        val updated = store.fetchContact(radioID, key)!!
        assertEquals(2, updated.outPathLength)
        assertTrue(updated.outPath.contentEquals(devicePath))
        collector.stop()
    }

    @Test
    fun aThrownAdvertHandlerRecordsTheIosErrorLine() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val session = FakeContactSession().apply {
            onGetContact = { error("radio down") }
        }
        val service = testContactService(session = session)
        val meshEvents = MutableSharedFlow<MeshEvent>(extraBufferCapacity = 4)
        val collector = ContactPathEventCollector(
            ContactTestIds.RADIO,
            service,
            this,
            logger = PersistentLogger(this, "AdvertisementService", record = { recorded += it }),
        )
        collector.start(meshEvents)
        yield()

        meshEvents.emit(MeshEvent.Advertisement(publicKey(0x74)))
        yield()

        val line = recorded.single { it.message.contains("Error handling advert event") }
        assertEquals(DebugLogLevel.ERROR, line.level)
        assertTrue(line.message.contains("radio down"))
        collector.stop()
    }

    @Test
    fun aCancelledAdvertHandlerStopsTheCollector() = runTest(UnconfinedTestDispatcher()) {
        val stopped = CancellationException("superseded")
        val session = FakeContactSession().apply { onGetContact = { throw stopped } }
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val collector = ContactPathEventCollector(ContactTestIds.RADIO, testContactService(session = session), scope)
        val events = MutableSharedFlow<MeshEvent>(extraBufferCapacity = 2)
        collector.start(events)
        val job = scope.coroutineContext[Job]!!.children.single()
        var cause: Throwable? = null
        job.invokeOnCompletion { cause = it }

        events.emit(MeshEvent.Advertisement(publicKey(0x74)))

        assertSame(stopped, cause)
        scope.cancel()
    }

    @Test
    fun unknownAdvertRecordsEnqueueFetchLine() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val session = FakeContactSession().apply {
            singleContactLookup = meshContact(keyByte = 0x75, name = "New")
        }
        val service = testContactService(
            session = session,
            logger = PersistentLogger(this, "AdvertisementService", record = { recorded += it }),
        )

        service.handleAdvertisement(ContactTestIds.RADIO, publicKey(0x75))

        val line = recorded.single { it.message.contains("ADVERT received for unknown contact") }
        assertEquals(DebugLogLevel.INFO, line.level)
        assertTrue(line.message.contains("enqueueing fetch"))
    }

    @Test
    fun pathDiscoveryResponseRecordsHopDumpLine() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val key = publicKey(0x76)
        val store = InMemoryContactStore()
        store.saveContact(ContactTestIds.RADIO, contactFrame(0x76, name = "Hop"))
        val service = testContactService(
            store = store,
            logger = PersistentLogger(this, "AdvertisementService", record = { recorded += it }),
        )
        val path = PathInfo(
            publicKeyPrefix = key.copyOf(3),
            outPathLength = 0x41,
            outPath = byteArrayOf(0xAA.toByte()),
            inPathLength = 0,
            inPath = ByteArray(0),
        )

        service.applyPathDiscoveryResponse(ContactTestIds.RADIO, path)

        val line = recorded.single { it.message.contains("Path discovery for") }
        assertEquals(DebugLogLevel.INFO, line.level)
        assertTrue(line.message.contains("Out: 1 hops"))
        assertTrue(line.message.contains("In: 0 hops (direct)"))
    }

    @Test
    fun traceDataRecordsTheIosReceivedLine() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val meshEvents = MutableSharedFlow<MeshEvent>(extraBufferCapacity = 4)
        val collector = ContactPathEventCollector(
            ContactTestIds.RADIO,
            testContactService(),
            this,
            logger = PersistentLogger(this, "AdvertisementService", record = { recorded += it }),
        )
        collector.start(meshEvents)
        yield()

        meshEvents.emit(
            MeshEvent.TraceData(
                TraceInfo(
                    tag = 7,
                    authCode = 0,
                    flags = 0,
                    pathLength = 2,
                    path = listOf(
                        TraceNode(hashBytes = byteArrayOf(0x11), snr = 4.0),
                        TraceNode(hashBytes = byteArrayOf(0x22), snr = -0.5),
                    ),
                ),
            ),
        )
        yield()

        val line = recorded.single { it.message.contains("Received trace data") }
        assertEquals(DebugLogLevel.INFO, line.level)
        assertEquals("AdvertisementService", line.category)
        assertEquals("Received trace data: tag=7, hops=2", line.message)
        collector.stop()
    }
}
