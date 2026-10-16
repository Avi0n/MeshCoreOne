package one.meshcore.shared.contact

import kotlinx.coroutines.flow.StateFlow
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.model.toUppercaseHex

/**
 * Volatile discovered-node row for the Discover screen (not backed up). Mirrors the load-bearing
 * fields of iOS `DiscoveredNode` / `DiscoveredNodeDTO`. [latitude]/[longitude]/[outPathLength]/[outPath]
 * default to "no coordinate, flood-routed" (the iOS model default) so a caller that only knows
 * name/type/key still builds a valid row.
 */
data class DiscoveredNodeRecord(
    val id: Uuid,
    val radioID: Uuid,
    val publicKey: ByteArray,
    val name: String,
    val typeRawValue: Int,
    val lastHeardEpochSeconds: Long,
    /** Firmware advertisement timestamp carried on the last advert (iOS `lastAdvertTimestamp`). */
    val lastAdvertTimestamp: Long = 0L,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val outPathLength: Int = CONTACT_FLOOD_PATH_SENTINEL,
    val outPath: ByteArray = ByteArray(0),
    /**
     * Hops the advert traversed to reach this phone (inbound), decoded from the heard RX-log packet.
     * null = never heard via an advert RX-log entry. Distinct from [outPath]/[outPathLength], the route
     * used to send to this node.
     */
    val inboundHopCount: Int? = null,
    /** The firmware advert timestamp current when [inboundHopCount] was last written (latest-advert pairing). */
    val inboundHopAdvertTimestamp: Long? = null,
) {
    val contactType: ContactType
        get() = ContactType.entries.firstOrNull { it.raw == typeRawValue } ?: ContactType.CHAT

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DiscoveredNodeRecord) return false
        return sameIdentity(other) && sameAdvert(other) && sameRoute(other)
    }

    private fun sameIdentity(other: DiscoveredNodeRecord): Boolean =
        id == other.id && radioID == other.radioID && publicKey.contentEquals(other.publicKey)

    private fun sameAdvert(other: DiscoveredNodeRecord): Boolean = name == other.name &&
        typeRawValue == other.typeRawValue &&
        lastHeardEpochSeconds == other.lastHeardEpochSeconds

    private fun sameRoute(other: DiscoveredNodeRecord): Boolean = samePlace(other) && samePath(other) && sameHop(other)

    private fun samePlace(other: DiscoveredNodeRecord): Boolean = lastAdvertTimestamp == other.lastAdvertTimestamp &&
        latitude == other.latitude &&
        longitude == other.longitude

    private fun samePath(other: DiscoveredNodeRecord): Boolean = outPathLength == other.outPathLength &&
        outPath.contentEquals(other.outPath) &&
        inboundHopCount == other.inboundHopCount

    private fun sameHop(other: DiscoveredNodeRecord): Boolean =
        inboundHopAdvertTimestamp == other.inboundHopAdvertTimestamp

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + radioID.hashCode()
        result = 31 * result + publicKey.contentHashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + typeRawValue
        result = 31 * result + lastHeardEpochSeconds.hashCode()
        result = 31 * result + lastAdvertTimestamp.hashCode()
        result = 31 * result + latitude.hashCode()
        result = 31 * result + longitude.hashCode()
        result = 31 * result + outPathLength
        result = 31 * result + outPath.contentHashCode()
        result = 31 * result + (inboundHopCount ?: 0)
        result = 31 * result + inboundHopAdvertTimestamp.hashCode()
        return result
    }
}

/** Discover segment filter (iOS `DiscoverSegment`). */
enum class DiscoverSegment {
    ALL,
    CONTACTS,
    REPEATERS,
    ROOMS,
}

/** Maximum volatile Discover rows retained for one radio, matching iOS. */
const val MAX_DISCOVERED_NODES_PER_RADIO: Int = 1000

/**
 * App-only volatile store for passively discovered nodes. Cap matches iOS (1000 per radio).
 * [snapshot] is every radio's rows (callers filter by radioID); upsert/clear publish a new list.
 */
interface DiscoveredNodeStore {
    /** All discovered rows across radios, newest-heard first within each radio's list order. */
    val snapshot: StateFlow<List<DiscoveredNodeRecord>>

    suspend fun list(radioID: Uuid): List<DiscoveredNodeRecord>

    suspend fun clear(radioID: Uuid)

    /** Upserts by (radioID, publicKey); returns true when a new row was inserted. */
    suspend fun upsert(node: DiscoveredNodeRecord): Boolean

    /**
     * Stamps the passively-heard inbound advert hop count on the matching row (mirrors iOS
     * `setInboundHopCount`). Buffers the reading when no row exists yet, since the firmware emits the
     * ADVERT RX packet before the advert push that creates the row; the next [upsert] drains it. The
     * adopt rule keeps the closest copy of the latest advert broadcast.
     */
    suspend fun setInboundHopCount(radioID: Uuid, publicKey: ByteArray, hopCount: Int, advertTimestamp: Long?)

