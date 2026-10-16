package one.meshcore.shared.chat.pathdetail

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.chat.NodeNameMatchKind
import one.meshcore.shared.chat.NodeNameResolution
import one.meshcore.shared.contact.DiscoveredNodeRecord
import one.meshcore.shared.contact.UserLocation
import one.meshcore.shared.remotenode.ui.NeighborPrefixResolver

/**
 * Names the last-hop repeater on a heard-repeat row. Mirrors iOS `RepeatRowView.resolution`: a unique
 * short prefix is exact, a colliding prefix is fallback, and an unmatched or empty hash is unresolved
 * with [unknownName] rather than a guessed contact. The sheet shows a "?" only on fallback.
 */
fun repeatRowResolution(
    repeaterHash: ByteArray?,
    contacts: List<ContactDto>,
    discovered: List<DiscoveredNodeRecord> = emptyList(),
    userLocation: UserLocation? = null,
    unknownName: String,
): NodeNameResolution {
    if (repeaterHash == null || repeaterHash.isEmpty()) {
        return NodeNameResolution(displayName = unknownName, matchKind = NodeNameMatchKind.UNRESOLVED)
    }
    val resolved = NeighborPrefixResolver.resolve(repeaterHash, contacts, discovered, userLocation)
    return if (resolved.matchKind == NodeNameMatchKind.UNRESOLVED) {
        NodeNameResolution(displayName = unknownName, matchKind = NodeNameMatchKind.UNRESOLVED)
    } else {
        resolved
    }
}

/** Unresolved last hops use [unknown], not the hash, as the name (iOS `RepeatRowView`). */
fun repeatRowTitle(resolution: NodeNameResolution, unknown: String): String =
    if (resolution.matchKind == NodeNameMatchKind.UNRESOLVED) unknown else resolution.displayName
