package one.meshcore.shared.contact

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.pathedit.DiscoveredNode

/**
 * One prefix-match candidate, flattened from whichever row supplied it (iOS `RepeaterResolvable`).
 * Saved contacts, passively discovered rows and the path picker's nodes all resolve through the same
 * ordering, so they meet in this shape rather than each carrying a copy of the comparator.
 */
class PrefixCandidate(
    val publicKey: ByteArray,
    val name: String,
    /** The node's coordinate when it carries a usable one. */
    val location: LatLng?,
    val lastAdvertTimestamp: Long,
    /** Secondary recency tiebreak: a contact's recencyTimestamp, a discovered row's last-heard. */
    val recencySeconds: Long,
)

fun ContactDto.asPrefixCandidate(): PrefixCandidate = PrefixCandidate(
    publicKey = publicKey,
    name = displayName,
    location = if (hasLocation) LatLng(latitude, longitude) else null,
    lastAdvertTimestamp = lastAdvertTimestamp,
    recencySeconds = recencyTimestamp,
)

fun DiscoveredNodeRecord.asPrefixCandidate(): PrefixCandidate = PrefixCandidate(
    publicKey = publicKey,
    name = name,
    location = if (hasLocation) LatLng(latitude, longitude) else null,
    lastAdvertTimestamp = lastAdvertTimestamp,
    recencySeconds = lastHeardEpochSeconds,
)

/**
 * The path picker's discovered row as a candidate. It carries no separate last-heard column, so the
 * advert timestamp stands in for the secondary recency tiebreak; the primary sort already reads it.
 */
fun DiscoveredNode.asPrefixCandidate(): PrefixCandidate = PrefixCandidate(
    publicKey = publicKey,
    name = name,
    location = location,
    lastAdvertTimestamp = lastAdvertTimestamp,
    recencySeconds = lastAdvertTimestamp,
)
