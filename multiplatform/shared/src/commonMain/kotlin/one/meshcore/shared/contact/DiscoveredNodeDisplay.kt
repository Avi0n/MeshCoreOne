package one.meshcore.shared.contact

import one.meshcore.shared.map.LatLng
import one.meshcore.shared.map.hasUsableCoordinate
import one.meshcore.shared.map.haversineMeters
import one.meshcore.shared.model.prefixHex

/**
 * Pure presentation helpers over a [DiscoveredNodeRecord], mirroring the [ContactDto] helpers in
 * `ContactDisplay.kt` so a Discover row and a Nodes-list row derive route/location/distance the same
 * way. Kept out of the Compose layer so sorting and row rendering read the same values.
 */

/**
 * The leading [hashSize] bytes of the public key as uppercase hex — the monospaced id prefix shown
 * at the head of a map callout. [hashSize] is the connected radio's key-prefix display size.
 */
fun DiscoveredNodeRecord.idPrefixHex(hashSize: Int = DEFAULT_HASH_SIZE): String = publicKey.prefixHex(hashSize)

/** True when the out-path is the flood-routing sentinel (`0xFF`). Mirrors [ContactDto.isFloodRouted]. */
val DiscoveredNodeRecord.isFloodRouted: Boolean get() = floodPath(outPathLength)

/** The number of hops in the deliberately-set out-path (0 when flood-routed or undecodable). */
val DiscoveredNodeRecord.pathHopCount: Int get() = decodedPathHopCount(outPathLength)

/**
 * The hop count to surface: the out-path hops when a route exists, otherwise the passively-heard inbound
 * advert hops stamped on this row. `null` when flood-routed with no advert hop count known. Mirrors
 * [ContactDto.displayedHopCount].
 */
val DiscoveredNodeRecord.displayedHopCount: Int? get() = displayedHopCount(isFloodRouted, pathHopCount, inboundHopCount)

/** The route label kind: a set non-flood zero-hop path is Direct; otherwise N hops; otherwise Flood. */
val DiscoveredNodeRecord.routeKind: ContactRouteKind
    get() = routeKindFor(isFloodRouted, pathHopCount, displayedHopCount)

/** Bytes per hop encoded in `outPathLength`, or 1 when flood-routed or undecodable. */
val DiscoveredNodeRecord.pathHashSize: Int get() = decodedPathHashSize(outPathLength)

/** Total path payload bytes (`hopCount * hashSize`), or 0 when flood-routed or undecodable. */
val DiscoveredNodeRecord.pathByteLength: Int get() = decodedPathBytes(outPathLength)

/** Each out-path hop as uppercase hex; empty when flood-routed or no route is set. */
val DiscoveredNodeRecord.pathNodesHex: List<String> get() = decodedPathHops(outPathLength, outPath).map { it.hex }

/**
 * Discover-row hop token: iOS `DiscoveryNodeRow` shows a bare integer beside the bounce icon.
 * The Nodes list still uses [ContactRouteKind.label] (`"N hops"`). Direct and Flood stay localized.
 */
fun ContactRouteKind.discoveryHopLabel(): String? = (this as? ContactRouteKind.Hops)?.count?.toString()

/**
 * True when Discover should show the curve-path glyph plus hex chain. iOS only draws that after a
 * non-flood hop count, and [pathNodesHex] is already empty for flood.
 */
val DiscoveredNodeRecord.showsDiscoveryPathIcon: Boolean
    get() = !isFloodRouted && pathNodesHex.isNotEmpty()

/**
 * The comma-joined hop chain a Discover row shows, elided in the middle once the route is longer than
 * [MAX_INLINE_PATH_HOPS] so a long path cannot push the rest of the row off screen. Mirrors the iOS
 * `DiscoveryNodeRow.formattedPath`.
 */
fun formatDiscoveryPath(hops: List<String>): String {
    if (hops.size <= MAX_INLINE_PATH_HOPS) return hops.joinToString(separator = PATH_SEPARATOR)
    val head = hops.take(PATH_ELISION_EDGE).joinToString(separator = PATH_SEPARATOR)
    val tail = hops.takeLast(PATH_ELISION_EDGE).joinToString(separator = PATH_SEPARATOR)
    return "$head$PATH_ELLIPSIS$tail"
}

private const val MAX_INLINE_PATH_HOPS = 6
private const val PATH_ELISION_EDGE = 3
private const val PATH_SEPARATOR = ","
private const val PATH_ELLIPSIS = "…"

/**
 * Whether the row carries a usable coordinate: a non-zero, valid WGS-84 lat/lon. Mirrors
 * [ContactDto.hasLocation].
 */
val DiscoveredNodeRecord.hasLocation: Boolean
    get() = hasUsableCoordinate(latitude, longitude)

/** Great-circle distance in meters from [from] to this node's coordinate. */
fun DiscoveredNodeRecord.distanceMeters(from: UserLocation): Double =
    haversineMeters(LatLng(from.latitude, from.longitude), LatLng(latitude, longitude))
