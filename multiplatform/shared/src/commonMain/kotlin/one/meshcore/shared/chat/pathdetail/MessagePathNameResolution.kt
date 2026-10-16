package one.meshcore.shared.chat.pathdetail

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.chat.NodeNameMatchKind
import one.meshcore.shared.chat.NodeNameResolution
import one.meshcore.shared.contact.DiscoveredNodeRecord
import one.meshcore.shared.contact.UserLocation
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.remotenode.ui.NeighborPrefixResolver

/**
 * Path-sheet hop names. Mirrors iOS `MessagePathViewModel.senderResolution` / `repeaterResolution`:
 * unresolved hops are [unknownLabel], never hex as the name (hex stays the row id).
 */
object MessagePathNameResolution {
    fun sender(message: MessageDto, contacts: List<ContactDto>, unknownLabel: String): NodeNameResolution {
        val channelName = message.senderNodeName?.takeIf { message.isChannelMessage && it.isNotEmpty() }
        if (channelName != null) return NodeNameResolution(channelName, NodeNameMatchKind.EXACT)
        val located = message.senderKeyPrefix?.takeIf { it.isNotEmpty() }
            ?.let { NeighborPrefixResolver.resolveLocated(it, contacts, emptyList()) }
        return located?.let { NodeNameResolution(it.displayName, it.matchKind) }
            ?: NodeNameResolution(unknownLabel, NodeNameMatchKind.UNRESOLVED)
    }

    fun repeater(
        hashBytes: ByteArray,
        repeaters: List<ContactDto>,
        discovered: List<DiscoveredNodeRecord>,
        userLocation: UserLocation?,
        unknownLabel: String,
    ): NodeNameResolution {
        val located = NeighborPrefixResolver.resolveLocated(hashBytes, repeaters, discovered, userLocation)
            ?: return NodeNameResolution(unknownLabel, NodeNameMatchKind.UNRESOLVED)
        return NodeNameResolution(located.displayName, located.matchKind)
    }
}

/**
 * Point B on the path map: the companion's stored fix when it has one, else the phone. Mirrors iOS
 * `MessagePathMapView` receiver location (device coords, then `bestAvailableLocation`).
 */
fun pathReceiverLocation(deviceLocation: LatLng?, phoneLocation: LatLng?): LatLng? = deviceLocation ?: phoneLocation
