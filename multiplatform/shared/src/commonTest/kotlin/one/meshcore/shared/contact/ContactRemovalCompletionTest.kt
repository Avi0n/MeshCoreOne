package one.meshcore.shared.contact

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.model.Uuid
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ContactRemovalCompletionTest {
    @Test
    fun confirmedRemovalCallsBackAfterTheRowAndMaskUpdate() = runTest {
        val contact = removalContact()
        val store = savedContactStore(contact)
        lateinit var vm: ContactListViewModel
        val removed = mutableListOf<ContactDto>()
        vm = removalModel(testContactService(store = store), contact.radioID) {
            assertTrue(contact.id in vm.state.value.pendingRemovalIds)
            assertTrue(vm.state.value.contacts.none { row -> row.id == contact.id })
            removed += it
        }
        vm.load()
        vm.deleteContact(contact)
        assertEquals(listOf(contact), removed)
        assertNull(store.fetchContact(contact.id))
    }

    @Test
    fun anAlreadyForgottenContactCallsBackAfterLocalCleanup() = runTest {
        val contact = removalContact()
        val store = savedContactStore(contact)
        val session = FakeContactSession().apply { removeContactError = ContactProtocolErrorCode.NOT_FOUND }
        val removed = mutableListOf<ContactDto>()
        val vm = removalModel(testContactService(session, store), contact.radioID, removed::add)
        vm.load()
        vm.deleteContact(contact)
        assertEquals(listOf(contact), removed)
        assertNull(store.fetchContact(contact.id))
    }

    @Test
    fun aFailedDeleteDoesNotCallBackOrHideTheRow() = runTest {
        val contact = removalContact()
        val store = savedContactStore(contact)
        val session = FakeContactSession().apply { removeContactThrowable = IllegalStateException("radio failed") }
        var callbacks = 0
        val vm = removalModel(testContactService(session, store), contact.radioID) { callbacks += 1 }
        vm.load()
        vm.deleteContact(contact)
        assertEquals(0, callbacks)
        assertEquals(listOf(contact), vm.state.value.contacts)
        assertTrue(vm.state.value.pendingRemovalIds.isEmpty())
    }

    @Test
    fun aPausedCompletionCannotClearAReplacementRadio() = runTest {
        verifyStaleCompletion { state -> state.radio = Uuid.random() }
    }

    @Test
    fun aPausedCompletionCannotClearAReplacementService() = runTest {
        verifyStaleCompletion { state -> state.service = testContactService() }
    }

    @Test
    fun aPausedCompletionCannotPublishAfterTheModelCloses() = runTest {
        verifyStaleCompletion { state -> state.model.close() }
    }

    @Test
    fun aNoncooperativeCanceledStoreCannotPublishCompletion() = runTest {
        val contact = removalContact()
        val backing = savedContactStore(contact)
        lateinit var continuation: Continuation<Unit>
        val store = object : ContactStore by backing {
            override suspend fun deleteContact(id: Uuid) {
                suspendCoroutine { continuation = it }
                backing.deleteContact(id)
            }
        }
        var callbacks = 0
        val vm = removalModel(testContactService(store = store), contact.radioID) { callbacks += 1 }
        vm.load()
        val deletion = launch { vm.deleteContact(contact) }
        runCurrent()
        deletion.cancel()
        continuation.resume(Unit)
        deletion.join()
        assertTrue(deletion.isCancelled)
        assertEquals(0, callbacks)
        assertTrue(vm.state.value.pendingRemovalIds.isEmpty())
    }

    private suspend fun TestScope.verifyStaleCompletion(replace: (RemovalOwner) -> Unit) {
        val contact = removalContact()
        val release = CompletableDeferred<Unit>()
        val session = FakeContactSession().apply { onRemoveContact = { release.await() } }
        val owner = RemovalOwner(testContactService(session, savedContactStore(contact)), contact.radioID)
        var callbacks = 0
        owner.model = ContactListViewModel(
            backgroundScope,
            ContactListViewModel.Dependencies(
                contactService = { owner.service },
                radioId = { owner.radio },
                isConnected = { true },
                errorText = { "Localized error" },
                onContactRemoved = { callbacks += 1 },
            ),
        )
        owner.model.load()
        val deletion = launch { owner.model.deleteContact(contact) }
        runCurrent()
        assertFalse(deletion.isCompleted)
        replace(owner)
        release.complete(Unit)
        deletion.join()
        assertEquals(0, callbacks)
        assertTrue(owner.model.state.value.pendingRemovalIds.isEmpty())
    }
}

private class RemovalOwner(var service: ContactService, var radio: Uuid) {
    lateinit var model: ContactListViewModel
}

private fun removalContact() = contactDto(
    id = ContactTestIds.fresh(),
    radioID = ContactTestIds.RADIO,
    keyByte = 0x01,
    name = "Removed node",
)

private fun TestScope.removalModel(service: ContactService, radio: Uuid, onRemoved: (ContactDto) -> Unit) =
    ContactListViewModel(
        backgroundScope,
        ContactListViewModel.Dependencies(
            contactService = { service },
            radioId = { radio },
            isConnected = { true },
            errorText = { "Localized error" },
            onContactRemoved = onRemoved,
        ),
    )
