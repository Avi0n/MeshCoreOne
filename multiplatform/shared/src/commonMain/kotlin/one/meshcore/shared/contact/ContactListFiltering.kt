package one.meshcore.shared.contact

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.map.containsIgnoringCaseAndDiacritics
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid

/** Max of radio `lastModified` and phone-clock `lastHeardTimestamp`. A missing heard stamp keeps lastModified. */
val ContactDto.recencyTimestamp: Long
    get() = maxOf(lastModified, lastHeardTimestamp ?: 0L)

/** Match used by stale-node prune. Favorites never match. Mirrors iOS `matchesStaleNodePrune`. */
internal fun ContactDto.matchesStaleNodePrune(cutoff: Long): Boolean = !isFavorite && recencyTimestamp < cutoff

/**
 * Pure segment/search filtering and sorting for the Nodes list — the Kotlin port of the iOS
 * `ContactsViewModel.filteredContacts`. Kept a stateless object over its inputs so the exact iOS
 * ordering is unit-tested without a view model or Compose.
 *
 * `inboundHopByKey` is keyed by uppercase-hex public key (a `ByteArray` is an unusable map key in
 * Kotlin); it supplies the passively-heard hop fallback the `hops` sort and route label read.
 */
object ContactListFiltering {
    /** Segment, search, and sort inputs for [filteredContacts]. */
    data class Inputs(
        val contacts: List<ContactDto>,
        val searchText: String,
        val segment: NodeSegment,
        val sortOrder: NodeSortOrder,
        val userLocation: UserLocation?,
        val inboundHopByKey: Map<String, Int>,
        val pendingRemovalIds: Set<Uuid>,
    )

    /**
     * The segment/search-filtered, sorted contacts. Rows pending removal are dropped first.
     * A non-empty search ignores the segment and spans all types; otherwise the segment selects a type.
     * Distance sort falls back to last heard when there is no user location.
     */
    fun filteredContacts(inputs: Inputs): List<ContactDto> {
        val visible = inputs.contacts.filter { it.id !in inputs.pendingRemovalIds }

        val matched = if (inputs.searchText.isEmpty()) {
            visible.filterBySegment(inputs.segment)
        } else {
            val query = inputs.searchText
            val upper = inputs.searchText.uppercase()
            visible.filter { contact ->
                contact.displayName.containsIgnoringCaseAndDiacritics(query) ||
                    contact.publicKeyHex.startsWith(upper)
            }
        }

        val effectiveOrder = if (inputs.sortOrder == NodeSortOrder.DISTANCE && inputs.userLocation == null) {
            NodeSortOrder.LAST_HEARD
        } else {
            inputs.sortOrder
        }
        return matched.sortedWith(comparator(effectiveOrder, inputs.userLocation, inputs.inboundHopByKey))
    }

    private fun List<ContactDto>.filterBySegment(segment: NodeSegment): List<ContactDto> = when (segment) {
        NodeSegment.FAVORITES -> filter { it.isFavorite }
        NodeSegment.CONTACTS -> filter { it.contactType == ContactType.CHAT }
        NodeSegment.REPEATERS -> filter { it.contactType == ContactType.REPEATER }
        NodeSegment.ROOMS -> filter { it.contactType == ContactType.ROOM }
    }

    private fun comparator(
        order: NodeSortOrder,
        userLocation: UserLocation?,
        inboundHopByKey: Map<String, Int>,
    ): Comparator<ContactDto> = when (order) {
        NodeSortOrder.LAST_HEARD -> compareByDescending { it.recencyTimestamp }
        NodeSortOrder.NAME -> byName
        NodeSortOrder.DISTANCE -> distanceThenName(userLocation)
        NodeSortOrder.HOPS -> hopsThenDistance(userLocation, inboundHopByKey)
    }

    /** Case-insensitive display-name ascending; the common-code stand-in for iOS `localizedCompare`. */
    private val byName: Comparator<ContactDto> = compareBy { it.displayName.lowercase() }

    /** Located nodes first, then nearest to [userLocation], then by name; name-only without a location. */
    private fun distanceThenName(userLocation: UserLocation?): Comparator<ContactDto> = distanceThenNameComparator(
        userLocation = userLocation,
        hasLocation = ContactDto::hasLocation,
        distanceMeters = ContactDto::distanceMeters,
        byName = byName,
    )

    /** By displayed hop count ascending; a `null` (unknown) count sorts last; ties fall back to distance-then-name. */
    private fun hopsThenDistance(
        userLocation: UserLocation?,
        inboundHopByKey: Map<String, Int>,
    ): Comparator<ContactDto> = hopsThenComparator(
        hops = { it.displayedHopCount(inboundHopByKey[it.publicKeyHex]) },
        fallback = distanceThenName(userLocation),
    )
}
