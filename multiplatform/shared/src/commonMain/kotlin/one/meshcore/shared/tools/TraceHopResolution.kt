package one.meshcore.shared.tools

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.contact.UserLocation
import one.meshcore.shared.contact.asPrefixCandidate
import one.meshcore.shared.contact.displayName
import one.meshcore.shared.contact.hasLocation
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.pathedit.DiscoveredNode
import one.meshcore.shared.remotenode.ui.NeighborPrefixResolver
import one.meshcore.shared.trace.TraceHop
import one.meshcore.shared.trace.TraceResult
import one.meshcore.shared.pathedit.PathHop as EditableHop

/**
 * Resolves a name and a coordinate for every hop of a completed trace: the intermediate repeaters from
 * the nodes this radio knows, and the start and end hops — both the local device — from [deviceLocation].
 *
 * The runner sees only wire bytes, so the hops it returns carry neither. Without this pass every hop
 * fails `TraceHop.hasLocation`, the total-distance readout is permanently "Unavailable", the result map
 * overlay is always empty, and each hop renders as bare hex. Mirrors the resolution half of the iOS
 * `TracePathViewModel.handleTraceResponse`: the outbound hop carrying the same hash supplies its full
 * public key, the returned hash itself is the fallback prefix match, and `bestAvailableLocation` stamps
 * both ends and breaks colliding prefixes by proximity. A null [deviceLocation] (no phone fix, no radio
 * coordinates) leaves the ends unplaced, and the distance figure then covers only the repeaters between
 * them.
 */
fun resolveTraceHops(
    result: TraceResult,
    outboundPath: List<EditableHop>,
    contacts: List<ContactDto>,
    discovered: List<DiscoveredNode>,
    deviceLocation: LatLng?,
): TraceResult {
    if (!result.success) return result
    val userLocation = deviceLocation?.let { UserLocation(it.latitude, it.longitude) }
    return result.copy(
        hops = result.hops.map { hop ->
            if (hop.isStartNode || hop.isEndNode) return@map hop.atDevice(deviceLocation)
            val hash = hop.hashBytes ?: return@map hop
            val contact = matchingContact(hash, outboundPath, contacts, userLocation)
            hop.resolved(contact, hash, contacts, discovered, userLocation)
        },
    )
}

/** Both ends of a trace are this device, so they carry its position when one is known. */
private fun TraceHop.atDevice(location: LatLng?): TraceHop =
    if (location == null) this else copy(latitude = location.latitude, longitude = location.longitude)

/**
 * The contact behind [hash], preferring the full public key recorded on the outbound hop the user built
 * the path from; an exact key beats the prefix match, which can be ambiguous at a 1-byte hash width.
 */
private fun matchingContact(
    hash: ByteArray,
    outboundPath: List<EditableHop>,
    contacts: List<ContactDto>,
    userLocation: UserLocation?,
): ContactDto? {
    val key = outboundPath.firstOrNull { it.hashBytes.contentEquals(hash) }?.publicKey
        ?: NeighborPrefixResolver.resolveCandidates(
            hash,
            contacts.map { it.asPrefixCandidate() },
            userLocation = userLocation,
        )?.publicKey
        ?: return null
    return contacts.firstOrNull { it.publicKey.contentEquals(key) }
}

private fun TraceHop.resolved(
    contact: ContactDto?,
    hash: ByteArray,
    contacts: List<ContactDto>,
    discovered: List<DiscoveredNode>,
    userLocation: UserLocation?,
): TraceHop {
    val located = contact?.takeIf { it.hasLocation }
    return copy(
        resolvedName = resolvedName
            ?: contact?.displayName
            ?: NeighborPrefixResolver.resolveCandidates(
                prefix = hash,
                contacts = contacts.map { it.asPrefixCandidate() },
                discovered = discovered.map { it.asPrefixCandidate() },
                userLocation = userLocation,
            )?.displayName,
        latitude = located?.latitude ?: latitude,
        longitude = located?.longitude ?: longitude,
    )
}
