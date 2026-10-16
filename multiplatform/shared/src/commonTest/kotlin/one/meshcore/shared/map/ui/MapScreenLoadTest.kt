package one.meshcore.shared.map.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.contact.ContactStore
import one.meshcore.shared.contact.ContactTestIds
import one.meshcore.shared.contact.InMemoryContactStore
import one.meshcore.shared.contact.contactDto
import one.meshcore.shared.contact.testContactService
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * Loading map contacts. A store failure still comes back as a failed result. Cancellation and an
 * error propagate and do not become a failed result.
 */
class MapScreenLoadTest {
    @Test
    fun aLoadReturnsTheStoredContacts() = runTest {
        val store = InMemoryContactStore()
        val contact = contactDto(id = ContactTestIds.fresh(), radioID = ContactTestIds.RADIO)
        store.saveContact(contact)
        val service = testContactService(store = store)

        val result = loadContacts(service, ContactTestIds.RADIO)

        assertEquals(listOf(contact.id), result.getOrThrow().map { it.id })
    }

    @Test
    fun aStoreExceptionBecomesAFailedResult() = runTest {
        val failure = IllegalArgumentException("bad load")
        val service = testContactService(store = FetchThrows { throw failure })

        val result = loadContacts(service, ContactTestIds.RADIO)

        assertSame(failure, result.exceptionOrNull())
    }

    @Test
    fun aCancelledLoadPropagates() = runTest {
        val cancelled = CancellationException("superseded")
        val service = testContactService(store = FetchThrows { throw cancelled })

        val thrown = assertFailsWith<CancellationException> {
            loadContacts(service, ContactTestIds.RADIO)
        }

        assertSame(cancelled, thrown)
    }

    @Test
    fun aLoadErrorPropagates() = runTest {
        val fault = MapLoadProbeError()
        val service = testContactService(store = FetchThrows { throw fault })

        val thrown = assertFailsWith<MapLoadProbeError> {
            loadContacts(service, ContactTestIds.RADIO)
        }

        assertSame(fault, thrown)
    }
}

private class MapLoadProbeError : Error("probe")

private class FetchThrows(private val fail: () -> Nothing) : ContactStore by InMemoryContactStore() {
    override suspend fun fetchContacts(radioID: Uuid): List<ContactDto> {
        fail()
    }
}
