package one.meshcore.shared.trace

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.map.MapFilterState
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.model.toUppercaseHex
import one.meshcore.shared.pathedit.DiscoveredNode
import one.meshcore.shared.pathedit.PathHop
import one.meshcore.shared.remotenode.NodeLocationFix

/**
 * Favorites / discovered pin algebra for the Trace Path map. Path members stay visible so a hop
 * already on the route cannot disappear when the user tightens the filter. Mirrors iOS
 * `TracePathMapViewModel.visibleContactPins` / `visibleDiscoveredPins`.
 */
object TracePathMapPinFilter {
    fun visibleContacts(
        candidates: List<ContactDto>,
        pathMemberIds: Set<Uuid>,
        filter: MapFilterState,
    ): List<ContactDto> {
        if (filter.favoritesOnly) {
            return candidates.filter { it.isFavorite || it.id in pathMemberIds }
        }
        return candidates
    }

    fun visibleDiscovered(
        discovered: List<DiscoveredNode>,
        contactKeys: Set<String>,
        pathMemberIds: Set<Uuid>,
        filter: MapFilterState,
    ): List<DiscoveredNode> {
        val pathMembers = discovered.filter { it.id in pathMemberIds && it.hasValidFix }
        if (filter.favoritesOnly || !filter.effectiveShowDiscovered) return pathMembers
        val byId = linkedMapOf<Uuid, DiscoveredNode>()
        for (node in discovered) {
            val listed = node.isRepeater &&
                node.hasValidFix &&
                node.publicKey.toUppercaseHex() !in contactKeys
            if (listed) byId[node.id] = node
        }
        for (member in pathMembers) {
            byId[member.id] = member
        }
        return byId.values.toList()
    }

    fun pathMemberIds(hops: List<PathHop>, contacts: List<ContactDto>, discovered: List<DiscoveredNode>): Set<Uuid> {
        val keys = hops.mapNotNull { hop -> hop.publicKey?.toUppercaseHex() }.toSet()
        if (keys.isEmpty()) return emptySet()
        val ids = mutableSetOf<Uuid>()
        contacts.forEach { contact ->
            if (contact.publicKey.toUppercaseHex() in keys) ids += contact.id
        }
        discovered.forEach { node ->
            if (node.publicKey.toUppercaseHex() in keys) ids += node.id
        }
        return ids
    }
}

internal val DiscoveredNode.hasValidFix: Boolean
    get() {
        val loc = location ?: return false
        return NodeLocationFix.isValidFix(loc.latitude, loc.longitude)
    }
