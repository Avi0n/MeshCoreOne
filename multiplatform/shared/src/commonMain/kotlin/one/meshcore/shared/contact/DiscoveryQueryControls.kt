package one.meshcore.shared.contact

/** Read and edit operations that derive the visible Discover list. */
interface DiscoveryQueryControls {
    fun filteredNodes(state: DiscoveryState): List<DiscoveredNodeRecord>

    fun setSortOrder(order: NodeSortOrder)

    fun setSegment(segment: DiscoverSegment)

    fun setSearchText(text: String)
}
