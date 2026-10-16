package one.meshcore.shared.remotenode.ui

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.chat.NodeNameMatchKind
import one.meshcore.shared.chat.NodeNameResolution
import one.meshcore.shared.chat.pathdetail.PathHop
import one.meshcore.shared.contact.DiscoveredNodeRecord
import one.meshcore.shared.contact.UserLocation
import one.meshcore.shared.contact.isFloodRouted
import one.meshcore.shared.contact.pathHops
import one.meshcore.shared.contact.pathString
import one.meshcore.shared.model.ContactType

/**
 * Pure route-path presentation for the node status sheet, mirroring iOS `NodeRoutePathSection` and
 * `NeighborNameResolver.resolvePath`. Only repeaters relay, so both sources are narrowed to repeaters
 * before matching, matching the login sheet's path display.
 */
object NodeRoutePathLogic {
    data class ResolvedPathHop(val index: Int, val hex: String, val resolution: NodeNameResolution)

    /** Summary line: flood / Direct / hop hex chain. */
    fun summaryText(contact: ContactDto, floodLabel: String, directLabel: String): String = when {
        contact.isFloodRouted -> floodLabel
        contact.pathHops.isEmpty() -> directLabel
        else -> contact.pathString
    }

    fun resolveHops(
        contact: ContactDto,
        contacts: List<ContactDto>,
        unknownHopLabel: String,
        discovered: List<DiscoveredNodeRecord> = emptyList(),
        userLocation: UserLocation? = null,
    ): List<ResolvedPathHop> {
        if (contact.isFloodRouted) return emptyList()
        val hops = contact.pathHops
        if (hops.isEmpty()) return emptyList()
        val repeaters = contacts.filter {
            ContactType.fromRaw(it.typeRawValue) == ContactType.REPEATER
        }
        val discoveredRepeaters = discovered.filter { it.contactType == ContactType.REPEATER }
        return hops.mapIndexed { index, hop ->
            ResolvedPathHop(
                index = index,
                hex = hop.hex,
                resolution = resolveHop(hop, repeaters, discoveredRepeaters, userLocation, unknownHopLabel),
            )
        }
    }

    private fun resolveHop(
        hop: PathHop,
        repeaters: List<ContactDto>,
        discoveredRepeaters: List<DiscoveredNodeRecord>,
        userLocation: UserLocation?,
        unknownHopLabel: String,
    ): NodeNameResolution {
        val resolution = NeighborPrefixResolver.resolve(hop.raw, repeaters, discoveredRepeaters, userLocation)
        return if (resolution.matchKind == NodeNameMatchKind.UNRESOLVED) {
            NodeNameResolution(displayName = unknownHopLabel, matchKind = NodeNameMatchKind.UNRESOLVED)
        } else {
            resolution
        }
    }
}
