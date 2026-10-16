package one.meshcore.shared.contact

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Discover's persisted sort order and its reload on contact-table changes, mirroring iOS `DiscoveryView`
 * (`@AppStorage(discoverySortOrder)` plus the `contactsVersion` reload). Distance sort is covered by
 * [DiscoveryViewModelLocationTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DiscoveryViewModelStateTest {
    private val radio = ContactTestIds.RADIO

    private fun TestScope.viewModel(
        store: DiscoveredNodeStore? = null,
        contactService: ContactService? = null,
        sortOrderStore: InMemoryPreferencesStore? = null,
        listInvalidations: Flow<Unit> = emptyFlow(),
        loadFailedMessage: String = DISCOVERY_LOAD_FAILED_MESSAGE,
    ) = DiscoveryViewModel(
        scope = backgroundScope,
        dependencies = DiscoveryViewModel.Dependencies(
            store = { store },
            contactService = { contactService },
            radioId = { radio },
            sortOrderStore = sortOrderStore,
            listInvalidations = listInvalidations,
            loadFailedMessage = { loadFailedMessage },
            errorText = { DISCOVERY_LOAD_FAILED_MESSAGE },
        ),
    )

    /** Discover with no store and no contact service, so an edit can prove it did not read them. */
    private fun TestScope.quietDiscovery(
        radioId: (() -> Uuid?)? = null,
        sortOrderStore: InMemoryPreferencesStore? = null,
    ): QuietDiscovery {
        val reads = QuietDiscoveryReads()
        val vm = DiscoveryViewModel(
            scope = backgroundScope,
            dependencies = DiscoveryViewModel.Dependencies(
                store = {
                    reads.storeReads += 1
                    null
                },
                contactService = {
                    reads.serviceReads += 1
                    null
                },
                radioId = radioId ?: {
                    reads.radioReads += 1
                    null
                },
                sortOrderStore = sortOrderStore,
                loadFailedMessage = { DISCOVERY_LOAD_FAILED_MESSAGE },
                errorText = { DISCOVERY_LOAD_FAILED_MESSAGE },
            ),
        )
        return QuietDiscovery(vm, reads)
    }

    private suspend fun DiscoveredNodeStore.addNode(keyByte: Int, name: String) = upsert(
        DiscoveredNodeRecord(
            id = newDiscoveredNodeId(),
            radioID = radio,
            publicKey = publicKey(keyByte),
            name = name,
            typeRawValue = ContactType.CHAT.raw,
            lastHeardEpochSeconds = 1,
        ),
    )

    @Test
    fun setSortOrderPersistsUnderDiscoversOwnKey() = runTest {
        val prefs = InMemoryPreferencesStore()
        val vm = viewModel(sortOrderStore = prefs)

        vm.setSortOrder(NodeSortOrder.HOPS)
        runCurrent()

        assertEquals(NodeSortOrder.HOPS, vm.state.value.sortOrder)
        assertEquals(NodeSortOrder.HOPS.raw, prefs.snapshot()[NodeSortOrder.DISCOVERY_SORT_ORDER_PREFERENCE_KEY])
        // The Nodes list keeps its own order; the two screens must not share one key.
        assertFalse(NodeSortOrder.SORT_ORDER_PREFERENCE_KEY in prefs.snapshot())
    }

    @Test
    fun choosingADiscoverSortOrderDoesNotRadio() = runTest {
        val prefs = InMemoryPreferencesStore()
        val quiet = quietDiscovery(radioId = { radio }, sortOrderStore = prefs)
        val vm = quiet.vm
        assertEquals(NodeSortOrder.LAST_HEARD, vm.state.value.sortOrder)
        assertEquals(DiscoverSegment.ALL, vm.state.value.segment)
        assertEquals("", vm.state.value.searchText)

        vm.setSortOrder(NodeSortOrder.HOPS)
        runCurrent()

        assertEquals(0, quiet.reads.serviceReads)
        assertEquals(0, quiet.reads.storeReads)
        assertEquals(NodeSortOrder.HOPS, vm.state.value.sortOrder)
        assertEquals(DiscoverSegment.ALL, vm.state.value.segment)
        assertEquals("", vm.state.value.searchText)
        assertFalse(vm.state.value.isLoading)
        assertFalse(vm.state.value.hasLoadedOnce)
        assertTrue(vm.state.value.nodes.isEmpty())
        assertEquals(
            mapOf(NodeSortOrder.DISCOVERY_SORT_ORDER_PREFERENCE_KEY to NodeSortOrder.HOPS.raw),
            prefs.snapshot(),
        )
    }

    @Test
    fun keepingTheDiscoverAllSegmentDoesNotRadio() = runTest {
        val quiet = quietDiscovery()
        val vm = quiet.vm
        assertEquals(DiscoverSegment.ALL, vm.state.value.segment)
        assertEquals("", vm.state.value.searchText)
        assertEquals(NodeSortOrder.LAST_HEARD, vm.state.value.sortOrder)
        assertFalse(vm.state.value.isLoading)
        assertFalse(vm.state.value.hasLoadedOnce)
        assertTrue(vm.state.value.nodes.isEmpty())
        assertNull(vm.state.value.errorMessage)
        assertEquals(0, quiet.reads.serviceReads)
        assertEquals(0, quiet.reads.storeReads)
        assertEquals(0, quiet.reads.radioReads)

        vm.setSegment(DiscoverSegment.ALL)
        runCurrent()

        assertEquals(0, quiet.reads.serviceReads)
        assertEquals(0, quiet.reads.storeReads)
        assertEquals(0, quiet.reads.radioReads)
        assertEquals(DiscoverSegment.ALL, vm.state.value.segment)
        assertEquals("", vm.state.value.searchText)
        assertEquals(NodeSortOrder.LAST_HEARD, vm.state.value.sortOrder)
        assertFalse(vm.state.value.isLoading)
        assertFalse(vm.state.value.hasLoadedOnce)
        assertTrue(vm.state.value.nodes.isEmpty())
        assertNull(vm.state.value.errorMessage)
    }

    @Test
    fun choosingADiscoverSegmentDoesNotRadio() = runTest {
        val quiet = quietDiscovery(radioId = { radio })
        val vm = quiet.vm
        assertEquals(DiscoverSegment.ALL, vm.state.value.segment)

        vm.setSegment(DiscoverSegment.REPEATERS)
        runCurrent()

        assertEquals(0, quiet.reads.serviceReads)
        assertEquals(0, quiet.reads.storeReads)
        assertEquals(DiscoverSegment.REPEATERS, vm.state.value.segment)
        assertEquals("", vm.state.value.searchText)
        assertFalse(vm.state.value.isLoading)
        assertTrue(vm.state.value.nodes.isEmpty())
    }

    @Test
    fun keepingAnEmptyDiscoverSearchDoesNotRadio() = runTest {
        val quiet = quietDiscovery()
        val vm = quiet.vm
        assertEquals("", vm.state.value.searchText)
        assertEquals(DiscoverSegment.ALL, vm.state.value.segment)
        assertEquals(NodeSortOrder.LAST_HEARD, vm.state.value.sortOrder)
        assertFalse(vm.state.value.isLoading)
        assertFalse(vm.state.value.hasLoadedOnce)
        assertTrue(vm.state.value.nodes.isEmpty())
        assertNull(vm.state.value.errorMessage)
        assertEquals(0, quiet.reads.serviceReads)
        assertEquals(0, quiet.reads.storeReads)
        assertEquals(0, quiet.reads.radioReads)

        vm.setSearchText("")
        runCurrent()

        assertEquals(0, quiet.reads.serviceReads)
        assertEquals(0, quiet.reads.storeReads)
        assertEquals(0, quiet.reads.radioReads)
        assertEquals("", vm.state.value.searchText)
        assertEquals(DiscoverSegment.ALL, vm.state.value.segment)
        assertEquals(NodeSortOrder.LAST_HEARD, vm.state.value.sortOrder)
        assertFalse(vm.state.value.isLoading)
        assertFalse(vm.state.value.hasLoadedOnce)
        assertTrue(vm.state.value.nodes.isEmpty())
        assertNull(vm.state.value.errorMessage)
    }

    @Test
    fun searchingDiscoverNodesDoesNotRadio() = runTest {
        val quiet = quietDiscovery(radioId = { radio })
        val vm = quiet.vm
        assertEquals("", vm.state.value.searchText)
        assertEquals(DiscoverSegment.ALL, vm.state.value.segment)

        vm.setSearchText("pond")
        runCurrent()

        assertEquals(0, quiet.reads.serviceReads)
        assertEquals(0, quiet.reads.storeReads)
        assertEquals("pond", vm.state.value.searchText)
        assertEquals(DiscoverSegment.ALL, vm.state.value.segment)
        assertFalse(vm.state.value.isLoading)
        assertTrue(vm.state.value.nodes.isEmpty())
    }

    @Test
    fun clearingAnEmptyDiscoverErrorDoesNotRadio() = runTest {
        val quiet = quietDiscovery()
        val vm = quiet.vm
        assertNull(vm.state.value.errorMessage)
        assertNull(vm.state.value.addError)
        assertEquals("", vm.state.value.searchText)
        assertEquals(DiscoverSegment.ALL, vm.state.value.segment)
        assertEquals(NodeSortOrder.LAST_HEARD, vm.state.value.sortOrder)
        assertFalse(vm.state.value.hasLoadedOnce)
        assertEquals(0, quiet.reads.serviceReads)
        assertEquals(0, quiet.reads.storeReads)
        assertEquals(0, quiet.reads.radioReads)

        vm.clearError()
        runCurrent()

        assertEquals(0, quiet.reads.serviceReads)
        assertEquals(0, quiet.reads.storeReads)
        assertEquals(0, quiet.reads.radioReads)
        assertNull(vm.state.value.errorMessage)
        assertNull(vm.state.value.addError)
        assertEquals("", vm.state.value.searchText)
        assertEquals(DiscoverSegment.ALL, vm.state.value.segment)
        assertEquals(NodeSortOrder.LAST_HEARD, vm.state.value.sortOrder)
        assertFalse(vm.state.value.hasLoadedOnce)
        assertFalse(vm.state.value.isLoading)
        assertTrue(vm.state.value.nodes.isEmpty())
    }

    @Test
    fun sortOrderRestoredFromPreferenceStoreOnConstruction() = runTest {
        val prefs = InMemoryPreferencesStore(
            mapOf(NodeSortOrder.DISCOVERY_SORT_ORDER_PREFERENCE_KEY to NodeSortOrder.NAME.raw),
        )
        val vm = viewModel(sortOrderStore = prefs)
        runCurrent()

        assertEquals(NodeSortOrder.NAME, vm.state.value.sortOrder)
    }

    /** A Nodes-list order left in the store must not leak into Discover, which defaults to Last Heard. */
    @Test
    fun theNodesListSortOrderDoesNotRestoreIntoDiscover() = runTest {
        val prefs = InMemoryPreferencesStore(
            mapOf(NodeSortOrder.SORT_ORDER_PREFERENCE_KEY to NodeSortOrder.NAME.raw),
        )
        val vm = viewModel(sortOrderStore = prefs)
        runCurrent()

        assertEquals(NodeSortOrder.LAST_HEARD, vm.state.value.sortOrder)
    }

    /**
     * An add from another surface leaves stale "Add" buttons unless the contact-table invalidation
     * refreshes the added-key set; the store snapshot alone never carries it.
     */
    @Test
    fun listInvalidationsRefreshTheAddedKeySet() = runTest(UnconfinedTestDispatcher()) {
        val nodes = InMemoryDiscoveredNodeStore()
        nodes.addNode(0x01, "Alpha")
        val contacts = InMemoryContactStore()
        val service = testContactService(store = contacts)
        val invalidations = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val vm = viewModel(store = nodes, contactService = service, listInvalidations = invalidations)
        vm.load()
        val node = vm.state.value.nodes.single()
        assertFalse(vm.state.value.isAdded(node))

        vm.bindListInvalidations()
        contacts.saveContact(contactDto(id = ContactTestIds.fresh(), radioID = radio, keyByte = 0x01))
        assertFalse(vm.state.value.isAdded(node))

        invalidations.emit(Unit)

        assertTrue(vm.state.value.isAdded(node))
    }

    @Test
    fun listInvalidationsMoveToTheReplacementFlow() = runTest(UnconfinedTestDispatcher()) {
        val nodes = InMemoryDiscoveredNodeStore().also { it.addNode(0x01, "Alpha") }
        val original = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val replacement = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val vm = viewModel(store = nodes, listInvalidations = original)
        vm.load()
        vm.bindListInvalidations()
        vm.bindListInvalidations(replacement)
        nodes.addNode(0x02, "Bravo")

        original.emit(Unit)
        assertEquals(listOf("Alpha"), vm.state.value.nodes.map { it.name })

        replacement.emit(Unit)
        assertEquals(listOf("Alpha", "Bravo"), vm.state.value.nodes.map { it.name }.sorted())
    }

    /** A node removed elsewhere disappears from the rows on the same invalidation. */
    @Test
    fun listInvalidationsRefreshTheNodeRows() = runTest(UnconfinedTestDispatcher()) {
        val nodes = InMemoryDiscoveredNodeStore()
        nodes.addNode(0x01, "Alpha")
        val invalidations = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val vm = viewModel(store = nodes, listInvalidations = invalidations)
        vm.load()
        vm.bindListInvalidations()
        assertEquals(1, vm.state.value.nodes.size)

        nodes.addNode(0x02, "Bravo")
        invalidations.emit(Unit)

        assertEquals(2, vm.state.value.nodes.size)
    }

    @Test
    fun closeStopsStoreAndInvalidationCollectors() = runTest(UnconfinedTestDispatcher()) {
        val nodes = InMemoryDiscoveredNodeStore()
        nodes.addNode(0x01, "Alpha")
        val invalidations = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val vm = viewModel(store = nodes, listInvalidations = invalidations)
        vm.load()
        vm.startObserving()
        vm.bindListInvalidations()
        assertEquals(1, vm.state.value.nodes.size)

        vm.close()
        nodes.addNode(0x02, "Bravo")
        invalidations.emit(Unit)

        assertEquals(1, vm.state.value.nodes.size)
    }

    @Test
    fun persistFaultOnClearKeepsRowsAndSetsError() = runTest {
        val backing = InMemoryDiscoveredNodeStore()
        backing.addNode(0x01, "Alpha")
        val store = object : DiscoveredNodeStore by backing {
            override suspend fun clear(radioID: Uuid) {
                error("room down")
            }
        }
        val vm = viewModel(store = store)
        vm.load()
        assertEquals(1, vm.state.value.nodes.size)

        vm.clearAll()

        assertEquals(1, vm.state.value.nodes.size)
        assertEquals(DISCOVERY_LOAD_FAILED_MESSAGE, vm.state.value.errorMessage)
    }

    @Test
    fun persistFaultOnDeleteKeepsRowAndSetsError() = runTest {
        val backing = InMemoryDiscoveredNodeStore()
        backing.addNode(0x01, "Alpha")
        val store = object : DiscoveredNodeStore by backing {
            override suspend fun remove(radioID: Uuid, publicKey: ByteArray) {
                error("room down")
            }
        }
        val vm = viewModel(store = store)
        vm.load()
        val node = vm.state.value.nodes.single()

        vm.deleteNode(node)

        assertEquals(1, vm.state.value.nodes.size)
        assertEquals(DISCOVERY_LOAD_FAILED_MESSAGE, vm.state.value.errorMessage)
    }

    @Test
    fun aLoadFailureShowsFallbackNotEnglish() = runTest {
        val store = object : DiscoveredNodeStore by InMemoryDiscoveredNodeStore() {
            override suspend fun list(radioID: Uuid) = error("radio down")
        }
        val vm = viewModel(store = store, loadFailedMessage = "load fail")
        vm.load()
        assertEquals("load fail", vm.state.value.errorMessage)
        assertTrue(vm.state.value.hasLoadedOnce)
    }
}

private class QuietDiscovery(val vm: DiscoveryViewModel, val reads: QuietDiscoveryReads)

private class QuietDiscoveryReads {
    var serviceReads = 0
    var storeReads = 0
    var radioReads = 0
}
