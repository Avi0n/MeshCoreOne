package one.meshcore.shared.chat.pathdetail

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.contact.ContactStore
import one.meshcore.shared.contact.ContactTestIds
import one.meshcore.shared.contact.DiscoveredNodeRecord
import one.meshcore.shared.contact.InMemoryContactStore
import one.meshcore.shared.contact.contactDto
import one.meshcore.shared.contact.testContactService
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A persist fault on path/mention contact load must land an empty list so the thread's
 * invalidation collector and the path sheet stay usable. iOS `MessagePathViewModel.loadContacts`
 * catches store errors and sets `contacts = []`.
 */
class RepeaterContactsLoadTest {
    @Test
    fun loadContactsReturnsSavedRows() = runTest {
        val store = InMemoryContactStore()
        val chat = contactDto(ContactTestIds.fresh(), ContactTestIds.RADIO, name = "Alice")
        val repeater = contactDto(
            ContactTestIds.fresh(),
            ContactTestIds.RADIO,
            name = "R1",
            typeRawValue = ContactType.REPEATER.raw,
        )
        store.saveContact(chat)
        store.saveContact(repeater)
        val service = testContactService(store = store)

        val contacts = loadContacts(service, ContactTestIds.RADIO)
        val repeaters = loadRepeaterContacts(service, ContactTestIds.RADIO)

        assertEquals(listOf("Alice", "R1"), contacts.map { it.name })
        assertEquals(listOf("R1"), repeaters.map { it.name })
    }

    @Test
    fun persistFaultReturnsEmptyList() = runTest {
        val store = object : ContactStore by InMemoryContactStore() {
            override suspend fun fetchContacts(radioID: Uuid) = error("db locked")
        }
        val service = testContactService(store = store)

        assertTrue(loadContacts(service, ContactTestIds.RADIO).isEmpty())
        assertTrue(loadRepeaterContacts(service, ContactTestIds.RADIO).isEmpty())
    }

    @Test
    fun aCancelledContactLoadPropagates() = runTest {
        val cancelled = CancellationException("stopped")
        val store = object : ContactStore by InMemoryContactStore() {
            override suspend fun fetchContacts(radioID: Uuid): List<ContactDto> = throw cancelled
        }
        val service = testContactService(store = store)

        val thrown = assertFailsWith<CancellationException> {
            loadContacts(service, ContactTestIds.RADIO)
        }

        assertSame(cancelled, thrown)
    }

    @Test
    fun aContactLoadErrorPropagates() = runTest {
        val fault = ContactLoadProbeError()
        val store = object : ContactStore by InMemoryContactStore() {
            override suspend fun fetchContacts(radioID: Uuid): List<ContactDto> = throw fault
        }
        val service = testContactService(store = store)

        val thrown = assertFailsWith<ContactLoadProbeError> {
            loadContacts(service, ContactTestIds.RADIO)
        }

        assertSame(fault, thrown)
    }

    @Test
    fun disconnectedReturnsEmptyList() = runTest {
        assertTrue(loadContacts(null, null).isEmpty())
    }

    @Test
    fun loadDiscoveredRepeatersKeepsRepeatersOnly() = runTest {
        val repeater = DiscoveredNodeRecord(
            id = ContactTestIds.fresh(),
            radioID = ContactTestIds.RADIO,
            publicKey = ByteArray(32) { 0x11 },
            name = "R1",
            typeRawValue = ContactType.REPEATER.raw,
            lastHeardEpochSeconds = 1L,
        )
        val chat = repeater.copy(
            id = ContactTestIds.fresh(),
            publicKey = ByteArray(32) { 0x22 },
            name = "Chat",
            typeRawValue = ContactType.CHAT.raw,
        )
        val loaded = loadDiscoveredRepeaters(ContactTestIds.RADIO) { listOf(repeater, chat) }
        assertEquals(listOf("R1"), loaded.map { it.name })
    }

    @Test
    fun discoveredPersistFaultReturnsEmptyList() = runTest {
        val loaded = loadDiscoveredRepeaters(ContactTestIds.RADIO) {
            error("db locked")
        }
        assertTrue(loaded.isEmpty())
    }

    @Test
    fun loadDiscoveredNodesKeepsAllTypes() = runTest {
        val chat = DiscoveredNodeRecord(
            id = ContactTestIds.fresh(),
            radioID = ContactTestIds.RADIO,
            publicKey = ByteArray(32) { 0x33 },
            name = "Chat",
            typeRawValue = ContactType.CHAT.raw,
            lastHeardEpochSeconds = 1L,
        )
        val loaded = loadDiscoveredNodes(ContactTestIds.RADIO) { listOf(chat) }
        assertEquals(listOf("Chat"), loaded.map { it.name })
    }

    @Test
    fun discoveredNodesPersistFaultReturnsEmptyList() = runTest {
        val loaded = loadDiscoveredNodes(ContactTestIds.RADIO) {
            error("db locked")
        }
        assertTrue(loaded.isEmpty())
    }

    @Test
    fun aCancelledDiscoveredNodeLoadPropagates() = runTest {
        val cancelled = CancellationException("stopped")
        val thrown = assertFailsWith<CancellationException> {
            loadDiscoveredNodes(ContactTestIds.RADIO) { throw cancelled }
        }
        assertSame(cancelled, thrown)
    }

    @Test
    fun aDiscoveredNodeLoadErrorPropagates() = runTest {
        val fault = DiscoveredNodeProbeError()
        val thrown = assertFailsWith<DiscoveredNodeProbeError> {
            loadDiscoveredNodes(ContactTestIds.RADIO) { throw fault }
        }
        assertSame(fault, thrown)
    }

    @Test
    fun pathMessagePersistFaultReturnsNull() = runTest {
        val dto = loadMessageForPath { error("db locked") }
        assertNull(dto)
    }

    @Test
    fun aCancelledPathMessageLoadPropagates() = runTest {
        val cancelled = CancellationException("stopped")
        val thrown = assertFailsWith<CancellationException> {
            loadMessageForPath { throw cancelled }
        }
        assertSame(cancelled, thrown)
    }

    @Test
    fun aPathMessageLoadErrorPropagates() = runTest {
        val fault = PathMessageProbeError()
        val thrown = assertFailsWith<PathMessageProbeError> {
            loadMessageForPath { throw fault }
        }
        assertSame(fault, thrown)
    }
}

private class ContactLoadProbeError : Error("probe")

private class DiscoveredNodeProbeError : Error("probe")

private class PathMessageProbeError : Error("probe")
