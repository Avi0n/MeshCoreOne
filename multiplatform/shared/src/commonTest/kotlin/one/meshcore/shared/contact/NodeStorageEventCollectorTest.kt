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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Device 0x90 contactsFull and 0x8F contactDeleted pushes must reach ContactService so Room and the
 * storage-full UI flag stay consistent with the radio (iOS AdvertisementService parity).
 */
class NodeStorageEventCollectorTest {
    @Test
    fun contactsFullNotifiesStorageFull() = runTest {
        val service = testContactService()
        val serviceEvents = service.events()
        val meshEvents = MutableSharedFlow<MeshEvent>(extraBufferCapacity = 4)
        val collector = NodeStorageEventCollector(ContactTestIds.RADIO, service, this)
        collector.start(meshEvents)
        yield()

        meshEvents.emit(MeshEvent.ContactsFull)
        yield()

        val event = serviceEvents.receive()
        assertTrue(event is ContactServiceEvent.NodeStorageFullChanged)
        assertTrue((event as ContactServiceEvent.NodeStorageFullChanged).isFull)
        collector.stop()
    }

    @Test
    fun contactDeletedRemovesLocalContactAndYieldsNodeDeleted() = runTest {
        val store = InMemoryContactStore()
        val radioID = ContactTestIds.RADIO
        val key = publicKey(0x66)
        val contactID = ContactTestIds.fresh()
        store.saveContact(contactDto(id = contactID, radioID = radioID, keyByte = 0x66, name = "Gone"))
        val service = testContactService(store = store)
        val serviceEvents = service.events()
        val meshEvents = MutableSharedFlow<MeshEvent>(extraBufferCapacity = 4)
        val collector = NodeStorageEventCollector(radioID, service, this)
        collector.start(meshEvents)
        yield()

        meshEvents.emit(MeshEvent.ContactDeleted(key))
        yield()

        assertNull(store.fetchContact(radioID, key))
        assertEquals(listOf(contactID), store.deletedMessagesForContactIDs())
        assertEquals(ContactServiceEvent.NodeDeleted, serviceEvents.receive())
        collector.stop()
    }

    @Test
    fun contactDeletedMissingLocalRowIsNoOp() = runTest {
        val store = InMemoryContactStore()
        val service = testContactService(store = store)
        val meshEvents = MutableSharedFlow<MeshEvent>(extraBufferCapacity = 4)
        val collector = NodeStorageEventCollector(ContactTestIds.RADIO, service, this)
        collector.start(meshEvents)
        yield()

        meshEvents.emit(MeshEvent.ContactDeleted(publicKey(0x77)))
        yield()

        assertTrue(store.fetchContacts(ContactTestIds.RADIO).isEmpty())
        collector.stop()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aCancelledContactDeletionStopsTheCollector() = runTest(UnconfinedTestDispatcher()) {
        val stopped = CancellationException("superseded")
        val key = publicKey(0x78)
        val store = InMemoryContactStore()
        store.saveContact(ContactTestIds.RADIO, contactFrame(0x78, name = "Keep"))
        val service = testContactService(store = store, selfPublicKey = { throw stopped })
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val events = MutableSharedFlow<MeshEvent>(extraBufferCapacity = 2)
        val collector = NodeStorageEventCollector(ContactTestIds.RADIO, service, scope)
        collector.start(events)
        val job = scope.coroutineContext[Job]!!.children.single()
        var cause: Throwable? = null
        job.invokeOnCompletion { cause = it }

        events.emit(MeshEvent.ContactDeleted(key))

        assertSame(stopped, cause)
        assertTrue(store.fetchContact(ContactTestIds.RADIO, key) != null)
        scope.cancel()
    }
}
