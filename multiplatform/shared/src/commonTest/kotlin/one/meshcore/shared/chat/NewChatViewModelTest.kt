package one.meshcore.shared.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.ContactStore
import one.meshcore.shared.contact.InMemoryContactStore
import one.meshcore.shared.contact.savedContactStore
import one.meshcore.shared.contact.testContactService
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Exercises the new-chat picker view model against a real [ContactService] backed by an
 * [InMemoryContactStore]: the load populates the picker, the eligibility + search derive the visible rows,
 * and a disconnected graph leaves it empty. Mirrors the iOS `NewChatView` load/filter behavior.
 */
class NewChatViewModelTest {
    private fun viewModel(service: ContactService?): NewChatViewModel = NewChatViewModel(
        dependencies = NewChatViewModel.Dependencies(
            contactService = { service },
            radioId = { if (service == null) null else ChatFixtures.RADIO },
        ),
    )

    @Test
    fun loadPopulatesContactsAndMarksLoaded() = runTest {
        val vm = viewModel(testContactService(store = savedContactStore(ChatFixtures.contact("Alice"))))
        vm.load()
        val state = vm.state.value
        assertEquals(1, state.contacts.size)
        assertTrue(state.hasLoadedOnce)
        assertFalse(state.isLoading)
    }

    @Test
    fun eligibleContactsDropRepeaterRoomAndBlocked() = runTest {
        val chat = ChatFixtures.contact("Chat")
        val repeater = ChatFixtures.contact("Repeater").copy(typeRawValue = ContactType.REPEATER.raw)
        val room = ChatFixtures.contact("Room").copy(typeRawValue = ContactType.ROOM.raw)
        val blocked = ChatFixtures.contact("Blocked", isBlocked = true)
        val vm = viewModel(testContactService(store = savedContactStore(chat, repeater, room, blocked)))
        vm.load()
        assertEquals(listOf(chat.id), vm.eligibleContacts(vm.state.value).map { it.id })
    }

    @Test
    fun searchTextNarrowsTheEligibleContacts() = runTest {
        val alice = ChatFixtures.contact("Alice")
        val bob = ChatFixtures.contact("Bob")
        val vm = viewModel(testContactService(store = savedContactStore(alice, bob)))
        vm.load()
        vm.setSearchText("bo")
        assertEquals(listOf(bob.id), vm.eligibleContacts(vm.state.value).map { it.id })
    }

    @Test
    fun keepingAnEmptyNewChatSearchDoesNotRadio() {
        var serviceReads = 0
        var radioReads = 0
        val vm = NewChatViewModel(
            dependencies = NewChatViewModel.Dependencies(
                contactService = {
                    serviceReads += 1
                    null
                },
                radioId = {
                    radioReads += 1
                    null
                },
            ),
        )
        assertEquals("", vm.state.value.searchText)
        assertTrue(vm.state.value.contacts.isEmpty())
        assertFalse(vm.state.value.isLoading)
        assertFalse(vm.state.value.hasLoadedOnce)
        assertEquals(0, serviceReads)
        assertEquals(0, radioReads)

        vm.setSearchText("")

        assertEquals(0, serviceReads)
        assertEquals(0, radioReads)
        assertEquals("", vm.state.value.searchText)
        assertTrue(vm.state.value.contacts.isEmpty())
        assertFalse(vm.state.value.isLoading)
        assertFalse(vm.state.value.hasLoadedOnce)
        assertTrue(vm.eligibleContacts(vm.state.value).isEmpty())
    }

    @Test
    fun typingANewChatSearchDoesNotRadio() {
        var serviceReads = 0
        var radioReads = 0
        val vm = NewChatViewModel(
            dependencies = NewChatViewModel.Dependencies(
                contactService = {
                    serviceReads += 1
                    null
                },
                radioId = {
                    radioReads += 1
                    null
                },
            ),
        )
        assertEquals("", vm.state.value.searchText)
        assertTrue(vm.state.value.contacts.isEmpty())
        assertFalse(vm.state.value.isLoading)
        assertFalse(vm.state.value.hasLoadedOnce)

        vm.setSearchText("quill")

        assertEquals(0, serviceReads)
        assertEquals(0, radioReads)
        assertEquals("quill", vm.state.value.searchText)
        assertTrue(vm.state.value.contacts.isEmpty())
        assertFalse(vm.state.value.isLoading)
        assertFalse(vm.state.value.hasLoadedOnce)
        assertTrue(vm.eligibleContacts(vm.state.value).isEmpty())
    }

