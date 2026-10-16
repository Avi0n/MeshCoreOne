package one.meshcore.shared.contact

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Block-toggle faults that must keep the thrown instance. A failed Room save still becomes a list
 * error; cancellation and a store error leave the row unblocked and propagate.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactListBlockFaultTest {
    @Test
    fun aCancelledBlockRethrowsTheSameException() = runTest {
        val cancelled = CancellationException("superseded")
        val contact = blockChat("Alice")
        val backing = savedContactStore(contact)
        val failing = object : ContactStore by backing {
            override suspend fun updateContactFields(id: Uuid, update: (ContactDto) -> ContactDto): ContactDto? =
                backing.updateContactFields(id) { current ->
                    val dto = update(current)
                    if (dto.isBlocked) throw cancelled
                    dto
                }
        }
        val vm = blockViewModel(testContactService(store = failing))
        vm.load()

        val thrown = assertFailsWith<CancellationException> {
            vm.toggleBlocked(vm.state.value.contacts.first())
        }

        assertSame(cancelled, thrown)
        assertNull(vm.state.value.error)
        assertFalse(vm.state.value.contacts.first().isBlocked)
    }

    @Test
    fun aBlockSaveErrorRethrowsTheSameError() = runTest {
        val failure = BlockSaveProbeError()
        val contact = blockChat("Alice")
        val backing = savedContactStore(contact)
        val failing = object : ContactStore by backing {
            override suspend fun updateContactFields(id: Uuid, update: (ContactDto) -> ContactDto): ContactDto? =
                backing.updateContactFields(id) { current ->
                    val dto = update(current)
                    if (dto.isBlocked) throw failure
                    dto
                }
        }
        val vm = blockViewModel(testContactService(store = failing))
        vm.load()

        val thrown = assertFailsWith<BlockSaveProbeError> {
            vm.toggleBlocked(vm.state.value.contacts.first())
        }

        assertSame(failure, thrown)
        assertNull(vm.state.value.error)
        assertFalse(vm.state.value.contacts.first().isBlocked)
    }

    @Test
    fun aBlockSaveExceptionReportsTheListError() = runTest {
        val contact = blockChat("Alice")
        val backing = savedContactStore(contact)
        val failing = object : ContactStore by backing {
            override suspend fun updateContactFields(id: Uuid, update: (ContactDto) -> ContactDto): ContactDto? =
                backing.updateContactFields(id) { current ->
                    val dto = update(current)
                    if (dto.isBlocked) error("room down")
                    dto
                }
        }
        val vm = blockViewModel(testContactService(store = failing))
        vm.load()

        vm.toggleBlocked(vm.state.value.contacts.first())

        assertIs<ContactListError.ServiceMessage>(vm.state.value.error)
        assertFalse(vm.state.value.contacts.first().isBlocked)
    }
}

private class BlockSaveProbeError : Error("probe")

private val blockRadio = ContactTestIds.RADIO

private fun blockChat(name: String) = contactDto(
    id = ContactTestIds.fresh(),
    radioID = blockRadio,
    keyByte = 0x01,
    name = name,
    typeRawValue = ContactType.CHAT.raw,
    isBlocked = false,
)

private fun TestScope.blockViewModel(service: ContactService): ContactListViewModel = ContactListViewModel(
    scope = backgroundScope,
    dependencies = ContactListViewModel.Dependencies(
        contactService = { service },
        radioId = { blockRadio },
        isConnected = { true },
        isReady = { true },
        errorText = { "Localized error" },
    ),
)
