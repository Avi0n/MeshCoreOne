package one.meshcore.shared.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.ContactSession
import one.meshcore.shared.contact.ContactStore
import one.meshcore.shared.contact.InMemoryContactStore
import one.meshcore.shared.contact.NoOpContactSession
import one.meshcore.shared.meshcore.MeshContact
import one.meshcore.shared.meshcore.MeshEvent
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.tools.DiscoveryLifetimeFixture.Companion.RADIO
import one.meshcore.shared.tools.DiscoveryLifetimeFixture.Companion.response
import one.meshcore.shared.tools.DiscoveryLifetimeFixture.Companion.result
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NodeDiscoveryAddLifetimeTest {
    @Test
    fun pendingAddCompletesOnTheProcessOwnerWithoutAMount() = runTest {
        val fixture = DiscoveryLifetimeFixture(backgroundScope)
        val ack = CompletableDeferred<Unit>()
        fixture.service = service(fixture.store) { ack.await() }
        fixture.workspace.addNode(result())
        runCurrent()
        assertEquals(result().publicKeyHex, fixture.model.state.value.addingPublicKeyHex)
        ack.complete(Unit)
        runCurrent()
        assertEquals(1, fixture.store.fetchContacts(RADIO).size)
        assertTrue(fixture.model.state.value.isAdded(result()))
        assertNull(fixture.model.state.value.addingPublicKeyHex)
    }

    @Test
    fun resetRejectsANoncooperativeAddBeforeItWritesTheStore() = runTest {
        val fixture = DiscoveryLifetimeFixture(backgroundScope)
        val ack = CompletableDeferred<Unit>()
        fixture.service = service(fixture.store) { withContext(NonCancellable) { ack.await() } }
        fixture.workspace.addNode(result())
        runCurrent()
        fixture.workspace.reset()
        runCurrent()
        ack.complete(Unit)
        runCurrent()
        assertTrue(fixture.store.fetchContacts(RADIO).isEmpty())
        assertFalse(fixture.model.state.value.isAdded(result()))
        assertNull(fixture.model.state.value.addError)
        assertNull(fixture.model.state.value.addingPublicKeyHex)
    }

    @Test
    fun switchingRadioRejectsAnAddBeforeTheStoreWriteEvenWithoutCancellation() = runTest {
        val fixture = DiscoveryLifetimeFixture(backgroundScope)
        val ack = CompletableDeferred<Unit>()
        fixture.service = service(fixture.store) { ack.await() }
        fixture.workspace.addNode(result())
        runCurrent()
        fixture.radio = DiscoveryLifetimeFixture.OTHER_RADIO
        ack.complete(Unit)
        runCurrent()
        assertTrue(fixture.store.fetchContacts(RADIO).isEmpty())
        assertTrue(fixture.store.fetchContacts(DiscoveryLifetimeFixture.OTHER_RADIO).isEmpty())
        assertFalse(fixture.model.state.value.isAdded(result()))
    }

    @Test
    fun aReplacedServiceRejectsAnAddBeforeTheStoreWrite() = runTest {
        val fixture = DiscoveryLifetimeFixture(backgroundScope)
        val ack = CompletableDeferred<Unit>()
        fixture.service = service(fixture.store) { ack.await() }
        fixture.workspace.addNode(result())
        runCurrent()
        fixture.service = service(fixture.store) { }
        ack.complete(Unit)
        runCurrent()
        assertTrue(fixture.store.fetchContacts(RADIO).isEmpty())
        assertFalse(fixture.model.state.value.isAdded(result()))
    }

    @Test
    fun anOlderSameKeyAddCannotClearTheNewSpinnerOrWriteItsName() = runTest {
        val fixture = DiscoveryLifetimeFixture(backgroundScope)
        val old = CompletableDeferred<Unit>()
        val new = CompletableDeferred<Unit>()
        var calls = 0
        fixture.service = service(fixture.store) { if (++calls == 1) old.await() else new.await() }
        fixture.workspace.addNode(result(name = "Old"))
        runCurrent()
        fixture.workspace.addNode(result(name = "New"))
        runCurrent()
        old.complete(Unit)
        runCurrent()
        assertEquals(result().publicKeyHex, fixture.model.state.value.addingPublicKeyHex)
        assertTrue(fixture.store.fetchContacts(RADIO).isEmpty())
        new.complete(Unit)
        runCurrent()
        assertEquals("New", fixture.store.fetchContacts(RADIO).single().name)
        assertNull(fixture.model.state.value.addingPublicKeyHex)
    }

    @Test
    fun aCancelledNoncooperativeFailureCannotPublishAnAddError() = runTest {
        val fixture = DiscoveryLifetimeFixture(backgroundScope)
        val ack = CompletableDeferred<Unit>()
        fixture.service = service(fixture.store) {
            withContext(NonCancellable) {
                ack.await()
                error("late failure")
            }
        }
        val add = backgroundScope.launch { fixture.model.addNode(result()) }
        runCurrent()
        add.cancel()
        ack.complete(Unit)
        runCurrent()
        assertNull(fixture.model.state.value.addError)
        assertNull(fixture.model.state.value.addingPublicKeyHex)
        assertTrue(fixture.store.fetchContacts(RADIO).isEmpty())
    }

    @Test
    fun finishingAnotherKeyDoesNotClearTheCurrentSpinner() = runTest {
        val fixture = DiscoveryLifetimeFixture(backgroundScope)
        val old = CompletableDeferred<Unit>()
        val new = CompletableDeferred<Unit>()
        var calls = 0
        fixture.service = service(fixture.store) { if (++calls == 1) old.await() else new.await() }
        fixture.workspace.addNode(result(key = 0x44))
        runCurrent()
        fixture.workspace.addNode(result(key = 0x55))
        runCurrent()
        old.complete(Unit)
        runCurrent()
        assertEquals(result(key = 0x55).publicKeyHex, fixture.model.state.value.addingPublicKeyHex)
        assertTrue(fixture.model.state.value.isAdded(result(key = 0x44)))
        new.complete(Unit)
        runCurrent()
        assertNull(fixture.model.state.value.addingPublicKeyHex)
        assertTrue(fixture.model.state.value.isAdded(result(key = 0x55)))
    }

    @Test
    fun anAuthoritativeReloadRemovesTheBadgeOfAnExternallyDeletedContact() = runTest {
        val fixture = DiscoveryLifetimeFixture(backgroundScope)
        fixture.workspace.addNode(result())
        runCurrent()
        assertTrue(fixture.model.state.value.isAdded(result()))
        fixture.store.deleteContact(fixture.store.fetchContacts(RADIO).single().id)
        fixture.workspace.scan()
        runCurrent()
        assertFalse(fixture.model.state.value.isAdded(result()))
    }

    @Test
    fun aNamePreloadCannotEraseAnAddThatCompletedDuringItsRead() = runTest {
        val read = CompletableDeferred<Unit>()
        val base = InMemoryContactStore()
        val store = object : ContactStore by base {
            override suspend fun fetchContacts(radioID: Uuid): List<ContactDto> {
                val old = base.fetchContacts(radioID)
                read.await()
                return old
            }
        }
        val fixture = DiscoveryLifetimeFixture(backgroundScope, store)
        fixture.workspace.scan()
        runCurrent()
        fixture.workspace.addNode(result(name = "Named now"))
        runCurrent()
        read.complete(Unit)
        runCurrent()
        val events = fixture.events as MutableSharedFlow<MeshEvent>
        backgroundScope.launch { events.emit(response()) }
        runCurrent()
        assertEquals("Named now", fixture.model.state.value.results.single().name)
        assertTrue(fixture.model.state.value.isAdded(result()))
    }

    @Test
    fun aNoncooperativeNamePreloadCannotRestoreNamesOrBadgesAfterReset() = runTest {
        val read = CompletableDeferred<Unit>()
        val base = InMemoryContactStore()
        val store = object : ContactStore by base {
            override suspend fun fetchContacts(radioID: Uuid): List<ContactDto> {
                val old = base.fetchContacts(radioID)
                withContext(NonCancellable) { read.await() }
                return old
            }
        }
        val fixture = DiscoveryLifetimeFixture(backgroundScope, store)
        fixture.workspace.addNode(result())
        runCurrent()
        fixture.workspace.scan()
        runCurrent()
        fixture.workspace.reset()
        runCurrent()
        read.complete(Unit)
        runCurrent()
        assertFalse(fixture.model.state.value.isAdded(result()))
        assertTrue(fixture.model.state.value.results.isEmpty())
        assertEquals(0, fixture.sendCalls)
        assertNull(fixture.model.state.value.errorMessage)
    }

    @Test
    fun losingTheLiveSessionDuringNamePreloadCancelsWithoutAScanError() = runTest {
        val read = CompletableDeferred<Unit>()
        val base = InMemoryContactStore()
        val store = object : ContactStore by base {
            override suspend fun fetchContacts(radioID: Uuid): List<ContactDto> {
                read.await()
                return emptyList()
            }
        }
        val fixture = DiscoveryLifetimeFixture(backgroundScope, store)
        var commands = 0
        fixture.send = {
            if (fixture.events == null) throw CancellationException()
            commands++
            DiscoveryLifetimeFixture.TAG
        }
        fixture.workspace.scan()
        runCurrent()
        fixture.radio = null
        fixture.service = null
        fixture.events = null
        read.complete(Unit)
        runCurrent()
        assertEquals(0, commands)
        assertFalse(fixture.model.state.value.isScanning)
        assertNull(fixture.model.state.value.errorMessage)
    }

    private fun service(store: ContactStore, add: suspend () -> Unit): ContactService = ContactService(
        session = object : ContactSession by NoOpContactSession {
            override suspend fun addContact(contact: MeshContact) = add()
        },
        store = store,
        now = { 0L },
    )
}
