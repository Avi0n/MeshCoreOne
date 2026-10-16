package one.meshcore.shared.remotenode.ui

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.chat.NodeNameMatchKind
import one.meshcore.shared.chat.NodeNameResolution
import one.meshcore.shared.contact.DiscoveredNodeRecord
import one.meshcore.shared.contact.PrefixCandidate
import one.meshcore.shared.contact.UserLocation
import one.meshcore.shared.contact.asPrefixCandidate
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.map.haversineMeters
import one.meshcore.shared.model.startsWithPrefix
import one.meshcore.shared.model.toUppercaseHex

/**
 * Resolves a public-key prefix to a node name for the status rows, the SNR map, the route path and the
 * auth-sheet path preview. Mirrors iOS `RepeaterResolver.resolve` + `NeighborNameResolver`: saved contacts
 * are preferred over passively discovered rows, collisions break by proximity to the user and then by
 * advert recency, and the confidence is refined across both sources. A repeater heard only through an
 * advert therefore shows its name rather than bare hex.
 */
object NeighborPrefixResolver {
    /**
     * A resolution carrying the resolved node's coordinate so the SNR map can place it. Latitude and
     * longitude are null when the node carries no usable position. Mirrors iOS `ResolvedNeighbor`.
     */
    data class ResolvedNeighbor(
        val displayName: String,
        val matchKind: NodeNameMatchKind,
        val latitude: Double?,
        val longitude: Double?,
        /** The winner's full public key, so a caller can find the row the candidate was flattened from. */
        val publicKey: ByteArray,
    )

    /**
     * Current-neighbor rows use "Unknown" when the prefix is unresolved, matching iOS
     * `RepeaterStatusContent`. The hex identity still sits on the subtitle.
     */
    fun neighborRowDisplayName(resolution: NodeNameResolution, unknownLabel: String): String =
        if (resolution.matchKind == NodeNameMatchKind.UNRESOLVED) unknownLabel else resolution.displayName

    /**
     * Prefix width at or above which a match is trusted outright: a 6-byte collision is not worth
     * hedging over. Mirrors iOS `RepeaterResolver.exactPrefixLength`.
     */
    private const val EXACT_PREFIX_LENGTH = 6

    /** iOS `RepeaterResolver.minimumKeyDisplayByteCount`. */
    const val MIN_KEY_DISPLAY_BYTE_COUNT = 2

    /** iOS `RepeaterResolver.maximumKeyDisplayByteCount`. */
    const val MAX_KEY_DISPLAY_BYTE_COUNT = 3

    /**
     * Bytes of public-key prefix to show for a neighbour identity hex string.
     * Uses the radio `hashSize` when present, clamped to 2–3. Mirrors iOS
     * `RepeaterResolver.keyDisplayByteCount`.
     */
    fun keyDisplayByteCount(deviceHashSize: Int?): Int {
        val size = deviceHashSize ?: return MIN_KEY_DISPLAY_BYTE_COUNT
        return size.coerceIn(MIN_KEY_DISPLAY_BYTE_COUNT, MAX_KEY_DISPLAY_BYTE_COUNT)
    }

    /** The name and confidence for [prefix], or a hex fallback tagged [NodeNameMatchKind.UNRESOLVED]. */
    fun resolve(
        prefix: ByteArray,
        contacts: List<ContactDto>,
        discovered: List<DiscoveredNodeRecord> = emptyList(),
        userLocation: UserLocation? = null,
        keyDisplayByteCount: Int = MIN_KEY_DISPLAY_BYTE_COUNT,
    ): NodeNameResolution = resolveLocated(prefix, contacts, discovered, userLocation)
        ?.let { NodeNameResolution(displayName = it.displayName, matchKind = it.matchKind) }
        ?: NodeNameResolution(
            displayName = fallbackName(prefix, keyDisplayByteCount),
            matchKind = NodeNameMatchKind.UNRESOLVED,
        )

    /** [resolve] plus the resolved node's coordinate, or null when nothing carries [prefix]. */
    fun resolveLocated(
        prefix: ByteArray,
        contacts: List<ContactDto>,
        discovered: List<DiscoveredNodeRecord> = emptyList(),
        userLocation: UserLocation? = null,
    ): ResolvedNeighbor? = resolveCandidates(
        prefix = prefix,
        contacts = contacts.map { it.asPrefixCandidate() },
        discovered = discovered.map { it.asPrefixCandidate() },
        userLocation = userLocation,
    )

