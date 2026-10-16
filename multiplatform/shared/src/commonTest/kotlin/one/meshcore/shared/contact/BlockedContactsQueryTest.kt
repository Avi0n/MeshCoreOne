package one.meshcore.shared.contact

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.DebugLogLevel
import one.meshcore.shared.settings.PersistentLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The Blocked Contacts screen query and its view model, plus the end-to-end block path through
 * [ContactService] wired to the production [ContactCleanupCoordinator]: blocking a contact persists it to
 * the blocked list, makes it queryable, and deletes its channel messages; unblocking drops it.
 */
class BlockedContactsQueryTest {
    @Test
    fun blockingAContactMakesItQueryableAndDeletesItsChannelMessages() = runTest {
        val store = InMemoryContactStore()
        val channelMessages = RecordingChannelMessageDeleter()
        val blockedSenderStore = InMemoryBlockedChannelSenderStore()
        val cache = FakeBlockedNamesCache()
        val refresher = ContactBlockedNamesRefresher(store, blockedSenderStore, cache)
        val coordinator = ContactCleanupCoordinator(
            contactStore = store,
            channelMessageDeleter = channelMessages,
            blockedNamesRefresher = refresher,
            syncCoordinator = RecordingContactSyncCoordinator(),
        )
        val service = testContactService(store = store, cleanup = coordinator)

        val contactID = store.saveContact(ContactTestIds.RADIO, contactFrame(0x11, name = "Zed"))
        service.updateContactPreferences(contactID, isBlocked = true)

        val blocked = service.getBlockedContacts(ContactTestIds.RADIO)
        assertEquals(listOf(contactID), blocked.map { it.id })
        assertEquals(1, channelMessages.calls.size)
        assertEquals("Zed", channelMessages.calls[0].senderName)
        assertEquals(setOf("Zed"), cache.lastNames)
    }

    @Test
    fun unblockingRemovesFromTheQuery() = runTest {
        val store = InMemoryContactStore()
        val service = testContactService(store = store)
        val contactID = store.saveContact(ContactTestIds.RADIO, contactFrame(0x11, name = "Zed"))

        service.updateContactPreferences(contactID, isBlocked = true)
        assertEquals(1, service.getBlockedContacts(ContactTestIds.RADIO).size)

        service.updateContactPreferences(contactID, isBlocked = false)
        assertTrue(service.getBlockedContacts(ContactTestIds.RADIO).isEmpty())
    }

    @Test
    fun blockedContactsAreSortedByName() = runTest {
        val store = InMemoryContactStore()
        suspend fun seed(keyByte: Int, name: String, isBlocked: Boolean) = store.saveContact(
            contactDto(
                id = ContactTestIds.fresh(),
                radioID = ContactTestIds.RADIO,
                keyByte = keyByte,
                name = name,
                isBlocked = isBlocked,
            ),
        )
        seed(0x03, "Charlie", isBlocked = true)
        seed(0x01, "Alice", isBlocked = true)
        seed(0x02, "Bob", isBlocked = false)

        val blocked = store.fetchBlockedContacts(ContactTestIds.RADIO)

        assertEquals(listOf("Alice", "Charlie"), blocked.map { it.name })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun blockedNamesRefreshPersistFaultRecordsIosDumpAndClearsCache() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val cache = FakeBlockedNamesCache().also { it.lastNames = setOf("kept") }
        val store = object : ContactStore by InMemoryContactStore() {
            override suspend fun fetchBlockedContacts(radioID: Uuid) = error("db locked")
        }
        val refresher = ContactBlockedNamesRefresher(
            contactStore = store,
            blockedSenderStore = InMemoryBlockedChannelSenderStore(),
            cache = cache,
            logger = PersistentLogger(
                scope = this,
                category = "SyncCoordinator",
                record = { recorded += it },
            ),
        )

        refresher.refresh(ContactTestIds.RADIO)

        val line = recorded.single { it.message.contains("Failed to refresh blocked names cache") }
        assertEquals(DebugLogLevel.ERROR, line.level)
        assertEquals("SyncCoordinator", line.category)
        assertTrue(line.message.contains("db locked"))
        assertEquals(emptySet<String>(), cache.lastNames)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aCancelledBlockedNamesRefreshKeepsTheCache() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val cache = FakeBlockedNamesCache().also { it.lastNames = setOf("kept") }
        val stop = CancellationException("superseded")
        val store = object : ContactStore by InMemoryContactStore() {
            override suspend fun fetchBlockedContacts(radioID: Uuid): Nothing = throw stop
        }
        val cancelled = assertFailsWith<CancellationException> {
            namesRefresher(store, cache, recorded, this).refresh(ContactTestIds.RADIO)
        }
        assertSame(stop, cancelled)
        assertEquals(setOf("kept"), cache.lastNames)
        assertTrue(recorded.isEmpty())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aBlockedNamesRefreshErrorKeepsTheCache() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val cache = FakeBlockedNamesCache().also { it.lastNames = setOf("kept") }
        val store = object : ContactStore by InMemoryContactStore() {
            override suspend fun fetchBlockedContacts(radioID: Uuid): Nothing = throw BlockedContactsProbeError()
        }
        assertFailsWith<BlockedContactsProbeError> {
            namesRefresher(store, cache, recorded, this).refresh(ContactTestIds.RADIO)
        }
        assertEquals(setOf("kept"), cache.lastNames)
        assertTrue(recorded.isEmpty())
    }

