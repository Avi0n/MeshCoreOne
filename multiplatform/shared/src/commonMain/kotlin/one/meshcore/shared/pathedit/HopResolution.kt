package one.meshcore.shared.pathedit

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.contact.displayName
import one.meshcore.shared.contact.hasLocation
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.model.startsWithPrefix

/**
 * Prefix-match a hop's hash bytes against known nodes, ported from the iOS `PathManagementViewModel`
 * resolution helpers. A strict single-match rule: exactly one contact match wins; zero contact matches
 * fall through to discovered nodes under the same single-match rule; ambiguity (two or more contacts)
 * returns nothing and never falls through, since an ambiguous contact match could itself be the target.
 *
 * Pure over the supplied node lists so the classifier's `resolve` seam and the load-time normalization
 * can drive every branch without a live connection.
 */
object HopResolution {
    /** The full 32-byte key of the sole node whose public key starts with [hashBytes], or null. */
    fun resolvePublicKey(
        hashBytes: ByteArray,
        contacts: List<ContactDto>,
        discovered: List<DiscoveredNode>,
    ): ByteArray? = resolveSolePrefix(
        hashBytes,
        contacts,
        discovered,
        contactValue = { it.publicKey },
        discoveredValue = { it.publicKey },
    )

    /** The display name of the sole node whose public key starts with [hashBytes], or null. */
    fun resolveName(hashBytes: ByteArray, contacts: List<ContactDto>, discovered: List<DiscoveredNode>): String? =
        resolveSolePrefix(
            hashBytes,
            contacts,
            discovered,
            contactValue = { it.displayName },
            discoveredValue = { it.name },
        )

    /**
     * One contact match wins. No contact matches fall through to a single discovered node. Two contact
     * matches return nothing and do not fall through.
     */
    private fun <T> resolveSolePrefix(
        hashBytes: ByteArray,
        contacts: List<ContactDto>,
        discovered: List<DiscoveredNode>,
        contactValue: (ContactDto) -> T,
        discoveredValue: (DiscoveredNode) -> T,
    ): T? {
        val contactMatches = contacts.filter { it.publicKey.startsWithPrefix(hashBytes) }
        return when {
            contactMatches.size == 1 -> contactValue(contactMatches[0])
            contactMatches.isEmpty() ->
                discovered.singleOrNull { it.publicKey.startsWithPrefix(hashBytes) }?.let(discoveredValue)
            else -> null
        }
    }

    /**
     * The coordinate of the sole node whose public key starts with [hashBytes], or null when no unique
     * match carries a usable position. Contacts win over discovered rows; a node heard only through an
     * advert still resolves a pin, which is what keeps the trace distance readout off its fallback.
     */
    fun resolveLocation(hashBytes: ByteArray, contacts: List<ContactDto>, discovered: List<DiscoveredNode>): LatLng? {
        val contactMatch = contacts.singleOrNull { it.publicKey.startsWithPrefix(hashBytes) }?.takeIf { it.hasLocation }
        if (contactMatch != null) return LatLng(contactMatch.latitude, contactMatch.longitude)
        return discovered.singleOrNull { it.publicKey.startsWithPrefix(hashBytes) }?.location
    }

    /** Build a [PathHop] from [hashBytes], resolving its full key and name when a unique node matches. */
    fun createPathHop(hashBytes: ByteArray, contacts: List<ContactDto>, discovered: List<DiscoveredNode>): PathHop =
        PathHop(
            hashBytes = hashBytes,
            publicKey = resolvePublicKey(hashBytes, contacts, discovered),
            resolvedName = resolveName(hashBytes, contacts, discovered),
        )
}
