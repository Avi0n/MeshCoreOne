package one.meshcore.shared.chat.pathdetail

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.BlockedChannelSenderDto
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.chat.ChatFixtures
import one.meshcore.shared.contact.BlockedChannelSenderService
import one.meshcore.shared.contact.BlockedChannelSenderStore
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.ContactStore
import one.meshcore.shared.contact.FakeContactSession
import one.meshcore.shared.contact.InMemoryBlockedChannelSenderStore
import one.meshcore.shared.contact.InMemoryContactStore
import one.meshcore.shared.contact.RecordingBlockedNamesRefresher
import one.meshcore.shared.contact.RecordingChannelMessageDeleter
import one.meshcore.shared.contact.RecordingContactSyncCoordinator
import one.meshcore.shared.contact.contactDto
import one.meshcore.shared.contact.testContactService
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Exercises the block-channel-sender-from-message flow: [BlockSenderViewModel.load] resolves the sender name
 * to also-blockable contacts (case-insensitive, not-already-blocked), and [BlockSenderViewModel.blockAnyway]
 * persists the name block, deletes the sender's channel messages via the W4 [BlockedChannelSenderService],
 * and blocks the contacts the user selected. Mirrors the iOS `performBlock` composition.
 */
class BlockSenderViewModelTest {
    private class SenderLoadProbeError : Error("probe")
    private class SenderPersistProbeError : Error("probe")
    private class ContactBlockProbeError : Error("probe")
    private class Fixture {
        val contactStore = InMemoryContactStore()
        val channelMessages = RecordingChannelMessageDeleter()
        val senderService = BlockedChannelSenderService(
            store = InMemoryBlockedChannelSenderStore(),
            channelMessageDeleter = channelMessages,
            blockedNamesRefresher = RecordingBlockedNamesRefresher(),
            syncCoordinator = RecordingContactSyncCoordinator(),
            now = { CLOCK },
            newId = { ChatFixtures.freshUuid() },
        )
        val contactService: ContactService = testContactService(store = contactStore)

        fun viewModel() = BlockSenderViewModel(
            BlockSenderViewModel.Dependencies(
                contactService = { contactService },
                blockedChannelSenderService = { senderService },
            ),
        )
    }

    @Test
    fun loadResolvesMatchingUnblockedContacts() = runTest {
        val f = Fixture()
        val alice = contactDto(ChatFixtures.freshUuid(), ChatFixtures.RADIO, name = "Alice")
        f.contactStore.saveContact(alice)
        f.contactStore.saveContact(
            contactDto(ChatFixtures.freshUuid(), ChatFixtures.RADIO, name = "Alice", isBlocked = true),
        )
        val vm = f.viewModel()

        vm.load(senderName = "alice", radioID = ChatFixtures.RADIO)

        assertEquals(listOf(alice.id), vm.state.value.matchingContacts.map { it.id })
        assertFalse(vm.state.value.isLoading)
    }

    @Test
    fun replacementGraphReloadsMatchesAndClearsRetiredSelection() = runTest {
        val first = contactDto(ChatFixtures.freshUuid(), ChatFixtures.RADIO, name = "Alice")
        val replacement = contactDto(ChatFixtures.freshUuid(), ChatFixtures.RADIO, name = "Alice")
        var live = testContactService(
            store = InMemoryContactStore().also { it.saveContact(first) },
        )
        val vm = BlockSenderViewModel(
            BlockSenderViewModel.Dependencies(
                contactService = { live },
                blockedChannelSenderService = { null },
            ),
        )
        vm.load(senderName = "Alice", radioID = ChatFixtures.RADIO)
        vm.toggleContact(first.id)

        live = testContactService(
            store = InMemoryContactStore().also { it.saveContact(replacement) },
        )
        vm.load(senderName = "Alice", radioID = ChatFixtures.RADIO)

        assertEquals(listOf(replacement.id), vm.state.value.matchingContacts.map { it.id })
        assertTrue(vm.state.value.selectedContactIDs.isEmpty())
        assertFalse(vm.state.value.isLoading)
    }