    /**
     * [resolveLocated] over candidates that have already been flattened, for the callers whose rows are
     * not [ContactDto]/[DiscoveredNodeRecord] — the trace and chat path views, which hold the path
     * picker's own node type.
     */
    fun resolveCandidates(
        prefix: ByteArray,
        contacts: List<PrefixCandidate>,
        discovered: List<PrefixCandidate> = emptyList(),
        userLocation: UserLocation? = null,
    ): ResolvedNeighbor? {
        if (prefix.isEmpty()) return null
        val match = bestMatch(prefix, contacts, userLocation)
            ?: bestMatch(prefix, discovered, userLocation)
            ?: return null
        return ResolvedNeighbor(
            displayName = match.node.name,
            matchKind = refinedMatchKind(prefix, match.matchKind, contacts, discovered),
            latitude = match.node.location?.latitude,
            longitude = match.node.location?.longitude,
            publicKey = match.node.publicKey,
        )
    }

    /**
     * Uppercase hex of the leading [byteCount] bytes. Clamped to [MAX_KEY_DISPLAY_BYTE_COUNT]
     * so a fallback title never dumps the full wire prefix. Mirrors iOS
     * `RepeaterResolver.fallbackName`.
     */
    fun fallbackName(prefix: ByteArray, byteCount: Int = MIN_KEY_DISPLAY_BYTE_COUNT): String {
        val n = byteCount.coerceIn(0, MAX_KEY_DISPLAY_BYTE_COUNT).coerceAtMost(prefix.size)
        return if (n == 0) "" else prefix.copyOfRange(0, n).toUppercaseHex()
    }

    private class Match(val node: PrefixCandidate, val matchKind: NodeNameMatchKind)

    /**
     * Nearest first among located candidates, then the freshest advert, then the freshest row, then name.
     * Candidates with no measurable distance sort after located ones rather than winning on a null.
     */
    private val CANDIDATE_ORDER: Comparator<Pair<PrefixCandidate, Double?>> =
        compareBy<Pair<PrefixCandidate, Double?>> { it.second == null }
            .thenBy { it.second ?: 0.0 }
            .thenByDescending { it.first.lastAdvertTimestamp }
            .thenByDescending { it.first.recencySeconds }
            .thenBy { it.first.name.lowercase() }

    private fun bestMatch(prefix: ByteArray, nodes: List<PrefixCandidate>, userLocation: UserLocation?): Match? {
        val matches = nodes.filter { it.publicKey.startsWithPrefix(prefix) }
        if (matches.isEmpty()) return null
        val best = matches
            .map { it to it.distanceMeters(userLocation) }
            .sortedWith(CANDIDATE_ORDER)
            .first()
            .first
        val distinctKeys = matches.distinctBy { it.publicKey.toList() }.size
        val matchKind = if (prefix.size >= EXACT_PREFIX_LENGTH || distinctKeys == 1) {
            NodeNameMatchKind.EXACT
        } else {
            NodeNameMatchKind.FALLBACK
        }
        return Match(best, matchKind)
    }

    /**
     * Downgrades a short-prefix match to [NodeNameMatchKind.FALLBACK] when the other source also holds a
     * node under that prefix; a contact-book hit alone cannot claim the prefix is unambiguous.
     */
    private fun refinedMatchKind(
        prefix: ByteArray,
        resolved: NodeNameMatchKind,
        contacts: List<PrefixCandidate>,
        discovered: List<PrefixCandidate>,
    ): NodeNameMatchKind {
        if (resolved == NodeNameMatchKind.UNRESOLVED || prefix.size >= EXACT_PREFIX_LENGTH) return resolved
        val keys = contacts.filter { it.publicKey.startsWithPrefix(prefix) }.map { it.publicKey.toList() } +
            discovered.filter { it.publicKey.startsWithPrefix(prefix) }.map { it.publicKey.toList() }
        return if (keys.toSet().size > 1) NodeNameMatchKind.FALLBACK else NodeNameMatchKind.EXACT
    }

    private fun PrefixCandidate.distanceMeters(userLocation: UserLocation?): Double? {
        val location = location ?: return null
        val from = userLocation ?: return null
        return haversineMeters(LatLng(from.latitude, from.longitude), location)
    }
}
