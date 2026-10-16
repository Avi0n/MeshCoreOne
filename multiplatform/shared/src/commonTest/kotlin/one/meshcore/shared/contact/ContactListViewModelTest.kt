package one.meshcore.shared.contact

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Load, search, and segment selection for the nodes list.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactListViewModelTest {
    @Test
    fun loadPopulatesContactsAndMarksLoaded() = runTest {
        val service = testContactService(store = savedContactStore(chat(0x01, "Alice"), chat(0x02, "Bob")))
        val vm = viewModel(service)
        vm.load()
        val state = vm.state.value
        assertEquals(2, state.contacts.size)
        assertTrue(state.hasLoadedOnce)
        assertFalse(state.isLoading)
        assertNull(state.error)
    }

    @Test
    fun persistFaultClearsSpinnerAndSurfacesError() = runTest {
        val store = object : ContactStore by InMemoryContactStore() {
            override suspend fun fetchContacts(radioID: Uuid) = error("db locked")
        }
        val vm = viewModel(testContactService(store = store))

        vm.load()

        val state = vm.state.value
        assertTrue(state.hasLoadedOnce)
        assertFalse(state.isLoading)
        assertIs<ContactListError.ServiceMessage>(state.error)
        assertEquals(LOCALIZED_ERROR, (state.error as ContactListError.ServiceMessage).message)
    }

    @Test
    fun aLoadFailureShowsMappedCopyNotEnglish() = runTest {
        val store = object : ContactStore by InMemoryContactStore() {
            override suspend fun fetchContacts(radioID: Uuid) = error("radio down")
        }
        val vm = viewModel(testContactService(store = store), ListOptions(errorText = { "list fail" }))

        vm.load()

        val error = vm.state.value.error
        assertIs<ContactListError.ServiceMessage>(error)
        assertEquals("list fail", error.message)
    }

    @Test
    fun loadWhenDisconnectedMarksLoadedSoEmptyStateShowsNotSpinner() = runTest {
        // No live radio: the list must render the empty-state guidance, so hasLoadedOnce flips even though
        // there is nothing to load. A stuck-false flag is the endless-spinner bug.
        val vm = viewModel(service = null)
        vm.load()
        val state = vm.state.value
        assertTrue(state.hasLoadedOnce)
        assertTrue(state.contacts.isEmpty())
        assertFalse(state.isLoading)
    }

    @Test
    fun loadWhenRadioGoesAwayClearsPreviousContacts() = runTest {
        val service = testContactService(store = savedContactStore(chat(0x01, "Alice")))
        var radioPresent = true
        val vm = ContactListViewModel(
            scope = backgroundScope,
            dependencies = ContactListViewModel.Dependencies(
                contactService = { service.takeIf { radioPresent } },
                radioId = { radio.takeIf { radioPresent } },
                errorText = { LOCALIZED_ERROR },
            ),
        )
        vm.load()
        assertEquals(1, vm.state.value.contacts.size)

        radioPresent = false
        vm.load()
        assertTrue(vm.state.value.contacts.isEmpty())
        assertTrue(vm.state.value.hasLoadedOnce)
        assertTrue(vm.state.value.inboundHopByKey.isEmpty())
        assertTrue(vm.state.value.deletingIds.isEmpty())
        assertTrue(vm.state.value.pendingRemovalIds.isEmpty())
    }

    @Test
    fun bindServiceEventsReloadsOnAdvertisementAutoAdd() = runTest(UnconfinedTestDispatcher()) {
        val mem = InMemoryContactStore()
        mem.saveContact(chat(0x01, "Alice"))
        val key = publicKey(0x2F)
        val session = FakeContactSession().apply {
            singleContactLookup = meshContact(keyByte = 0x2F, name = "AutoAdded")
        }
        val service = testContactService(session = session, store = mem)
        val vm = viewModel(service)
        vm.load()
        assertEquals(1, vm.state.value.contacts.size)

        vm.bindServiceEvents()
        assertTrue(service.handleAdvertisement(radio, key))

        assertEquals(2, vm.state.value.contacts.size)
        assertTrue(vm.state.value.contacts.any { it.name == "AutoAdded" })
    }

    @Test
    fun closeStopsServiceEventReloads() = runTest(UnconfinedTestDispatcher()) {
        val mem = InMemoryContactStore().also { it.saveContact(chat(0x01, "Alice")) }
        val key = publicKey(0x2F)
        val session = FakeContactSession().apply {
            singleContactLookup = meshContact(keyByte = 0x2F, name = "AutoAdded")
        }
        val service = testContactService(session = session, store = mem)
        val vm = viewModel(service)
        vm.load()
        vm.bindServiceEvents()

        vm.close()
        assertTrue(service.handleAdvertisement(radio, key))

        assertEquals(listOf("Alice"), vm.state.value.contacts.map { it.name })
    }

    @Test
    fun bindListInvalidationsReloadsAfterAnAppSideContactAdd() = runTest(UnconfinedTestDispatcher()) {
        // Add Contact, QR scan, Discover "Add" and node-config import write through the service without a
        // device push, so only the app-wide invalidation tells the list its rows are stale.
        val mem = InMemoryContactStore()
        mem.saveContact(chat(0x01, "Alice"))
        val invalidations = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val vm = viewModel(testContactService(store = mem), ListOptions(listInvalidations = invalidations))
        vm.load()
        assertEquals(1, vm.state.value.contacts.size)

        vm.bindListInvalidations()
        mem.saveContact(chat(0x02, "Bob"))
        assertEquals(1, vm.state.value.contacts.size)

        invalidations.emit(Unit)

        assertEquals(2, vm.state.value.contacts.size)
        assertTrue(vm.state.value.contacts.any { it.name == "Bob" })
    }

    @Test
    fun bindListInvalidationsMovesTheCollectorToTheReplacementFlow() = runTest(UnconfinedTestDispatcher()) {
        val mem = InMemoryContactStore().also { it.saveContact(chat(0x01, "Alice")) }
        val original = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val replacement = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val vm = viewModel(testContactService(store = mem), ListOptions(listInvalidations = original))
        vm.load()
        vm.bindListInvalidations()
        vm.bindListInvalidations(replacement)
        mem.saveContact(chat(0x02, "Bob"))

        original.emit(Unit)
        assertEquals(listOf("Alice"), vm.state.value.contacts.map { it.name })

        replacement.emit(Unit)
        assertEquals(listOf("Alice", "Bob"), vm.state.value.contacts.map { it.name }.sorted())
    }

    @Test
    fun closeStopsListInvalidationReloads() = runTest(UnconfinedTestDispatcher()) {
        val mem = InMemoryContactStore().also { it.saveContact(chat(0x01, "Alice")) }
        val invalidations = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val vm = viewModel(testContactService(store = mem), ListOptions(listInvalidations = invalidations))
        vm.load()
        vm.bindListInvalidations()
        mem.saveContact(chat(0x02, "Bob"))

        vm.close()
        invalidations.emit(Unit)

        assertEquals(listOf("Alice"), vm.state.value.contacts.map { it.name })
    }

    @Test
    fun closePreventsLaterCollectorBinding() = runTest(UnconfinedTestDispatcher()) {
        val mem = InMemoryContactStore().also { it.saveContact(chat(0x01, "Alice")) }
        val invalidations = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val vm = viewModel(testContactService(store = mem), ListOptions(listInvalidations = invalidations))
        vm.load()
        vm.close()

        vm.bindServiceEvents()
        vm.bindListInvalidations()
        mem.saveContact(chat(0x02, "Bob"))
        invalidations.emit(Unit)

        assertEquals(listOf("Alice"), vm.state.value.contacts.map { it.name })
    }

    @Test
    fun searchFiltersAcrossTypesIgnoringSegment() = runTest {
        val store = InMemoryContactStore().also {
            it.saveContact(chat(0x01, "Alice"))
            it.saveContact(
                contactDto(
                    id = ContactTestIds.fresh(),
                    radioID = radio,
                    keyByte = 0x02,
                    name = "Bob",
                    typeRawValue = ContactType.REPEATER.raw,
                ),
            )
        }
        val vm = viewModel(testContactService(store = store))
        vm.load()
        assertEquals(2, vm.state.value.contacts.size)
        vm.setSearchText("bob")
        advanceUntilIdle()
        // Segment is CONTACTS (chat only), but a search spans all types and matches the repeater by name.
        assertEquals(listOf("Bob"), vm.filteredContacts(vm.state.value).map { it.name })
        assertTrue(vm.state.value.isSearching)
    }

    @Test
    fun keepingAnEmptyContactListSearchDoesNotRadio() = runTest {
        var serviceReads = 0
        var radioReads = 0
        var readyReads = 0
        val vm = ContactListViewModel(
            scope = backgroundScope,
            dependencies = ContactListViewModel.Dependencies(
                contactService = {
                    serviceReads += 1
                    null
                },
                radioId = {
                    radioReads += 1
                    null
                },
                isConnected = { false },
                isReady = {
                    readyReads += 1
                    false
                },
                userLocation = { null },
                inboundHops = { emptyMap() },
                sortOrderStore = null,
                listInvalidations = emptyFlow(),
                errorText = { "" },
            ),
        )
        assertEquals("", vm.state.value.searchText)
        assertFalse(vm.state.value.isSearching)
        assertEquals(NodeSegment.CONTACTS, vm.state.value.segment)
        assertFalse(vm.state.value.userSwitchedSegment)
        assertTrue(vm.state.value.contacts.isEmpty())
        assertFalse(vm.state.value.hasLoadedOnce)
        assertEquals(0, serviceReads)
        assertEquals(0, radioReads)
        assertEquals(0, readyReads)

        vm.setSearchText("")
        advanceUntilIdle()

        assertEquals(0, serviceReads)
        assertEquals(0, radioReads)
        assertEquals(0, readyReads)
        assertEquals("", vm.state.value.searchText)
        assertFalse(vm.state.value.isSearching)
        assertEquals(NodeSegment.CONTACTS, vm.state.value.segment)
        assertFalse(vm.state.value.userSwitchedSegment)
        assertTrue(vm.state.value.contacts.isEmpty())
        assertFalse(vm.state.value.hasLoadedOnce)
        assertFalse(vm.state.value.isLoading)
        assertNull(vm.state.value.error)
    }

    @Test
    fun searchingTheContactListDoesNotRadio() = runTest {
        var serviceReads = 0
        var radioReads = 0
        val vm = ContactListViewModel(
            scope = backgroundScope,
            dependencies = ContactListViewModel.Dependencies(
                contactService = {
                    serviceReads += 1
                    error("contact service")
                },
                radioId = {
                    radioReads += 1
                    error("radio")
                },
                isConnected = { error("connected") },
                isReady = { error("ready") },
                userLocation = { error("location") },
                inboundHops = { error("hops") },
                sortOrderStore = null,
                listInvalidations = emptyFlow(),
                errorText = { error("error text") },
            ),
        )

        vm.setSearchText("bob")
        advanceUntilIdle()

        assertEquals(0, serviceReads)
        assertEquals(0, radioReads)
        assertEquals("bob", vm.state.value.searchText)
        assertTrue(vm.state.value.isSearching)
        assertTrue(vm.state.value.contacts.isEmpty())
        assertFalse(vm.state.value.hasLoadedOnce)
        assertFalse(vm.state.value.isLoading)
        assertEquals(NodeSegment.CONTACTS, vm.state.value.segment)
    }

    @Test
    fun keepingTheContactsSegmentDoesNotRadio() = runTest {
        var serviceReads = 0
        var radioReads = 0
        val vm = ContactListViewModel(
            scope = backgroundScope,
            dependencies = ContactListViewModel.Dependencies(
                contactService = {
                    serviceReads += 1
                    error("contact service")
                },
                radioId = {
                    radioReads += 1
                    error("radio")
                },
                isConnected = { error("connected") },
                isReady = { error("ready") },
                userLocation = { error("location") },
                inboundHops = { error("hops") },
                sortOrderStore = null,
                listInvalidations = emptyFlow(),
                errorText = { error("error text") },
            ),
        )
        assertEquals(NodeSegment.CONTACTS, vm.state.value.segment)
        assertFalse(vm.state.value.userSwitchedSegment)
        assertEquals("", vm.state.value.searchText)
        assertTrue(vm.state.value.contacts.isEmpty())
        assertEquals(0, serviceReads)
        assertEquals(0, radioReads)

        vm.setSegment(NodeSegment.CONTACTS)
        advanceUntilIdle()

        assertEquals(0, serviceReads)
        assertEquals(0, radioReads)
        assertEquals(NodeSegment.CONTACTS, vm.state.value.segment)
        assertTrue(vm.state.value.userSwitchedSegment)
        assertEquals("", vm.state.value.searchText)
        assertTrue(vm.state.value.contacts.isEmpty())
        assertFalse(vm.state.value.hasLoadedOnce)
        assertFalse(vm.state.value.isLoading)
    }

    @Test
    fun choosingAContactListSegmentDoesNotRadio() = runTest {
        var serviceReads = 0
        var radioReads = 0
        val vm = ContactListViewModel(
            scope = backgroundScope,
            dependencies = ContactListViewModel.Dependencies(
                contactService = {
                    serviceReads += 1
                    error("contact service")
                },
                radioId = {
                    radioReads += 1
                    error("radio")
                },
                isConnected = { error("connected") },
                isReady = { error("ready") },
                userLocation = { error("location") },
                inboundHops = { error("hops") },
                sortOrderStore = null,
                listInvalidations = emptyFlow(),
                errorText = { error("error text") },
            ),
        )
        assertEquals(NodeSegment.CONTACTS, vm.state.value.segment)
        assertFalse(vm.state.value.userSwitchedSegment)
        assertEquals("", vm.state.value.searchText)
        assertTrue(vm.state.value.contacts.isEmpty())

        vm.setSegment(NodeSegment.REPEATERS)
        advanceUntilIdle()

        assertEquals(0, serviceReads)
        assertEquals(0, radioReads)
        assertEquals(NodeSegment.REPEATERS, vm.state.value.segment)
        assertTrue(vm.state.value.userSwitchedSegment)
        assertEquals("", vm.state.value.searchText)
        assertTrue(vm.state.value.contacts.isEmpty())
        assertFalse(vm.state.value.hasLoadedOnce)
        assertFalse(vm.state.value.isLoading)
    }

    @Test
    fun favoritesAutoSelectedOnFirstLoadWhenFavoritesExist() = runTest {
        val vm = viewModel(testContactService(store = savedContactStore(chat(0x01, "Fav", isFavorite = true))))
        vm.load()
        assertEquals(NodeSegment.FAVORITES, vm.state.value.segment)
    }

    @Test
    fun favoritesNotAutoSelectedAfterUserSwitchesSegment() = runTest {
        val vm = viewModel(testContactService(store = savedContactStore(chat(0x01, "Fav", isFavorite = true))))
        vm.setSegment(NodeSegment.CONTACTS)
        vm.load()
        assertEquals(NodeSegment.CONTACTS, vm.state.value.segment)
    }

    @Test
    fun favoritesNotAutoSelectedWithoutFavorites() = runTest {
        val vm = viewModel(testContactService(store = savedContactStore(chat(0x01, "Plain"))))
        vm.load()
        assertEquals(NodeSegment.CONTACTS, vm.state.value.segment)
    }
}

