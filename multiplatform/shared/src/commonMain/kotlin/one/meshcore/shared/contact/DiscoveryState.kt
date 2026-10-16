package one.meshcore.shared.contact

import one.meshcore.shared.model.Uuid
import one.meshcore.shared.model.toUppercaseHex

data class DiscoveryState(
    val nodes: List<DiscoveredNodeRecord> = emptyList(),
    val addedPublicKeyHexes: Set<String> = emptySet(),
    val segment: DiscoverSegment = DiscoverSegment.ALL,
    val searchText: String = "",
    val sortOrder: NodeSortOrder = NodeSortOrder.LAST_HEARD,
    val isLoading: Boolean = false,
    val hasLoadedOnce: Boolean = false,
    val errorMessage: String? = null,
    /**
     * The typed failure from adding a node to the device table. Typed rather than a message string so the
     * full-table case reaches the localized "node list is full (max N)" copy with the device cap, instead
     * of the raw English exception text (iOS `DiscoveryView.addNode`).
     */
    val addError: AddContactError? = null,
    val addingNodeId: Uuid? = null,
) {
    fun isAdded(node: DiscoveredNodeRecord): Boolean = node.publicKey.toUppercaseHex() in addedPublicKeyHexes

    /** Search spans all types and ignores the segment, matching iOS `isSearching`. */
    val isSearching: Boolean get() = searchText.isNotEmpty()
}

/** The block the Discover screen renders. */
enum class DiscoveryListPhase { LOADING, EMPTY, SEARCH_EMPTY, ROWS }

/**
 * The block Discover shows for [state] given its [filteredCount] visible rows. The search-empty copy
 * interpolates the query, so a segment filter that hides every row falls back to the plain empty state
 * rather than reading "no results for ''". Mirrors the iOS `isSearching` branch.
 */
fun discoveryListPhase(state: DiscoveryState, filteredCount: Int): DiscoveryListPhase = when {
    !state.hasLoadedOnce -> DiscoveryListPhase.LOADING
    filteredCount > 0 -> DiscoveryListPhase.ROWS
    state.searchText.isNotEmpty() -> DiscoveryListPhase.SEARCH_EMPTY
    else -> DiscoveryListPhase.EMPTY
}
