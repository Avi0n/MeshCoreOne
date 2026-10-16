package one.meshcore.shared.contact

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import one.meshcore.shared.prefs.PreferencesStore

/** Owns Discover's persisted sort choice, query edits, and visible-list derivation. */
class DiscoveryQueryEditor internal constructor(
    private val scope: CoroutineScope,
    private val userLocation: () -> UserLocation?,
    private val sortOrderStore: PreferencesStore?,
) : DiscoveryQueryControls {
    val state = MutableStateFlow(DiscoveryState())

    init {
        sortOrderStore?.let { store ->
            scope.launch {
                store.getString(NodeSortOrder.DISCOVERY_SORT_ORDER_PREFERENCE_KEY)?.let { raw ->
                    state.update { it.copy(sortOrder = NodeSortOrder.fromRaw(raw)) }
                }
            }
        }
    }

    override fun filteredNodes(state: DiscoveryState): List<DiscoveredNodeRecord> {
        val filtered = filterDiscoveredNodes(state.nodes, state.segment, state.searchText)
        return sortDiscoveredNodes(filtered, state.sortOrder, userLocation())
    }

    override fun setSortOrder(order: NodeSortOrder) {
        state.update { it.copy(sortOrder = order) }
        sortOrderStore?.let { store ->
            scope.launch { store.putString(NodeSortOrder.DISCOVERY_SORT_ORDER_PREFERENCE_KEY, order.raw) }
        }
    }

    override fun setSegment(segment: DiscoverSegment) {
        state.update { it.copy(segment = segment) }
    }

    override fun setSearchText(text: String) {
        state.update { it.copy(searchText = text) }
    }
}