/**
 * Favorite, block, delete, refresh, and sort actions on the nodes list.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactListActionsTest {
    @Test
    fun toggleFavoriteWritesDeviceFlagAndUpdatesState() = runTest {
        val session = FakeContactSession()
        val contact = chat(0x01, "Alice", isFavorite = false)
        val vm = viewModel(testContactService(session = session, store = savedContactStore(contact)))
        vm.load()

        vm.toggleFavorite(vm.state.value.contacts.first())

        assertEquals(1, session.changeFlagsCalls.size)
        assertTrue(vm.state.value.contacts.first().isFavorite)
        assertNull(vm.state.value.togglingFavoriteId)
    }

    @Test
    fun toggleFavoriteTransportFaultSurfacesAnErrorAndClearsTheSpinner() = runTest {
        val session = FakeContactSession().apply {
            changeFlagsThrowable = IllegalStateException("socket closed")
        }
        val contact = chat(0x01, "Alice", isFavorite = false)
        val vm = viewModel(testContactService(session = session, store = savedContactStore(contact)))
        vm.load()

        vm.toggleFavorite(vm.state.value.contacts.first())

        assertTrue(session.changeFlagsCalls.isEmpty())
        assertFalse(vm.state.value.contacts.first().isFavorite)
        assertNull(vm.state.value.togglingFavoriteId)
        assertIs<ContactListError.ServiceMessage>(vm.state.value.error)
    }

    @Test
    fun toggleFavoritePersistFaultSurfacesAnErrorAndClearsTheSpinner() = runTest {
        val contact = chat(0x01, "Alice", isFavorite = false)
        val backing = savedContactStore(contact)
        val store = object : ContactStore by backing {
            override suspend fun updateContactFields(id: Uuid, update: (ContactDto) -> ContactDto): ContactDto? {
                error("room down")
            }
        }
        val vm = viewModel(testContactService(store = store))
        vm.load()

        vm.toggleFavorite(vm.state.value.contacts.first())

        assertFalse(vm.state.value.contacts.first().isFavorite)
        assertNull(vm.state.value.togglingFavoriteId)
        assertIs<ContactListError.ServiceMessage>(vm.state.value.error)
    }

    @Test
    fun toggleBlockedUpdatesLocalPreference() = runTest {
        val contact = chat(0x01, "Alice", isBlocked = false)
        val vm = viewModel(testContactService(store = savedContactStore(contact)))
        vm.load()

        vm.toggleBlocked(vm.state.value.contacts.first())

        assertTrue(vm.state.value.contacts.first().isBlocked)
    }

    @Test
    fun deleteContactRemovesFromDeviceAndHidesRow() = runTest {
        val session = FakeContactSession()
        val contact = chat(0x01, "Alice")
        val vm = viewModel(testContactService(session = session, store = savedContactStore(contact)))
        vm.load()

        vm.deleteContact(vm.state.value.contacts.first())
        advanceUntilIdle()

        assertEquals(1, session.removedPublicKeys.size)
        assertTrue(contact.id in vm.state.value.pendingRemovalIds)
        assertTrue(vm.filteredContacts(vm.state.value).isEmpty())
        // The in-flight spinner set is cleared in the finally; the anti-resurrection mask persists.
        assertTrue(contact.id !in vm.state.value.deletingIds)
    }

    @Test
    fun deleteWhileDisconnectedSurfacesConnectError() = runTest {
        val vm = viewModel(service = null)
        vm.deleteContact(chat(0x01, "Ghost"))
        assertIs<ContactListError.ConnectToDelete>(vm.state.value.error)
    }

    @Test
    fun deleteWithOfflineServiceSurfacesConnectErrorAndDoesNotRemove() = runTest {
        val session = FakeContactSession()
        val contact = chat(0x01, "Alice")
        val vm = viewModel(
            testContactService(session = session, store = savedContactStore(contact)),
            ListOptions(isConnected = false),
        )
        vm.load()

        vm.deleteContact(vm.state.value.contacts.first())

        assertIs<ContactListError.ConnectToDelete>(vm.state.value.error)
        assertTrue(session.removedPublicKeys.isEmpty())
        assertTrue(contact.id !in vm.state.value.pendingRemovalIds)
    }

    @Test
    fun toggleFavoriteWhileDisconnectedDoesNotWriteDeviceFlag() = runTest {
        val session = FakeContactSession()
        val contact = chat(0x01, "Alice", isFavorite = false)
        val vm = viewModel(
            testContactService(session = session, store = savedContactStore(contact)),
            ListOptions(isConnected = false),
        )
        vm.load()

        vm.toggleFavorite(vm.state.value.contacts.first())

        assertTrue(session.changeFlagsCalls.isEmpty())
        assertFalse(vm.state.value.contacts.first().isFavorite)
        assertNull(vm.state.value.togglingFavoriteId)
        assertNull(vm.state.value.error)
    }

    @Test
    fun toggleBlockedWhileDisconnectedDoesNotPersist() = runTest {
        val contact = chat(0x01, "Alice", isBlocked = false)
        val mem = savedContactStore(contact)
        val vm = viewModel(testContactService(store = mem), ListOptions(isConnected = false))
        vm.load()

        vm.toggleBlocked(vm.state.value.contacts.first())

        assertFalse(vm.state.value.contacts.first().isBlocked)
        assertFalse(mem.fetchContact(contact.id)!!.isBlocked)
        assertNull(vm.state.value.error)
    }

    @Test
    fun deleteClearsOrphanedLocalRowWhenDeviceAlreadyForgotContact() = runTest {
        val session = FakeContactSession().apply { removeContactError = ContactProtocolErrorCode.NOT_FOUND }
        val contact = chat(0x01, "Alice")
        val vm = viewModel(testContactService(session = session, store = savedContactStore(contact)))
        vm.load()

        vm.deleteContact(vm.state.value.contacts.first())
        advanceUntilIdle()

        // The device rejected the remove with NOT_FOUND, so the local row is cleared instead, without an error.
        assertTrue(session.removedPublicKeys.isEmpty())
        assertTrue(contact.id in vm.state.value.pendingRemovalIds)
        assertNull(vm.state.value.error)
    }

    @Test
    fun refreshBlockedWhenRadioNotReady() = runTest {
        val vm = viewModel(
            testContactService(store = savedContactStore(chat(0x01, "Alice"))),
            ListOptions(isReady = false),
        )
        vm.refresh()
        assertIs<ContactListError.CannotRefresh>(vm.state.value.error)
    }

    @Test
    fun clearingAContactListErrorDoesNotRadio() = runTest {
        val session = FakeContactSession()
        var contactReads = 0
        session.onGetContacts = { contactReads += 1 }
        val vm = viewModel(
            testContactService(session = session, store = savedContactStore(chat(0x01, "Alice"))),
            ListOptions(isReady = false),
        )
        vm.refresh()
        advanceUntilIdle()
        assertIs<ContactListError.CannotRefresh>(vm.state.value.error)
        assertEquals(0, contactReads)
        val discoveries = session.pathDiscoveryDestinations.size
        val resets = session.resetPathPublicKeys.size
        val adds = session.addedContacts.size
        val removes = session.removedPublicKeys.size
        val shares = session.sharedPublicKeys.size
        val flags = session.changeFlagsCalls.size
        val lookups = session.getContactKeys.size

        vm.clearError()
        advanceUntilIdle()

        assertEquals(0, contactReads)
        assertEquals(discoveries, session.pathDiscoveryDestinations.size)
        assertEquals(resets, session.resetPathPublicKeys.size)
        assertEquals(adds, session.addedContacts.size)
        assertEquals(removes, session.removedPublicKeys.size)
        assertEquals(shares, session.sharedPublicKeys.size)
        assertEquals(flags, session.changeFlagsCalls.size)
        assertEquals(lookups, session.getContactKeys.size)
        assertNull(vm.state.value.error)
        assertFalse(vm.state.value.isSyncing)
    }

    @Test
    fun refreshSyncsFromDeviceAndReloads() = runTest {
        val session = FakeContactSession().apply {
            stubbedContacts = listOf(meshContact(0x01, "Synced"), meshContact(0x02, "AlsoSynced"))
        }
        val vm = viewModel(testContactService(session = session), ListOptions(isReady = true))

        vm.refresh()
        advanceUntilIdle()

        assertEquals(setOf("Synced", "AlsoSynced"), vm.state.value.contacts.mapTo(mutableSetOf()) { it.name })
        assertFalse(vm.state.value.isSyncing)
    }

    @Test
    fun setSortOrderPersistsToPreferenceStore() = runTest {
        val prefs = InMemoryPreferencesStore()
        val vm = viewModel(
            testContactService(store = savedContactStore(chat(0x01, "Alice"))),
            ListOptions(sortOrderStore = prefs),
        )

        vm.setSortOrder(NodeSortOrder.DISTANCE)
        runCurrent()

        assertEquals(NodeSortOrder.DISTANCE, vm.state.value.sortOrder)
        assertEquals(NodeSortOrder.DISTANCE.raw, prefs.snapshot()[NodeSortOrder.SORT_ORDER_PREFERENCE_KEY])
    }

    @Test
    fun keepingTheContactListSortDoesNotRadio() = runTest {
        val prefs = InMemoryPreferencesStore()
        var serviceReads = 0
        var readyReads = 0
        var connectedReads = 0
        val vm = ContactListViewModel(
            scope = backgroundScope,
            dependencies = ContactListViewModel.Dependencies(
                contactService = {
                    serviceReads += 1
                    error("contact service")
                },
                radioId = { error("radio") },
                isConnected = {
                    connectedReads += 1
                    false
                },
                isReady = {
                    readyReads += 1
                    false
                },
                sortOrderStore = prefs,
                errorText = { LOCALIZED_ERROR },
            ),
        )
        runCurrent()
        assertEquals(0, serviceReads)
        assertEquals(0, readyReads)
        assertEquals(0, connectedReads)
        assertEquals(NodeSortOrder.LAST_HEARD, vm.state.value.sortOrder)
        assertEquals(NodeSegment.CONTACTS, vm.state.value.segment)
        assertTrue(prefs.snapshot().isEmpty())

        vm.setSortOrder(NodeSortOrder.LAST_HEARD)
        runCurrent()

        assertEquals(0, serviceReads)
        assertEquals(0, readyReads)
        assertEquals(0, connectedReads)
        assertEquals(NodeSortOrder.LAST_HEARD, vm.state.value.sortOrder)
        assertEquals(NodeSegment.CONTACTS, vm.state.value.segment)
        assertFalse(vm.state.value.userSwitchedSegment)
        assertEquals(
            mapOf(NodeSortOrder.SORT_ORDER_PREFERENCE_KEY to NodeSortOrder.LAST_HEARD.raw),
            prefs.snapshot(),
        )
    }

    @Test
    fun choosingAContactListSortDoesNotRadio() = runTest {
        val prefs = InMemoryPreferencesStore()
        var serviceReads = 0
        var readyReads = 0
        var connectedReads = 0
        val vm = ContactListViewModel(
            scope = backgroundScope,
            dependencies = ContactListViewModel.Dependencies(
                contactService = {
                    serviceReads += 1
                    error("contact service")
                },
                radioId = { error("radio") },
                isConnected = {
                    connectedReads += 1
                    false
                },
                isReady = {
                    readyReads += 1
                    false
                },
                sortOrderStore = prefs,
                errorText = { LOCALIZED_ERROR },
            ),
        )
        runCurrent()
        assertEquals(0, serviceReads)
        assertEquals(0, readyReads)
        assertEquals(0, connectedReads)
        assertEquals(NodeSortOrder.LAST_HEARD, vm.state.value.sortOrder)
        assertTrue(prefs.snapshot().isEmpty())

        vm.setSortOrder(NodeSortOrder.NAME)
        runCurrent()

        val state = vm.state.value
        assertEquals(0, serviceReads)
        assertEquals(0, readyReads)
        assertEquals(0, connectedReads)
        assertEquals(NodeSortOrder.NAME, state.sortOrder)
        assertEquals(NodeSegment.CONTACTS, state.segment)
        assertEquals("", state.searchText)
        assertTrue(state.contacts.isEmpty())
        assertFalse(state.hasLoadedOnce)
        assertFalse(state.isSyncing)
        assertNull(state.error)
        assertFalse(state.userSwitchedSegment)
        assertEquals(
            mapOf(NodeSortOrder.SORT_ORDER_PREFERENCE_KEY to NodeSortOrder.NAME.raw),
            prefs.snapshot(),
        )
        assertFalse(NodeSortOrder.DISCOVERY_SORT_ORDER_PREFERENCE_KEY in prefs.snapshot())
    }

    @Test
    fun sortOrderRestoredFromPreferenceStoreOnConstruction() = runTest {
        // A relaunch reads the persisted order rather than resetting to Last Heard.
        val prefs = InMemoryPreferencesStore(
            mapOf(NodeSortOrder.SORT_ORDER_PREFERENCE_KEY to NodeSortOrder.HOPS.raw),
        )
        val vm = viewModel(
            testContactService(store = savedContactStore(chat(0x01, "Alice"))),
            ListOptions(sortOrderStore = prefs),
        )
        runCurrent()

        assertEquals(NodeSortOrder.HOPS, vm.state.value.sortOrder)
    }

    @Test
    fun emptyStateDerivedWhenSegmentHasNoMatches() = runTest {
        val vm = viewModel(testContactService(store = savedContactStore(chat(0x01, "Alice"))))
        vm.load()
        vm.setSegment(NodeSegment.REPEATERS)
        advanceUntilIdle()
        assertTrue(vm.filteredContacts(vm.state.value).isEmpty())
        assertTrue(vm.state.value.hasLoadedOnce)
        assertFalse(vm.state.value.isSearching)
    }
}

/**
 * Favorite, block, and delete faults on the nodes list. A radio that never acks a delete is a timeout,
 * which stays a result; cancellation and a thrown error leave the row and do not become that result.
 */
