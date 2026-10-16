package one.meshcore.shared.contact

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Orphaned-delete faults that must keep the thrown instance. The device already forgot the contact;
 * a local delete that is cancelled or throws an error keeps the row.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactListOrphanFaultTest {
    @Test
    fun aCancelledOrphanDeleteRethrowsTheSameExceptionAndKeepsTheRow() = runTest {
        val cancelled = CancellationException("superseded")
        val contact = orphanChat("Alice")
        val backing = savedContactStore(contact)
        val failing = object : ContactStore by backing {
            override suspend fun deleteContact(id: Uuid) = throw cancelled
        }
        val session = FakeContactSession().apply { removeContactError = ContactProtocolErrorCode.NOT_FOUND }
        val vm = orphanViewModel(testContactService(session = session, store = failing))
        vm.load()

        val thrown = assertFailsWith<CancellationException> {
            vm.deleteContact(vm.state.value.contacts.first())
        }

        assertSame(cancelled, thrown)
        assertNull(vm.state.value.error)
        assertEquals(listOf("Alice"), vm.state.value.contacts.map { it.name })
        assertTrue(contact.id !in vm.state.value.deletingIds)
        assertTrue(contact.id !in vm.state.value.pendingRemovalIds)
    }

    @Test
    fun anOrphanDeleteErrorRethrowsTheSameErrorAndKeepsTheRow() = runTest {
        val failure = OrphanRowProbeError()
        val contact = orphanChat("Alice")
        val backing = savedContactStore(contact)
        val failing = object : ContactStore by backing {
            override suspend fun deleteContact(id: Uuid) = throw failure
        }
        val session = FakeContactSession().apply { removeContactError = ContactProtocolErrorCode.NOT_FOUND }
        val vm = orphanViewModel(testContactService(session = session, store = failing))
        vm.load()

        val thrown = assertFailsWith<OrphanRowProbeError> {
            vm.deleteContact(vm.state.value.contacts.first())
        }

        assertSame(failure, thrown)
        assertNull(vm.state.value.error)
        assertEquals(listOf("Alice"), vm.state.value.contacts.map { it.name })
        assertTrue(contact.id !in vm.state.value.deletingIds)
        assertTrue(contact.id !in vm.state.value.pendingRemovalIds)
    }
}

private class OrphanRowProbeError : Error("probe")

private val orphanRadio = ContactTestIds.RADIO

private fun orphanChat(name: String) = contactDto(
    id = ContactTestIds.fresh(),
    radioID = orphanRadio,
    keyByte = 0x01,
    name = name,
    typeRawValue = ContactType.CHAT.raw,
)

private fun TestScope.orphanViewModel(service: ContactService): ContactListViewModel = ContactListViewModel(
    scope = backgroundScope,
    dependencies = ContactListViewModel.Dependencies(
        contactService = { service },
        radioId = { orphanRadio },
        isConnected = { true },
        isReady = { true },
        errorText = { "Localized error" },
    ),
)
