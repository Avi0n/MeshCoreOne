package one.meshcore.shared.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.ContactStore
import one.meshcore.shared.contact.InMemoryContactStore
import one.meshcore.shared.contact.savedContactStore
import one.meshcore.shared.contact.testContactService
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Exercises the send-DM picker view model against a real [ContactService] backed by an
 * [InMemoryContactStore], driving the [SenderContactMatcher] resolve: an exact/case-insensitive name opens
 * the match(es), an unknown name resolves to none, ambiguous names all surface, blocked contacts are
 * dropped, and a disconnected graph resolves empty. Mirrors the iOS `SendDMSheet` load.
 */
class SendDMViewModelTest {
    private fun viewModel(service: ContactService?): SendDMViewModel = SendDMViewModel(
        dependencies = SendDMViewModel.Dependencies(contactService = { service }),
    )

    @Test
    fun exactNameResolvesToTheContactAndClearsLoading() = runTest {
        val alice = ChatFixtures.contact("Alice")
        val vm = viewModel(testContactService(store = savedContactStore(alice, ChatFixtures.contact("Bob"))))
        vm.load(senderName = "Alice", radioID = ChatFixtures.RADIO)
        assertEquals(listOf(alice.id), vm.state.value.matchingContacts.map { it.id })
        assertFalse(vm.state.value.isLoading)
    }

    @Test
    fun caseInsensitiveNameResolvesToTheContact() = runTest {
        val alice = ChatFixtures.contact("Alice")
        val vm = viewModel(testContactService(store = savedContactStore(alice)))
        vm.load(senderName = "ALICE", radioID = ChatFixtures.RADIO)
        assertEquals(listOf(alice.id), vm.state.value.matchingContacts.map { it.id })
    }

    @Test
    fun unknownNameResolvesToNoMatches() = runTest {
        val vm = viewModel(testContactService(store = savedContactStore(ChatFixtures.contact("Alice"))))
        vm.load(senderName = "Nobody", radioID = ChatFixtures.RADIO)
        assertTrue(vm.state.value.matchingContacts.isEmpty())
        assertFalse(vm.state.value.isLoading)
    }

    @Test
    fun ambiguousNameSurfacesEveryMatch() = runTest {
        val alice1 = ChatFixtures.contact("Alice")
        val alice2 = ChatFixtures.contact("alice")
        val vm = viewModel(testContactService(store = savedContactStore(alice1, alice2, ChatFixtures.contact("Bob"))))
        vm.load(senderName = "Alice", radioID = ChatFixtures.RADIO)
        assertEquals(setOf(alice1.id, alice2.id), vm.state.value.matchingContacts.map { it.id }.toSet())
    }

    @Test
    fun blockedMatchesAreExcluded() = runTest {
        val visible = ChatFixtures.contact("Alice")
        val blocked = ChatFixtures.contact("Alice", isBlocked = true)
        val vm = viewModel(testContactService(store = savedContactStore(visible, blocked)))
        vm.load(senderName = "Alice", radioID = ChatFixtures.RADIO)
        assertEquals(listOf(visible.id), vm.state.value.matchingContacts.map { it.id })
    }

    @Test
    fun disconnectedLoadResolvesEmptyAndClearsLoading() = runTest {
        val vm = viewModel(service = null)
        vm.load(senderName = "Alice", radioID = ChatFixtures.RADIO)
        assertTrue(vm.state.value.matchingContacts.isEmpty())
        assertFalse(vm.state.value.isLoading)
    }

    @Test
    fun replacementGraphReloadsMatchesFromCurrentService() = runTest {
        val first = ChatFixtures.contact("Alice")
        val replacement = ChatFixtures.contact("Alice")
        var live = testContactService(store = savedContactStore(first))
        val vm = SendDMViewModel(
            dependencies = SendDMViewModel.Dependencies(contactService = { live }),
        )
        vm.load(senderName = "Alice", radioID = ChatFixtures.RADIO)

        live = testContactService(store = savedContactStore(replacement))
        vm.load(senderName = "Alice", radioID = ChatFixtures.RADIO)

        assertEquals(listOf(replacement.id), vm.state.value.matchingContacts.map { it.id })
        assertFalse(vm.state.value.isLoading)
    }

    @Test
    fun disconnectClearsMatchesFromRetiredService() = runTest {
        val alice = ChatFixtures.contact("Alice")
        var live: ContactService? = testContactService(store = savedContactStore(alice))
        val vm = SendDMViewModel(
            dependencies = SendDMViewModel.Dependencies(contactService = { live }),
        )
        vm.load(senderName = "Alice", radioID = ChatFixtures.RADIO)

        live = null
        vm.load(senderName = "Alice", radioID = ChatFixtures.RADIO)

        assertTrue(vm.state.value.matchingContacts.isEmpty())
        assertFalse(vm.state.value.isLoading)
    }

    @Test
    fun persistFaultClearsSpinnerAndShowsNoMatches() = runTest {
        val store = object : ContactStore by InMemoryContactStore() {
            override suspend fun fetchContacts(radioID: Uuid) = error("db locked")
        }
        val vm = viewModel(testContactService(store = store))

        vm.load(senderName = "Alice", radioID = ChatFixtures.RADIO)

        assertTrue(vm.state.value.matchingContacts.isEmpty())
        assertFalse(vm.state.value.isLoading)
    }

    @Test
    fun aCancelledLoadKeepsTheSpinner() = runTest {
        val cancelled = CancellationException("superseded")
        val vm = viewModel(testContactService(store = SendDmFetchThrows { throw cancelled }))

        val thrown = assertFailsWith<CancellationException> {
            vm.load(senderName = "Alice", radioID = ChatFixtures.RADIO)
        }

        assertSame(cancelled, thrown)
        assertTrue(vm.state.value.matchingContacts.isEmpty())
        assertTrue(vm.state.value.isLoading)
    }

    @Test
    fun aLoadErrorClearsTheSpinner() = runTest {
        val vm = viewModel(testContactService(store = SendDmFetchThrows { throw SendDmLoadProbeError() }))

        vm.load(senderName = "Alice", radioID = ChatFixtures.RADIO)

        assertTrue(vm.state.value.matchingContacts.isEmpty())
        assertFalse(vm.state.value.isLoading)
    }
}

private class SendDmLoadProbeError : Error("probe")

private class SendDmFetchThrows(private val fail: () -> Nothing) : ContactStore by InMemoryContactStore() {
    override suspend fun fetchContacts(radioID: Uuid): List<ContactDto> = fail()
}