    @Test
    fun disconnectClearsMatchesAndSelectionFromRetiredService() = runTest {
        val alice = contactDto(ChatFixtures.freshUuid(), ChatFixtures.RADIO, name = "Alice")
        var live: ContactService? = testContactService(
            store = InMemoryContactStore().also { it.saveContact(alice) },
        )
        val vm = BlockSenderViewModel(
            BlockSenderViewModel.Dependencies(
                contactService = { live },
                blockedChannelSenderService = { null },
            ),
        )
        vm.load(senderName = "Alice", radioID = ChatFixtures.RADIO)
        vm.toggleContact(alice.id)

        live = null
        vm.load(senderName = "Alice", radioID = ChatFixtures.RADIO)

        assertTrue(vm.state.value.matchingContacts.isEmpty())
        assertTrue(vm.state.value.selectedContactIDs.isEmpty())
        assertFalse(vm.state.value.isLoading)
    }

    @Test
    fun blockAnywayBlocksTheNameAndDeletesChannelMessages() = runTest {
        val f = Fixture()
        val vm = f.viewModel()

        vm.blockAnyway(senderName = "Spammer", radioID = ChatFixtures.RADIO)

        assertEquals(listOf("Spammer"), f.senderService.blockedSenders(ChatFixtures.RADIO).map { it.name })
        assertEquals(1, f.channelMessages.calls.size)
        assertEquals("Spammer", f.channelMessages.calls[0].senderName)
        assertTrue(vm.state.value.blockCompleted)
        assertFalse(vm.state.value.isBlocking)
    }

    @Test
    fun blockAnywayAlsoBlocksSelectedContacts() = runTest {
        val f = Fixture()
        val alice = contactDto(
            ChatFixtures.freshUuid(),
            ChatFixtures.RADIO,
            name = "Alice",
            typeRawValue = ContactType.CHAT.raw,
        )
        f.contactStore.saveContact(alice)
        val vm = f.viewModel()
        vm.load(senderName = "Alice", radioID = ChatFixtures.RADIO)
        vm.toggleContact(alice.id)

        vm.blockAnyway(senderName = "Alice", radioID = ChatFixtures.RADIO)

        assertTrue(f.contactStore.fetchContact(alice.id)?.isBlocked == true)
    }

    @Test
    fun blockAnywayWithoutLiveServicesDoesNotBlockMatchingContacts() = runTest {
        val f = Fixture()
        val alice = contactDto(
            ChatFixtures.freshUuid(),
            ChatFixtures.RADIO,
            name = "Alice",
            typeRawValue = ContactType.CHAT.raw,
        )
        f.contactStore.saveContact(alice)
        val vm = BlockSenderViewModel(
            BlockSenderViewModel.Dependencies(
                contactService = { f.contactService },
                blockedChannelSenderService = { null },
            ),
        )
        vm.load(senderName = "Alice", radioID = ChatFixtures.RADIO)
        vm.toggleContact(alice.id)

        vm.blockAnyway(senderName = "Alice", radioID = ChatFixtures.RADIO)

        assertFalse(f.contactStore.fetchContact(alice.id)?.isBlocked == true)
        assertTrue(f.channelMessages.calls.isEmpty())
        assertTrue(vm.state.value.blockCompleted)
    }

    @Test
    fun toggleContactAddsAndRemovesSelection() {
        val f = Fixture()
        val vm = f.viewModel()
        val id = ChatFixtures.freshUuid()

        vm.toggleContact(id)
        assertEquals(setOf(id), vm.state.value.selectedContactIDs)
        vm.toggleContact(id)
        assertTrue(vm.state.value.selectedContactIDs.isEmpty())
    }