    @Test
    fun viewModelLoadsBlockedContactsForTheLiveRadio() = runTest {
        val store = InMemoryContactStore()
        val service = testContactService(store = store)
        store.saveContact(contactDto(ContactTestIds.fresh(), ContactTestIds.RADIO, name = "Blocked", isBlocked = true))
        val viewModel = BlockedContactsViewModel(
            scope = backgroundScope,
            dependencies = BlockedContactsViewModel.Dependencies(
                contactService = { service },
                radioId = { ContactTestIds.RADIO },
                errorText = { LOCALIZED_ERROR },
            ),
        )

        viewModel.load()

        val state = viewModel.state.value
        assertTrue(state.hasLoadedOnce)
        assertEquals(listOf("Blocked"), state.contacts.map { it.name })
    }

    @Test
    fun viewModelLoadMarksTheListLoadedWhenDisconnected() = runTest {
        val viewModel = BlockedContactsViewModel(
            scope = backgroundScope,
            dependencies = BlockedContactsViewModel.Dependencies(
                contactService = { null },
                radioId = { null },
                errorText = { LOCALIZED_ERROR },
            ),
        )

        viewModel.load()

        val state = viewModel.state.value
        assertTrue(state.hasLoadedOnce)
        assertTrue(state.contacts.isEmpty())
        assertFalse(state.isLoading)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun bindServiceEventsReloadsWhenDeviceDeletesABlockedContact() = runTest(UnconfinedTestDispatcher()) {
        val store = InMemoryContactStore()
        val key = publicKey(0x11)
        store.saveContact(
            contactDto(
                id = ContactTestIds.fresh(),
                radioID = ContactTestIds.RADIO,
                keyByte = 0x11,
                name = "Zed",
                isBlocked = true,
            ),
        )
        val service = testContactService(store = store)
        val viewModel = BlockedContactsViewModel(
            scope = backgroundScope,
            dependencies = BlockedContactsViewModel.Dependencies(
                contactService = { service },
                radioId = { ContactTestIds.RADIO },
                errorText = { LOCALIZED_ERROR },
            ),
        )
        viewModel.load()
        assertEquals(listOf("Zed"), viewModel.state.value.contacts.map { it.name })

        viewModel.bindServiceEvents()
        assertTrue(service.handleDeviceContactDeleted(ContactTestIds.RADIO, key))

        assertTrue(viewModel.state.value.contacts.isEmpty())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun closeStopsServiceEventReloads() = runTest(UnconfinedTestDispatcher()) {
        val store = InMemoryContactStore()
        val key = publicKey(0x11)
        store.saveContact(
            contactDto(
                id = ContactTestIds.fresh(),
                radioID = ContactTestIds.RADIO,
                keyByte = 0x11,
                name = "Zed",
                isBlocked = true,
            ),
        )
        val service = testContactService(store = store)
        val viewModel = BlockedContactsViewModel(
            scope = backgroundScope,
            dependencies = BlockedContactsViewModel.Dependencies(
                contactService = { service },
                radioId = { ContactTestIds.RADIO },
                errorText = { LOCALIZED_ERROR },
            ),
        )
        viewModel.load()
        viewModel.bindServiceEvents()

        viewModel.close()
        assertTrue(service.handleDeviceContactDeleted(ContactTestIds.RADIO, key))

        assertEquals(listOf("Zed"), viewModel.state.value.contacts.map { it.name })
    }

    @Test
    fun aLoadFailureShowsFallbackNotEnglish() = runTest {
        val store = object : ContactStore by InMemoryContactStore() {
            override suspend fun fetchBlockedContacts(radioID: Uuid) = error("radio down")
        }
        val viewModel = BlockedContactsViewModel(
            scope = backgroundScope,
            dependencies = BlockedContactsViewModel.Dependencies(
                contactService = { testContactService(store = store) },
                radioId = { ContactTestIds.RADIO },
                errorText = { LOCALIZED_ERROR },
            ),
        )

        viewModel.load()

        assertEquals(LOCALIZED_ERROR, viewModel.state.value.errorMessage)
        assertFalse(viewModel.state.value.isLoading)
    }

    @Test
    fun clearingABlockedContactsErrorDoesNotRadio() = runTest {
        var serviceReads = 0
        var contactReads = 0
        val session = FakeContactSession()
        session.onGetContacts = { contactReads += 1 }
        val store = object : ContactStore by InMemoryContactStore() {
            override suspend fun fetchBlockedContacts(radioID: Uuid) = error("radio down")
        }
        val viewModel = BlockedContactsViewModel(
            scope = backgroundScope,
            dependencies = BlockedContactsViewModel.Dependencies(
                contactService = {
                    serviceReads += 1
                    testContactService(session = session, store = store)
                },
                radioId = { ContactTestIds.RADIO },
                errorText = { LOCALIZED_ERROR },
            ),
        )
        viewModel.load()
        assertEquals(LOCALIZED_ERROR, viewModel.state.value.errorMessage)
        val reads = serviceReads
        val fetches = contactReads
        val lookups = session.getContactKeys.size

        viewModel.clearError()

        assertEquals(reads, serviceReads)
        assertEquals(fetches, contactReads)
        assertEquals(lookups, session.getContactKeys.size)
        assertEquals(null, viewModel.state.value.errorMessage)
        assertFalse(viewModel.state.value.isLoading)
        assertTrue(viewModel.state.value.contacts.isEmpty())
    }

    @Test
    fun aCancelledLoadPropagatesWithoutAnError() = runTest {
        val cancelled = CancellationException("superseded")
        val store = object : ContactStore by InMemoryContactStore() {
            override suspend fun fetchBlockedContacts(radioID: Uuid) = throw cancelled
        }
        val viewModel = blockedContactsViewModel(backgroundScope, store)

        val thrown = assertFailsWith<CancellationException> { viewModel.load() }
        assertSame(cancelled, thrown)

        val state = viewModel.state.value
        assertTrue(state.isLoading)
        assertFalse(state.hasLoadedOnce)
        assertEquals(null, state.errorMessage)
    }

    @Test
    fun aLoadErrorPropagatesWithoutAnErrorMessage() = runTest {
        val fault = BlockedContactsProbeError()
        val store = object : ContactStore by InMemoryContactStore() {
            override suspend fun fetchBlockedContacts(radioID: Uuid) = throw fault
        }
        val viewModel = blockedContactsViewModel(backgroundScope, store)

        val thrown = assertFailsWith<BlockedContactsProbeError> { viewModel.load() }
        assertSame(fault, thrown)

        val state = viewModel.state.value
        assertTrue(state.isLoading)
        assertFalse(state.hasLoadedOnce)
        assertEquals(null, state.errorMessage)
    }

    private fun namesRefresher(
        store: ContactStore,
        cache: FakeBlockedNamesCache,
        recorded: MutableList<DebugLogEntryDto>,
        scope: CoroutineScope,
    ) = ContactBlockedNamesRefresher(
        contactStore = store,
        blockedSenderStore = InMemoryBlockedChannelSenderStore(),
        cache = cache,
        logger = PersistentLogger(
            scope = scope,
            category = "SyncCoordinator",
            record = { recorded += it },
        ),
    )

    private fun blockedContactsViewModel(scope: CoroutineScope, store: ContactStore) = BlockedContactsViewModel(
        scope = scope,
        dependencies = BlockedContactsViewModel.Dependencies(
            contactService = { testContactService(store = store) },
            radioId = { ContactTestIds.RADIO },
            errorText = { LOCALIZED_ERROR },
        ),
    )
}

private class BlockedContactsProbeError : Error("probe")

private const val LOCALIZED_ERROR = "Localized error"