class ContactListRowFaultTest {
    @Test
    fun callersDeadlineDoesNotBecomeADeleteError() = runTest {
        val session = FakeContactSession().apply {
            onRemoveContact = { suspendCancellableCoroutine { } }
        }
        val contact = chat(0x01, "Alice")
        val vm = viewModel(testContactService(session = session, store = savedContactStore(contact)))
        vm.load()

        withTimeoutOrNull(100L) { vm.deleteContact(vm.state.value.contacts.first()) }

        assertNull(vm.state.value.error)
        assertEquals(listOf("Alice"), vm.state.value.contacts.map { it.name })
        assertTrue(contact.id !in vm.state.value.deletingIds)
    }

    @Test
    fun aDeleteThatNeverAcksIsATimeoutAndKeepsTheRow() = runTest {
        val session = FakeContactSession().apply {
            onRemoveContact = { suspendCancellableCoroutine { } }
        }
        val contact = chat(0x01, "Alice")
        val vm = viewModel(testContactService(session = session, store = savedContactStore(contact)))
        vm.load()

        vm.deleteContact(vm.state.value.contacts.first())

        assertIs<ContactListError.RemoveTimedOut>(vm.state.value.error)
        assertEquals(listOf("Alice"), vm.state.value.contacts.map { it.name })
        assertTrue(contact.id !in vm.state.value.deletingIds)
        assertTrue(contact.id !in vm.state.value.pendingRemovalIds)
        assertTrue(session.removedPublicKeys.isEmpty())
    }