    @Test
    fun selectingABlockSenderContactDoesNotRadio() = runTest {
        val session = FakeContactSession()
        val store = InMemoryContactStore()
        val alice = contactDto(ChatFixtures.freshUuid(), ChatFixtures.RADIO, name = "Alice")
        store.saveContact(alice)
        val vm = BlockSenderViewModel(
            BlockSenderViewModel.Dependencies(
                contactService = { testContactService(session = session, store = store) },
                blockedChannelSenderService = { null },
            ),
        )
        vm.load(senderName = "Alice", radioID = ChatFixtures.RADIO)
        val discoveries = session.pathDiscoveryDestinations.size
        val flags = session.changeFlagsCalls.size
        val lookups = session.getContactKeys.size
        val adds = session.addedContacts.size
        val removes = session.removedPublicKeys.size
        val resets = session.resetPathPublicKeys.size
        val shares = session.sharedPublicKeys.size

        vm.toggleContact(alice.id)

        assertEquals(discoveries, session.pathDiscoveryDestinations.size)
        assertEquals(flags, session.changeFlagsCalls.size)
        assertEquals(lookups, session.getContactKeys.size)
        assertEquals(adds, session.addedContacts.size)
        assertEquals(removes, session.removedPublicKeys.size)
        assertEquals(resets, session.resetPathPublicKeys.size)
        assertEquals(shares, session.sharedPublicKeys.size)
        assertEquals(setOf(alice.id), vm.state.value.selectedContactIDs)
        assertFalse(store.fetchContact(alice.id)?.isBlocked == true)
        assertFalse(vm.state.value.blockCompleted)
        assertFalse(vm.state.value.isBlocking)
    }

    @Test
    fun loadPersistFaultClearsSpinner() = runTest {
        val store = object : ContactStore by InMemoryContactStore() {
            override suspend fun fetchContacts(radioID: Uuid) = error("db locked")
        }
        val vm = BlockSenderViewModel(
            BlockSenderViewModel.Dependencies(
                contactService = { testContactService(store = store) },
                blockedChannelSenderService = { null },
            ),
        )

        vm.load(senderName = "Alice", radioID = ChatFixtures.RADIO)

        assertFalse(vm.state.value.isLoading)
        assertTrue(vm.state.value.matchingContacts.isEmpty())
    }

    @Test
    fun aCancelledSenderLoadLeavesTheSpinner() = runTest {
        val store = object : ContactStore by InMemoryContactStore() {
            override suspend fun fetchContacts(radioID: Uuid): List<ContactDto> = throw CancellationException("stopped")
        }
        val vm = BlockSenderViewModel(
            BlockSenderViewModel.Dependencies(
                contactService = { testContactService(store = store) },
                blockedChannelSenderService = { null },
            ),
        )

        val cancelled = assertFailsWith<CancellationException> {
            vm.load(senderName = "Alice", radioID = ChatFixtures.RADIO)
        }

        assertEquals("stopped", cancelled.message)
        assertTrue(vm.state.value.isLoading)
        assertTrue(vm.state.value.matchingContacts.isEmpty())
    }

    @Test
    fun aSenderLoadErrorLeavesTheSpinner() = runTest {
        val store = object : ContactStore by InMemoryContactStore() {
            override suspend fun fetchContacts(radioID: Uuid): List<ContactDto> = throw SenderLoadProbeError()
        }
        val vm = BlockSenderViewModel(
            BlockSenderViewModel.Dependencies(
                contactService = { testContactService(store = store) },
                blockedChannelSenderService = { null },
            ),
        )

        assertFailsWith<SenderLoadProbeError> {
            vm.load(senderName = "Alice", radioID = ChatFixtures.RADIO)
        }

        assertTrue(vm.state.value.isLoading)
        assertTrue(vm.state.value.matchingContacts.isEmpty())
    }

