package one.meshcore.shared.pathedit

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.contact.displayName

/** The five Add-Hop picker sections, materialized once per render. Ported from the iOS `PickerResults`. */
data class PickerSections(
    val recent: List<PickerNode> = emptyList(),
    val favorites: List<PickerNode> = emptyList(),
    val contacts: List<PickerNode> = emptyList(),
    val discovered: List<PickerNode> = emptyList(),
    val rooms: List<PickerNode> = emptyList(),
) {
    val isEmpty: Boolean
        get() = recent.isEmpty() && favorites.isEmpty() && contacts.isEmpty() && discovered.isEmpty() && rooms.isEmpty()
}

/**
 * Builds the Add-Hop picker's sections from the editor [state], the frozen recent-key snapshot, the
 * active [filter], and the search [query], ported from the iOS `AddHopPickerView.buildResults`.
 *
 * Cross-section dedup only applies in [AddHopFilter.ALL], where every section shows at once; a
 * single-section filter shows nothing else, so excluding recent/contact keys there would hide a node that
 * has no other section to appear in. Each section is name-substring/hex filtered through [HopNodeMatching]
 * so the search query narrows every list consistently. [sessionRecentKeys] is the picker's frozen snapshot,
 * distinct from the live `state.recentPublicKeys`.
 */
object AddHopPickerSections {
    fun build(
        state: PathEditState,
        filter: AddHopFilter,
        query: String,
        sessionRecentKeys: List<ByteArray>,
    ): PickerSections {
        val repeaters = state.availableRepeaters
        val rooms = state.availableRooms
        val unfiltered = filter == AddHopFilter.ALL
        val recentKeys = if (unfiltered) sessionRecentKeys.map { it.toList() }.toSet() else emptySet()
        val contactKeys = if (unfiltered) repeaters.map { it.publicKey.toList() }.toSet() else emptySet()

        return PickerSections(
            recent = if (filter == AddHopFilter.ALL || filter == AddHopFilter.RECENT) {
                recentSection(sessionRecentKeys, repeaters, state.discoveredRepeaters, query)
            } else {
                emptyList()
            },
            favorites = if (filter == AddHopFilter.ALL || filter == AddHopFilter.FAVORITES) {
                contactSection(repeaters.filter { it.isFavorite && it.publicKey.toList() !in recentKeys }, query)
            } else {
                emptyList()
            },
            contacts = if (filter == AddHopFilter.ALL) {
                contactSection(repeaters.filter { !it.isFavorite && it.publicKey.toList() !in recentKeys }, query)
            } else {
                emptyList()
            },
            discovered = if (filter == AddHopFilter.ALL || filter == AddHopFilter.DISCOVERED) {
                discoveredSection(state.discoveredRepeaters, contactKeys, recentKeys, query)
            } else {
                emptyList()
            },
            rooms = if (filter == AddHopFilter.ALL) {
                contactSection(rooms.filter { it.publicKey.toList() !in recentKeys }, query)
            } else {
                emptyList()
            },
        )
    }

    /** Recent hits resolved against repeaters then discovered (first hit wins), LRU order preserved, then filtered. */
    private fun recentSection(
        sessionRecentKeys: List<ByteArray>,
        repeaters: List<ContactDto>,
        discovered: List<DiscoveredNode>,
        query: String,
    ): List<PickerNode> {
        val resolved = sessionRecentKeys.mapNotNull { key ->
            repeaters.firstOrNull { it.publicKey.contentEquals(key) }?.let { return@mapNotNull PickerNode.Contact(it) }
            discovered.firstOrNull { it.publicKey.contentEquals(key) }?.let { PickerNode.Discovered(it) }
        }
        return HopNodeMatching.filtered(resolved, query)
    }

    private fun contactSection(contacts: List<ContactDto>, query: String): List<PickerNode> {
        val nodes = contacts.sortedBy { it.displayName.lowercase() }.map { PickerNode.Contact(it) }
        return HopNodeMatching.filtered(nodes, query)
    }

    private fun discoveredSection(
        discovered: List<DiscoveredNode>,
        contactKeys: Set<List<Byte>>,
        recentKeys: Set<List<Byte>>,
        query: String,
    ): List<PickerNode> {
        val nodes = discovered
            .filter { it.publicKey.toList() !in contactKeys && it.publicKey.toList() !in recentKeys }
            .sortedBy { it.name.lowercase() }
            .map { PickerNode.Discovered(it) }
        return HopNodeMatching.filtered(nodes, query)
    }
}
