package one.meshcore.shared.contact

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
 * Delete faults that must keep a store error. A radio timeout stays a list result in the existing
 * row tests; a store error keeps the row and propagates.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactListDeleteFaultTest {
    @Test
    fun aDeleteStoreErrorRethrowsTheSameErrorAndKeepsTheRow() = runTest {
        val failure = DeleteRowProbeError()
        val contact = deleteChat("Alice")
        val backing = savedContactStore(contact)
        val failing = object : ContactStore by backing {
            override suspend fun deleteContact(id: Uuid) = throw failure
        }
        val vm = deleteViewModel(testContactService(session = FakeContactSession(), store = failing))
        vm.load()

        val thrown = assertFailsWith<DeleteRowProbeError> {
            vm.deleteContact(vm.state.value.contacts.first())
        }

        assertSame(failure, thrown)
        assertNull(vm.state.value.error)
        assertEquals(listOf("Alice"), vm.state.value.contacts.map { it.name })
        assertTrue(contact.id !in vm.state.value.deletingIds)
        assertTrue(contact.id !in vm.state.value.pendingRemovalIds)
    }
}

private class DeleteRowProbeError : Error("probe")

private val deleteRadio = ContactTestIds.RADIO

private fun deleteChat(name: String) = contactDto(
    id = ContactTestIds.fresh(),
    radioID = deleteRadio,
    keyByte = 0x01,
    name = name,
    typeRawValue = ContactType.CHAT.raw,
)

private fun TestScope.deleteViewModel(service: ContactService): ContactListViewModel = ContactListViewModel(
    scope = backgroundScope,
    dependencies = ContactListViewModel.Dependencies(
        contactService = { service },
        radioId = { deleteRadio },
        isConnected = { true },
        isReady = { true },
        errorText = { "Localized error" },
    ),
)