    @Test
    fun blockAnywayPersistFaultStillDismisses() = runTest {
        val senderStore = object : BlockedChannelSenderStore by InMemoryBlockedChannelSenderStore() {
            override suspend fun save(sender: BlockedChannelSenderDto) = error("db locked")
        }
        val senderService = BlockedChannelSenderService(
            store = senderStore,
            channelMessageDeleter = RecordingChannelMessageDeleter(),
            blockedNamesRefresher = RecordingBlockedNamesRefresher(),
            syncCoordinator = RecordingContactSyncCoordinator(),
            now = { CLOCK },
            newId = { ChatFixtures.freshUuid() },
        )
        val alice = contactDto(ChatFixtures.freshUuid(), ChatFixtures.RADIO, name = "Alice")
        val contactStore = InMemoryContactStore()
        contactStore.saveContact(alice)
        val vm = BlockSenderViewModel(
            BlockSenderViewModel.Dependencies(
                contactService = { testContactService(store = contactStore) },
                blockedChannelSenderService = { senderService },
            ),
        )
        vm.toggleContact(alice.id)

        vm.blockAnyway(senderName = "Spammer", radioID = ChatFixtures.RADIO)

        assertTrue(vm.state.value.blockCompleted)
        assertFalse(vm.state.value.isBlocking)
        assertTrue(contactStore.fetchContact(alice.id)?.isBlocked == true)
    }

    @Test
    fun aCancelledSenderPersistDismissesWithoutBlocking() = runTest {
        val senderStore = object : BlockedChannelSenderStore by InMemoryBlockedChannelSenderStore() {
            override suspend fun save(sender: BlockedChannelSenderDto) = throw CancellationException("stopped")
        }
        val senderService = BlockedChannelSenderService(
            store = senderStore,
            channelMessageDeleter = RecordingChannelMessageDeleter(),
            blockedNamesRefresher = RecordingBlockedNamesRefresher(),
            syncCoordinator = RecordingContactSyncCoordinator(),
            now = { CLOCK },
            newId = { ChatFixtures.freshUuid() },
        )
        val alice = contactDto(ChatFixtures.freshUuid(), ChatFixtures.RADIO, name = "Alice")
        val contactStore = InMemoryContactStore()
        contactStore.saveContact(alice)
        val vm = BlockSenderViewModel(
            BlockSenderViewModel.Dependencies(
                contactService = { testContactService(store = contactStore) },
                blockedChannelSenderService = { senderService },
            ),
        )
        vm.toggleContact(alice.id)

        val cancelled = assertFailsWith<CancellationException> {
            vm.blockAnyway(senderName = "Spammer", radioID = ChatFixtures.RADIO)
        }

        assertEquals("stopped", cancelled.message)
        assertTrue(vm.state.value.blockCompleted)
        assertFalse(vm.state.value.isBlocking)
        assertFalse(contactStore.fetchContact(alice.id)?.isBlocked == true)
    }

    @Test
    fun aSenderPersistErrorDismissesWithoutBlocking() = runTest {
        val senderStore = object : BlockedChannelSenderStore by InMemoryBlockedChannelSenderStore() {
            override suspend fun save(sender: BlockedChannelSenderDto) = throw SenderPersistProbeError()
        }
        val senderService = BlockedChannelSenderService(
            store = senderStore,
            channelMessageDeleter = RecordingChannelMessageDeleter(),
            blockedNamesRefresher = RecordingBlockedNamesRefresher(),
            syncCoordinator = RecordingContactSyncCoordinator(),
            now = { CLOCK },
            newId = { ChatFixtures.freshUuid() },
        )
        val alice = contactDto(ChatFixtures.freshUuid(), ChatFixtures.RADIO, name = "Alice")
        val contactStore = InMemoryContactStore()
        contactStore.saveContact(alice)
        val vm = BlockSenderViewModel(
            BlockSenderViewModel.Dependencies(
                contactService = { testContactService(store = contactStore) },
                blockedChannelSenderService = { senderService },
            ),
        )
        vm.toggleContact(alice.id)

        assertFailsWith<SenderPersistProbeError> {
            vm.blockAnyway(senderName = "Spammer", radioID = ChatFixtures.RADIO)
        }

        assertTrue(vm.state.value.blockCompleted)
        assertFalse(vm.state.value.isBlocking)
        assertFalse(contactStore.fetchContact(alice.id)?.isBlocked == true)
    }