    /** Removes one row by (radioID, publicKey); a no-op if no such row exists. Mirrors iOS `deleteDiscoveredNode`. */
    suspend fun remove(radioID: Uuid, publicKey: ByteArray)
}

/**
 * Contact frame for adding a discovered node, carrying the row's captured location and out-path.
 * [lastAdvertTimestamp] is the firmware advert time the node broadcast, never the receiver's wall clock:
 * the contact row persists it and stale-node age and share-contact freshness both read it back.
 */
fun DiscoveredNodeRecord.toContactFrame(nowEpochSeconds: Long): ContactFrame = ContactFrame(
    publicKey = publicKey,
    type = contactType,
    typeRawValue = typeRawValue,
    flags = 0,
    outPathLength = outPathLength,
    outPath = outPath,
    name = name,
    lastAdvertTimestamp = lastAdvertTimestamp,
    latitude = latitude,
    longitude = longitude,
    lastModified = nowEpochSeconds,
)

/**
 * Filters discovered nodes by segment and search text, mirroring iOS `DiscoveryViewModel.filteredNodes`.
 * A search spans every node type and the segment is ignored while one is active, so a repeater the user
 * types the name of is never hidden by the segment that happens to be selected; with no search the
 * segment alone applies. The predicate is a case-insensitive name substring or a public-key hex prefix.
 */
fun filterDiscoveredNodes(
    nodes: List<DiscoveredNodeRecord>,
    segment: DiscoverSegment,
    searchText: String,
): List<DiscoveredNodeRecord> {
    val query = searchText.trim()
    if (query.isEmpty()) {
        return when (segment) {
            DiscoverSegment.ALL -> nodes
            DiscoverSegment.CONTACTS -> nodes.filter { it.contactType == ContactType.CHAT }
            DiscoverSegment.REPEATERS -> nodes.filter { it.contactType == ContactType.REPEATER }
            DiscoverSegment.ROOMS -> nodes.filter { it.contactType == ContactType.ROOM }
        }
    }
    val upperQuery = query.uppercase()
    return nodes.filter { node ->
        node.name.contains(query, ignoreCase = true) || node.publicKey.toUppercaseHex().startsWith(upperQuery)
    }
}

/**
 * Sorts discovered nodes per [order], the Kotlin port of iOS `DiscoveryViewModel.sorted` reusing
 * [NodeSortOrder] (the same four modes as the Nodes list). [NodeSortOrder.DISTANCE] falls back to
 * [NodeSortOrder.LAST_HEARD] when [userLocation] is unavailable, matching [ContactListFiltering].
 */
fun sortDiscoveredNodes(
    nodes: List<DiscoveredNodeRecord>,
    order: NodeSortOrder,
    userLocation: UserLocation?,
): List<DiscoveredNodeRecord> {
    val effectiveOrder = if (order == NodeSortOrder.DISTANCE && userLocation == null) {
        NodeSortOrder.LAST_HEARD
    } else {
        order
    }
    return nodes.sortedWith(discoveredNodeComparator(effectiveOrder, userLocation))
}

private fun discoveredNodeComparator(
    order: NodeSortOrder,
    userLocation: UserLocation?,
): Comparator<DiscoveredNodeRecord> = when (order) {
    NodeSortOrder.LAST_HEARD -> compareByDescending { it.lastHeardEpochSeconds }
    NodeSortOrder.NAME -> discoveredNodeByName
    NodeSortOrder.DISTANCE -> discoveredNodeDistanceThenName(userLocation)
    NodeSortOrder.HOPS -> discoveredNodeHopsThenDistance(userLocation)
}

/** Case-insensitive name ascending. */
private val discoveredNodeByName: Comparator<DiscoveredNodeRecord> = compareBy { it.name.lowercase() }

/** Located nodes first, then nearest to [userLocation], then by name; name-only without a location. */
private fun discoveredNodeDistanceThenName(userLocation: UserLocation?): Comparator<DiscoveredNodeRecord> =
    distanceThenNameComparator(
        userLocation = userLocation,
        hasLocation = DiscoveredNodeRecord::hasLocation,
        distanceMeters = DiscoveredNodeRecord::distanceMeters,
        byName = discoveredNodeByName,
    )

/** By out-path hop count ascending; flood-routed (unknown) rows sort last; ties fall back to distance-then-name. */
private fun discoveredNodeHopsThenDistance(userLocation: UserLocation?): Comparator<DiscoveredNodeRecord> =
    hopsThenComparator(
        hops = DiscoveredNodeRecord::displayedHopCount,
        fallback = discoveredNodeDistanceThenName(userLocation),
    )

fun newDiscoveredNodeId(): Uuid = Uuid.random()