    @Test
    fun aCancelledDeletePropagatesAndKeepsTheRow() = runTest {
        val session = FakeContactSession().apply {
            removeContactThrowable = CancellationException("superseded")
        }
        val contact = chat(0x01, "Alice")
        val vm = viewModel(testContactService(session = session, store = savedContactStore(contact)))
        vm.load()

        assertFailsWith<CancellationException> {
            vm.deleteContact(vm.state.value.contacts.first())
        }

        assertNull(vm.state.value.error)
        assertEquals(listOf("Alice"), vm.state.value.contacts.map { it.name })
        assertTrue(contact.id !in vm.state.value.deletingIds)
        assertTrue(contact.id !in vm.state.value.pendingRemovalIds)
    }

    @Test
    fun aDeleteErrorPropagatesAndKeepsTheRow() = runTest {
        val contact = chat(0x01, "Alice")
        val backing = savedContactStore(contact)
        val failing = object : ContactStore by backing {
            override suspend fun deleteContact(id: Uuid) = throw ContactListRowProbeError()
        }
        val vm = viewModel(testContactService(session = FakeContactSession(), store = failing))
        vm.load()

        assertFailsWith<ContactListRowProbeError> {
            vm.deleteContact(vm.state.value.contacts.first())
        }

        assertNull(vm.state.value.error)
        assertEquals(listOf("Alice"), vm.state.value.contacts.map { it.name })
        assertTrue(contact.id !in vm.state.value.deletingIds)
        assertTrue(contact.id !in vm.state.value.pendingRemovalIds)
    }

