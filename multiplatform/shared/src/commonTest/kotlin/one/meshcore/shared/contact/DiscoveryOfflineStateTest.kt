package one.meshcore.shared.contact

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Discover stays reachable while disconnected, so it has to render something coherent with no radio
 * partition to read and with a segment filter that hides every row. Covers the disconnected load and the
 * pure [discoveryListPhase] branch the screen renders from.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DiscoveryOfflineStateTest {
    private val radio = ContactTestIds.RADIO

    private fun TestScope.viewModel(store: DiscoveredNodeStore?, radioId: Uuid?) = DiscoveryViewModel(
        scope = backgroundScope,
        dependencies = DiscoveryViewModel.Dependencies(
            store = { store },
            contactService = { null },
            radioId = { radioId },
            loadFailedMessage = { DISCOVERY_LOAD_FAILED_MESSAGE },
            errorText = { DISCOVERY_LOAD_FAILED_MESSAGE },
        ),
    )

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

    /**
     * With no live radio the screen would otherwise sit at `hasLoadedOnce == false` forever, which the
     * phase branch renders as a blank scroll area rather than the localized empty-state guidance.
     */
    @Test
    fun loadingWithoutARadioMarksTheListLoaded() = runTest(UnconfinedTestDispatcher()) {
        val vm = viewModel(store = InMemoryDiscoveredNodeStore(), radioId = null)

        vm.load()

        assertTrue(vm.state.value.hasLoadedOnce, "disconnected load must settle the list")
        assertEquals(emptyList(), vm.state.value.nodes)
        assertEquals(DiscoveryListPhase.EMPTY, discoveryListPhase(vm.state.value, filteredCount = 0))
    }

    /** Rows loaded while connected must not linger as browsable-but-stale after the radio goes away. */
    @Test
    fun loadingWithoutARadioClearsPreviouslyLoadedRows() = runTest(UnconfinedTestDispatcher()) {
        val store = InMemoryDiscoveredNodeStore()
        store.addNode(0x01, "Alpha")
        var currentRadio: Uuid? = radio
        val vm = DiscoveryViewModel(
            scope = backgroundScope,
            dependencies = DiscoveryViewModel.Dependencies(
                store = { store },
                contactService = { null },
                radioId = { currentRadio },
                loadFailedMessage = { DISCOVERY_LOAD_FAILED_MESSAGE },
                errorText = { DISCOVERY_LOAD_FAILED_MESSAGE },
            ),
        )
        vm.load()
        assertEquals(1, vm.state.value.nodes.size)

        currentRadio = null
        vm.load()

        assertTrue(vm.state.value.hasLoadedOnce)
        assertEquals(emptyList(), vm.state.value.nodes)
    }

    @Test
    fun anUnloadedListRendersTheLoadingBlock() {
        assertEquals(
            DiscoveryListPhase.LOADING,
            discoveryListPhase(DiscoveryState(hasLoadedOnce = false), filteredCount = 0),
        )
    }

    /**
     * A segment filter that hides every row is not a search miss: the search-empty copy interpolates the
     * query and would read "no results for ''" with an empty one.
     */
    @Test
    fun aSegmentFilterHidingEveryRowUsesThePlainEmptyState() {
        val state = DiscoveryState(
            nodes = listOf(record(0x01, "Alpha")),
            hasLoadedOnce = true,
            searchText = "",
        )

        assertEquals(DiscoveryListPhase.EMPTY, discoveryListPhase(state, filteredCount = 0))
    }

    @Test
    fun aSearchMissUsesTheSearchEmptyState() {
        val state = DiscoveryState(
            nodes = listOf(record(0x01, "Alpha")),
            hasLoadedOnce = true,
            searchText = "zulu",
        )

        assertEquals(DiscoveryListPhase.SEARCH_EMPTY, discoveryListPhase(state, filteredCount = 0))
    }

    @Test
    fun visibleRowsRenderTheList() {
        val state = DiscoveryState(nodes = listOf(record(0x01, "Alpha")), hasLoadedOnce = true)

        assertEquals(DiscoveryListPhase.ROWS, discoveryListPhase(state, filteredCount = 1))
    }

    private fun record(keyByte: Int, name: String) = DiscoveredNodeRecord(
        id = newDiscoveredNodeId(),
        radioID = radio,
        publicKey = publicKey(keyByte),
        name = name,
        typeRawValue = ContactType.CHAT.raw,
        lastHeardEpochSeconds = 1,
    )
}