    @Test
    fun disconnectedLoadLeavesTheContactsEmpty() = runTest {
        val vm = viewModel(service = null)
        vm.load()
        assertTrue(vm.state.value.contacts.isEmpty())
        assertTrue(vm.state.value.hasLoadedOnce)
        assertFalse(vm.state.value.isLoading)
    }

    @Test
    fun reloadWithoutARadioClearsAPreviouslyLoadedPicker() = runTest {
        val service = testContactService(store = savedContactStore(ChatFixtures.contact("Alice")))
        var live: ContactService? = service
        val vm = NewChatViewModel(
            dependencies = NewChatViewModel.Dependencies(
                contactService = { live },
                radioId = { if (live == null) null else ChatFixtures.RADIO },
            ),
        )
        vm.load()
        assertEquals(1, vm.state.value.contacts.size)

        live = null
        vm.load()
        assertTrue(vm.state.value.contacts.isEmpty())
        assertTrue(vm.state.value.hasLoadedOnce)
    }

    @Test
    fun replacementGraphReloadsContactsWithoutDroppingSearch() = runTest {
        val first = ChatFixtures.contact("Alice")
        val replacement = ChatFixtures.contact("Bob")
        var live = testContactService(store = savedContactStore(first))
        val vm = NewChatViewModel(
            dependencies = NewChatViewModel.Dependencies(
                contactService = { live },
                radioId = { ChatFixtures.RADIO },
            ),
        )
        vm.load()
        vm.setSearchText("bo")

        live = testContactService(store = savedContactStore(replacement))
        vm.load()

        assertEquals("bo", vm.state.value.searchText)
        assertEquals(listOf(replacement.id), vm.state.value.contacts.map { it.id })
        assertEquals(listOf(replacement.id), vm.eligibleContacts(vm.state.value).map { it.id })
    }

    @Test
    fun persistFaultClearsSpinnerAndShowsEmpty() = runTest {
        val store = object : ContactStore by InMemoryContactStore() {
            override suspend fun fetchContacts(radioID: Uuid) = error("db locked")
        }
        val vm = viewModel(testContactService(store = store))

        vm.load()

        val state = vm.state.value
        assertTrue(state.hasLoadedOnce)
        assertFalse(state.isLoading)
        assertTrue(state.contacts.isEmpty())
    }

    @Test
    fun aCancelledLoadPropagatesAndKeepsTheSpinner() = runTest {
        val cancelled = CancellationException("superseded")
        val vm = viewModel(testContactService(store = FetchThrows { throw cancelled }))

        val error = assertFailsWith<CancellationException> { vm.load() }

        assertSame(cancelled, error)
        assertFalse(vm.state.value.hasLoadedOnce)
        assertTrue(vm.state.value.isLoading)
        assertTrue(vm.state.value.contacts.isEmpty())
    }

    @Test
    fun aLoadErrorPropagatesAndKeepsTheSpinner() = runTest {
        val probe = NewChatProbeError()
        val vm = viewModel(testContactService(store = FetchThrows { throw probe }))

        val thrown = assertFailsWith<NewChatProbeError> { vm.load() }

        assertSame(probe, thrown)
        assertFalse(vm.state.value.hasLoadedOnce)
        assertTrue(vm.state.value.isLoading)
        assertTrue(vm.state.value.contacts.isEmpty())
    }
}

private class NewChatProbeError : Error("probe")

private class FetchThrows(private val fail: () -> Nothing) : ContactStore by InMemoryContactStore() {
    override suspend fun fetchContacts(radioID: Uuid): List<ContactDto> = fail()
}