    @Test
    fun aDeleteFailureSurfacesAServiceMessageAndKeepsTheRow() = runTest {
        val session = FakeContactSession().apply {
            removeContactThrowable = IllegalStateException("radio down")
        }
        val contact = chat(0x01, "Alice")
        val vm = viewModel(testContactService(session = session, store = savedContactStore(contact)))
        vm.load()

        vm.deleteContact(vm.state.value.contacts.first())

        assertIs<ContactListError.ServiceMessage>(vm.state.value.error)
        assertEquals(listOf("Alice"), vm.state.value.contacts.map { it.name })
        assertTrue(contact.id !in vm.state.value.deletingIds)
    }

    @Test
    fun aCancelledFavoritePropagatesAndClearsTheSpinner() = runTest {
        val session = FakeContactSession().apply {
            changeFlagsThrowable = CancellationException("superseded")
        }
        val contact = chat(0x01, "Alice", isFavorite = false)
        val vm = viewModel(testContactService(session = session, store = savedContactStore(contact)))
        vm.load()

        assertFailsWith<CancellationException> {
            vm.toggleFavorite(vm.state.value.contacts.first())
        }

        assertNull(vm.state.value.togglingFavoriteId)
        assertNull(vm.state.value.error)
        assertFalse(vm.state.value.contacts.first().isFavorite)
    }