    @Test
    fun aContactBlockFaultStillBlocksTheOthers() = runTest {
        val f = Fixture()
        val alice = contactDto(ChatFixtures.freshUuid(), ChatFixtures.RADIO, name = "Alice")
        f.contactStore.saveContact(alice)
        val vm = f.viewModel()
        vm.toggleContact(ChatFixtures.freshUuid())
        vm.toggleContact(alice.id)

        vm.blockAnyway(senderName = "Spammer", radioID = ChatFixtures.RADIO)

        assertTrue(f.contactStore.fetchContact(alice.id)?.isBlocked == true)
        assertTrue(vm.state.value.blockCompleted)
        assertFalse(vm.state.value.isBlocking)
    }

    @Test
    fun aCancelledContactBlockDismissesWithoutBlockingTheRest() = runTest {
        val inner = InMemoryContactStore()
        val contactStore = object : ContactStore by inner {
            override suspend fun updateContactFields(id: Uuid, update: (ContactDto) -> ContactDto): ContactDto? =
                throw CancellationException("stopped")
        }
        val alice = contactDto(ChatFixtures.freshUuid(), ChatFixtures.RADIO, name = "Alice")
        inner.saveContact(alice)
        val senderService = BlockedChannelSenderService(
            store = InMemoryBlockedChannelSenderStore(),
            channelMessageDeleter = RecordingChannelMessageDeleter(),
            blockedNamesRefresher = RecordingBlockedNamesRefresher(),
            syncCoordinator = RecordingContactSyncCoordinator(),
            now = { CLOCK },
            newId = { ChatFixtures.freshUuid() },
        )
        val vm = BlockSenderViewModel(
            BlockSenderViewModel.Dependencies(
                contactService = { testContactService(store = contactStore) },
                blockedChannelSenderService = { senderService },
            ),
        )
        vm.toggleContact(alice.id)

        val cancelled = assertFailsWith<CancellationException> {
            vm.blockAnyway(senderName = "Spammer", radioID = ChatFixtures.RADIO)
        }

        assertEquals("stopped", cancelled.message)
        assertEquals(listOf("Spammer"), senderService.blockedSenders(ChatFixtures.RADIO).map { it.name })
        assertFalse(inner.fetchContact(alice.id)?.isBlocked == true)
        assertTrue(vm.state.value.blockCompleted)
        assertFalse(vm.state.value.isBlocking)
    }

    @Test
    fun aContactBlockErrorDismissesWithoutBlockingTheRest() = runTest {
        val inner = InMemoryContactStore()
        val contactStore = object : ContactStore by inner {
            override suspend fun updateContactFields(id: Uuid, update: (ContactDto) -> ContactDto): ContactDto? =
                throw ContactBlockProbeError()
        }
        val alice = contactDto(ChatFixtures.freshUuid(), ChatFixtures.RADIO, name = "Alice")
        inner.saveContact(alice)
        val senderService = BlockedChannelSenderService(
            store = InMemoryBlockedChannelSenderStore(),
            channelMessageDeleter = RecordingChannelMessageDeleter(),
            blockedNamesRefresher = RecordingBlockedNamesRefresher(),
            syncCoordinator = RecordingContactSyncCoordinator(),
            now = { CLOCK },
            newId = { ChatFixtures.freshUuid() },
        )
        val vm = BlockSenderViewModel(
            BlockSenderViewModel.Dependencies(
                contactService = { testContactService(store = contactStore) },
                blockedChannelSenderService = { senderService },
            ),
        )
        vm.toggleContact(alice.id)

        assertFailsWith<ContactBlockProbeError> {
            vm.blockAnyway(senderName = "Spammer", radioID = ChatFixtures.RADIO)
        }

        assertEquals(listOf("Spammer"), senderService.blockedSenders(ChatFixtures.RADIO).map { it.name })
        assertFalse(inner.fetchContact(alice.id)?.isBlocked == true)
        assertTrue(vm.state.value.blockCompleted)
        assertFalse(vm.state.value.isBlocking)
    }

    private companion object {
        const val CLOCK = 1_700_000_000L
    }
}