    @Test
    fun aFavoriteSaveErrorPropagatesAndClearsTheSpinner() = runTest {
        val contact = chat(0x01, "Alice", isFavorite = false)
        val backing = savedContactStore(contact)
        val failing = object : ContactStore by backing {
            override suspend fun updateContactFields(id: Uuid, update: (ContactDto) -> ContactDto): ContactDto? =
                backing.updateContactFields(id) { current ->
                    val dto = update(current)
                    if (dto.isFavorite) throw ContactListRowProbeError()
                    dto
                }
        }
        val vm = viewModel(testContactService(session = FakeContactSession(), store = failing))
        vm.load()

        assertFailsWith<ContactListRowProbeError> {
            vm.toggleFavorite(vm.state.value.contacts.first())
        }

        assertNull(vm.state.value.togglingFavoriteId)
        assertNull(vm.state.value.error)
        assertFalse(vm.state.value.contacts.first().isFavorite)
    }

    @Test
    fun aCancelledBlockPropagatesWithoutAnError() = runTest {
        val contact = chat(0x01, "Alice", isBlocked = false)
        val backing = savedContactStore(contact)
        val failing = object : ContactStore by backing {
            override suspend fun updateContactFields(id: Uuid, update: (ContactDto) -> ContactDto): ContactDto? =
                backing.updateContactFields(id) { current ->
                    val dto = update(current)
                    if (dto.isBlocked) throw CancellationException("superseded")
                    dto
                }
        }
        val vm = viewModel(testContactService(store = failing))
        vm.load()

        assertFailsWith<CancellationException> {
            vm.toggleBlocked(vm.state.value.contacts.first())
        }

        assertNull(vm.state.value.error)
        assertFalse(vm.state.value.contacts.first().isBlocked)
    }

    @Test
    fun aBlockSaveErrorPropagatesWithoutAnError() = runTest {
        val contact = chat(0x01, "Alice", isBlocked = false)
        val backing = savedContactStore(contact)
        val failing = object : ContactStore by backing {
            override suspend fun updateContactFields(id: Uuid, update: (ContactDto) -> ContactDto): ContactDto? =
                backing.updateContactFields(id) { current ->
                    val dto = update(current)
                    if (dto.isBlocked) throw ContactListRowProbeError()
                    dto
                }
        }
        val vm = viewModel(testContactService(store = failing))
        vm.load()

        assertFailsWith<ContactListRowProbeError> {
            vm.toggleBlocked(vm.state.value.contacts.first())
        }

        assertNull(vm.state.value.error)
        assertFalse(vm.state.value.contacts.first().isBlocked)
    }

    @Test
    fun anOrphanedDeleteFailureSurfacesAServiceMessageAndKeepsTheRow() = runTest {
        val session = FakeContactSession().apply { removeContactError = ContactProtocolErrorCode.NOT_FOUND }
        val contact = chat(0x01, "Alice")
        val backing = savedContactStore(contact)
        val failing = object : ContactStore by backing {
            override suspend fun deleteContact(id: Uuid) = error("db locked")
        }
        val vm = viewModel(testContactService(session = session, store = failing))
        vm.load()

        vm.deleteContact(vm.state.value.contacts.first())

        assertIs<ContactListError.ServiceMessage>(vm.state.value.error)
        assertEquals(listOf("Alice"), vm.state.value.contacts.map { it.name })
        assertTrue(contact.id !in vm.state.value.pendingRemovalIds)
        assertTrue(contact.id !in vm.state.value.deletingIds)
    }

    @Test
    fun anOrphanedDeleteErrorPropagatesAndKeepsTheRow() = runTest {
        val session = FakeContactSession().apply { removeContactError = ContactProtocolErrorCode.NOT_FOUND }
        val contact = chat(0x01, "Alice")
        val backing = savedContactStore(contact)
        val failing = object : ContactStore by backing {
            override suspend fun deleteContact(id: Uuid) = throw ContactListRowProbeError()
        }
        val vm = viewModel(testContactService(session = session, store = failing))
        vm.load()

        assertFailsWith<ContactListRowProbeError> {
            vm.deleteContact(vm.state.value.contacts.first())
        }

        assertNull(vm.state.value.error)
        assertEquals(listOf("Alice"), vm.state.value.contacts.map { it.name })
        assertTrue(contact.id !in vm.state.value.deletingIds)
    }
}

private val radio = ContactTestIds.RADIO

private fun chat(keyByte: Int, name: String, isFavorite: Boolean = false, isBlocked: Boolean = false) = contactDto(
    id = ContactTestIds.fresh(),
    radioID = radio,
    keyByte = keyByte,
    name = name,
    typeRawValue = ContactType.CHAT.raw,
    flags = if (isFavorite) 0x01 else 0x00,
    isFavorite = isFavorite,
    isBlocked = isBlocked,
)

private data class ListOptions(
    val isReady: Boolean = true,
    val isConnected: Boolean? = null,
    val inboundHops: Map<String, Int> = emptyMap(),
    val inboundHopsBlock: (suspend (Uuid) -> Map<String, Int>)? = null,
    val radioIdOverride: (() -> Uuid?)? = null,
    val sortOrderStore: InMemoryPreferencesStore? = null,
    val listInvalidations: Flow<Unit> = emptyFlow(),
    val errorText: (Throwable) -> String = { LOCALIZED_ERROR },
)

private fun TestScope.viewModel(
    service: ContactService?,
    options: ListOptions = ListOptions(),
    scope: CoroutineScope = backgroundScope,
): ContactListViewModel {
    val connected = options.isConnected ?: (service != null)
    return ContactListViewModel(
        scope = scope,
        dependencies = ContactListViewModel.Dependencies(
            contactService = { service },
            radioId = { options.radioIdOverride?.invoke() ?: if (service == null) null else radio },
            isConnected = { connected },
            isReady = { options.isReady },
            inboundHops = options.inboundHopsBlock ?: { options.inboundHops },
            sortOrderStore = options.sortOrderStore,
            listInvalidations = options.listInvalidations,
            errorText = options.errorText,
        ),
    )
}

private class ContactListRowProbeError : Error("probe")

private class ContactListLoadProbeError : Error("probe")

private const val LOCALIZED_ERROR = "Localized error"

/** Load, refresh, and invalidation faults for the nodes list. */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactListLoadFaultTest {
    @Test
    fun aContactReadErrorPropagatesAndLeavesTheSpinner() = runTest {
        val store = object : ContactStore by InMemoryContactStore() {
            override suspend fun fetchContacts(radioID: Uuid): Nothing = throw ContactListLoadProbeError()
        }
        val vm = viewModel(testContactService(store = store))

        assertFailsWith<ContactListLoadProbeError> { vm.load() }

        assertNull(vm.state.value.error)
        assertTrue(vm.state.value.isLoading)
        assertFalse(vm.state.value.hasLoadedOnce)
        assertTrue(vm.state.value.contacts.isEmpty())
    }

    @Test
    fun aCancelledContactReadPropagatesAndLeavesTheSpinner() = runTest {
        val store = object : ContactStore by InMemoryContactStore() {
            override suspend fun fetchContacts(radioID: Uuid): Nothing = throw CancellationException("superseded")
        }
        val vm = viewModel(testContactService(store = store))

        val cancelled = assertFailsWith<CancellationException> { vm.load() }

        assertEquals("superseded", cancelled.message)
        assertNull(vm.state.value.error)
        assertTrue(vm.state.value.isLoading)
        assertFalse(vm.state.value.hasLoadedOnce)
    }

    @Test
    fun aHopReadExceptionStillShowsTheContacts() = runTest {
        val vm = viewModel(
            testContactService(store = savedContactStore(chat(0x01, "Alice"))),
            ListOptions(inboundHopsBlock = { error("hops") }),
        )

        vm.load()

        assertEquals(listOf("Alice"), vm.state.value.contacts.map { it.name })
        assertTrue(vm.state.value.inboundHopByKey.isEmpty())
        assertNull(vm.state.value.error)
        assertFalse(vm.state.value.isLoading)
        assertTrue(vm.state.value.hasLoadedOnce)
    }

    @Test
    fun aHopReadErrorPropagatesAndLeavesTheSpinner() = runTest {
        val vm = viewModel(
            testContactService(store = savedContactStore(chat(0x01, "Alice"))),
            ListOptions(inboundHopsBlock = { throw ContactListLoadProbeError() }),
        )

        assertFailsWith<ContactListLoadProbeError> { vm.load() }

        assertNull(vm.state.value.error)
        assertTrue(vm.state.value.isLoading)
        assertTrue(vm.state.value.contacts.isEmpty())
        assertFalse(vm.state.value.hasLoadedOnce)
    }

    @Test
    fun aCancelledHopReadPropagatesAndLeavesTheSpinner() = runTest {
        val vm = viewModel(
            testContactService(store = savedContactStore(chat(0x01, "Alice"))),
            ListOptions(inboundHopsBlock = { throw CancellationException("superseded") }),
        )

        val cancelled = assertFailsWith<CancellationException> { vm.load() }

        assertEquals("superseded", cancelled.message)
        assertNull(vm.state.value.error)
        assertTrue(vm.state.value.isLoading)
        assertTrue(vm.state.value.contacts.isEmpty())
    }

    @Test
    fun aRefreshExceptionReportsAServiceMessageAndClearsTheSpinner() = runTest {
        val session = FakeContactSession().apply { onGetContacts = { error("radio down") } }
        val vm = viewModel(testContactService(session = session), ListOptions(isReady = true))

        vm.refresh()

        assertIs<ContactListError.ServiceMessage>(vm.state.value.error)
        assertFalse(vm.state.value.isSyncing)
    }

    @Test
    fun aRefreshErrorPropagatesAndClearsTheSpinner() = runTest {
        val store = object : ContactStore by InMemoryContactStore() {
            override suspend fun fetchContacts(radioID: Uuid): Nothing = throw ContactListLoadProbeError()
        }
        val vm = viewModel(testContactService(store = store), ListOptions(isReady = true))

        assertFailsWith<ContactListLoadProbeError> { vm.refresh() }

        assertNull(vm.state.value.error)
        assertFalse(vm.state.value.isSyncing)
    }

    @Test
    fun aCancelledRefreshPropagatesAndClearsTheSpinner() = runTest {
        val session = FakeContactSession().apply {
            onGetContacts = { throw CancellationException("superseded") }
        }
        val vm = viewModel(testContactService(session = session), ListOptions(isReady = true))

        val cancelled = assertFailsWith<CancellationException> { vm.refresh() }

        assertEquals("superseded", cancelled.message)
        assertNull(vm.state.value.error)
        assertFalse(vm.state.value.isSyncing)
    }

    @Test
    fun aContactReadExceptionStillReloadsTheNextInvalidation() = runTest(UnconfinedTestDispatcher()) {
        val backing = InMemoryContactStore().also { it.saveContact(chat(0x01, "Alice")) }
        var fetches = 0
        val store = object : ContactStore by backing {
            override suspend fun fetchContacts(radioID: Uuid): List<ContactDto> {
                fetches += 1
                if (fetches == 2) error("db locked")
                return backing.fetchContacts(radioID)
            }
        }
        val invalidations = MutableSharedFlow<Unit>(extraBufferCapacity = 2)
        val vm = viewModel(
            testContactService(store = store),
            ListOptions(listInvalidations = invalidations),
        )
        vm.load()
        vm.bindListInvalidations()
        backing.saveContact(chat(0x02, "Bob"))

        invalidations.emit(Unit)
        invalidations.emit(Unit)

        assertEquals(3, fetches)
        assertNull(vm.state.value.error)
        assertEquals(listOf("Alice", "Bob"), vm.state.value.contacts.map { it.name }.sorted())
        assertFalse(vm.state.value.isLoading)
    }

    @Test
    fun anInvalidationExceptionStillReloadsTheNextChange() = runTest(UnconfinedTestDispatcher()) {
        val mem = InMemoryContactStore().also { it.saveContact(chat(0x01, "Alice")) }
        val invalidations = MutableSharedFlow<Unit>(extraBufferCapacity = 2)
        var reads = 0
        val vm = viewModel(
            testContactService(store = mem),
            ListOptions(
                listInvalidations = invalidations,
                radioIdOverride = {
                    reads += 1
                    if (reads == 2) error("room")
                    radio
                },
            ),
        )
        vm.load()
        vm.bindListInvalidations()
        mem.saveContact(chat(0x02, "Bob"))

        invalidations.emit(Unit)
        invalidations.emit(Unit)

        assertEquals(3, reads)
        assertEquals(listOf("Alice", "Bob"), vm.state.value.contacts.map { it.name }.sorted())
        assertNull(vm.state.value.error)
    }

    @Test
    fun aCancelledInvalidationStopsLaterReloads() = runTest(UnconfinedTestDispatcher()) {
        val mem = InMemoryContactStore().also { it.saveContact(chat(0x01, "Alice")) }
        val invalidations = MutableSharedFlow<Unit>(extraBufferCapacity = 2)
        var reads = 0
        val vm = viewModel(
            testContactService(store = mem),
            ListOptions(
                listInvalidations = invalidations,
                radioIdOverride = {
                    reads += 1
                    if (reads == 2) throw CancellationException("superseded")
                    radio
                },
            ),
        )
        vm.load()
        vm.bindListInvalidations()
        mem.saveContact(chat(0x02, "Bob"))

        invalidations.emit(Unit)
        invalidations.emit(Unit)

        assertEquals(2, reads)
        assertEquals(listOf("Alice"), vm.state.value.contacts.map { it.name })
    }

    @Test
    fun anInvalidationErrorStopsLaterReloads() = runTest(UnconfinedTestDispatcher()) {
        val errors = mutableListOf<Throwable>()
        val scope = CoroutineScope(
            SupervisorJob() + UnconfinedTestDispatcher(testScheduler) +
                CoroutineExceptionHandler { _, error -> errors += error },
        )
        val mem = InMemoryContactStore().also { it.saveContact(chat(0x01, "Alice")) }
        val invalidations = MutableSharedFlow<Unit>(extraBufferCapacity = 2)
        var reads = 0
        val vm = viewModel(
            testContactService(store = mem),
            ListOptions(
                listInvalidations = invalidations,
                radioIdOverride = {
                    reads += 1
                    if (reads == 2) throw ContactListLoadProbeError()
                    radio
                },
            ),
            scope = scope,
        )
        vm.load()
        vm.bindListInvalidations()
        mem.saveContact(chat(0x02, "Bob"))

        invalidations.emit(Unit)
        invalidations.emit(Unit)

        assertTrue(errors.single() is ContactListLoadProbeError)
        assertEquals(2, reads)
        assertEquals(listOf("Alice"), vm.state.value.contacts.map { it.name })
        assertNull(vm.state.value.error)
        scope.cancel()
    }
}
