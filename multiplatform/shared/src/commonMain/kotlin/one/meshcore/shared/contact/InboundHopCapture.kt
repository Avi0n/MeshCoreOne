package one.meshcore.shared.contact

import one.meshcore.shared.chat.pathdetail.PathEncoding
import one.meshcore.shared.meshcore.ParsedRxLogData
import one.meshcore.shared.model.RouteType
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.model.toUppercaseHex

/** Wire payload type of an ADVERT RX-log packet (MeshCore `PayloadType.advert`). */
private const val ADVERT_PAYLOAD_TYPE = 4

/** A node public key is 32 bytes; the advert timestamp is a little-endian UInt32 at offset 32. */
private const val ADVERT_PUBLIC_KEY_SIZE = 32
private const val ADVERT_TIMESTAMP_SIZE = 4

/** One inbound advert hop reading pulled from an RX-log packet: the advertiser, its hops, and grouping timestamp. */
class InboundAdvertHop(val publicKey: ByteArray, val hopCount: Int, val advertTimestamp: Long?)

/**
 * Extracts the inbound advert hop reading from a parsed RX-log packet, mirroring the ADVERT branch of
 * iOS `RxLogService`. The advertiser's full public key sits at payload offset 0; the advert timestamp
 * follows at offset 32. Only flood-routed adverts accumulate a hop path, so a direct-routed advert
 * (whose path length is the remaining route, not hops traversed) is excluded. A reserved or truncated
 * encoding returns null rather than stamping a wrong value.
 */
fun parseInboundAdvertHop(data: ParsedRxLogData): InboundAdvertHop? {
    if (data.payloadType != ADVERT_PAYLOAD_TYPE) return null
    if (!isFloodAdvertRoute(data.routeType)) return null
    if (data.packetPayload.size < ADVERT_PUBLIC_KEY_SIZE) return null
    val hopCount = PathEncoding.decodePathLen(data.pathLength)?.hopCount ?: return null
    return advertHop(data.packetPayload, hopCount)
}

private fun isFloodAdvertRoute(routeType: Int): Boolean {
    val route = RouteType.fromRaw(routeType)
    return route == RouteType.FLOOD || route == RouteType.TC_FLOOD
}

private fun advertHop(payload: ByteArray, hopCount: Int): InboundAdvertHop {
    val advertTimestamp = if (payload.size >= ADVERT_PUBLIC_KEY_SIZE + ADVERT_TIMESTAMP_SIZE) {
        readUInt32LE(payload, ADVERT_PUBLIC_KEY_SIZE)
    } else {
        null
    }
    return InboundAdvertHop(
        publicKey = payload.copyOfRange(0, ADVERT_PUBLIC_KEY_SIZE),
        hopCount = hopCount,
        advertTimestamp = advertTimestamp,
    )
}

/** The `(hopCount, advertTimestamp)` pair to store, per [adoptInboundHop]. */
data class AdoptedInboundHop(val hopCount: Int, val advertTimestamp: Long?)

/**
 * Adopt rule for inbound advert hop counts, the port of iOS `adoptInboundHop`. Returns the pair to
 * store, or null to no-op (the caller skips the write to avoid StateFlow churn on repeated floods).
 *
 * - stored timestamp null -> adopt the incoming pair
 * - incoming.ts > stored.ts -> adopt (newer advert; hop may rise or fall)
 * - incoming.ts == stored.ts && incoming.hops < stored.hops -> adopt (closer copy of this broadcast)
 * - else -> no-op
 *
 * A null incoming timestamp is "no grouping signal": adopted only while the stored timestamp is also
 * null. Once a timestamp is stored, an ungrouped incoming read is a no-op, its ordering unknowable.
 */
fun adoptInboundHop(
    storedHops: Int?,
    storedTimestamp: Long?,
    incomingHops: Int,
    incomingTimestamp: Long?,
): AdoptedInboundHop? {
    if (storedTimestamp == null) return AdoptedInboundHop(incomingHops, incomingTimestamp)
    if (incomingTimestamp == null) return null
    if (incomingTimestamp > storedTimestamp) return AdoptedInboundHop(incomingHops, incomingTimestamp)
    if (incomingTimestamp == storedTimestamp && incomingHops < (storedHops ?: Int.MAX_VALUE)) {
        return AdoptedInboundHop(incomingHops, incomingTimestamp)
    }
    return null
}

/**
 * Merges any hop buffered before a node row existed into the row being upserted. The advert frame that
 * builds the row never carries inbound hops, so [storedHops]/[storedTimestamp] are the existing row's
 * values (or null for a fresh insert); [drained] is the buffered reading. Returns the pair to persist.
 */
fun resolveUpsertInboundHop(storedHops: Int?, storedTimestamp: Long?, drained: AdoptedInboundHop?): Pair<Int?, Long?> {
    drained ?: return storedHops to storedTimestamp
    val adopted = adoptInboundHop(storedHops, storedTimestamp, drained.hopCount, drained.advertTimestamp)
        ?: return storedHops to storedTimestamp
    return adopted.hopCount to adopted.advertTimestamp
}

/**
 * The advert frame never carries inbound hops, so an upsert keeps the existing row's id and carries
 * that row's hop forward, including a reading buffered before the row existed.
 */
fun DiscoveredNodeRecord.withCarriedInboundHop(
    existingId: Uuid?,
    storedHops: Int?,
    storedTimestamp: Long?,
    drained: AdoptedInboundHop?,
): DiscoveredNodeRecord {
    val (inboundHops, inboundTimestamp) = resolveUpsertInboundHop(storedHops, storedTimestamp, drained)
    return copy(
        id = existingId ?: id,
        inboundHopCount = inboundHops,
        inboundHopAdvertTimestamp = inboundTimestamp,
    )
}

/**
 * Bounded buffer for inbound advert hops heard via the RX log before the matching node row exists.
 * The firmware emits the ADVERT RX packet before the advert push that creates the row, so a keyed
 * write would otherwise miss on first contact. Drained by the upsert when the row lands. Not
 * thread-safe on its own; callers hold the store's mutex.
 */
class PendingInboundHopBuffer(private val maxEntries: Int = MAX_PENDING_INBOUND_HOPS) {
    private class Entry(val hopCount: Int, val advertTimestamp: Long?)

    private val pending = LinkedHashMap<String, Entry>()

    private fun key(radioID: Uuid, publicKey: ByteArray): String = "$radioID:${publicKey.toUppercaseHex()}"

    /** Buffers a hop heard before its row exists, applying the same adopt rule as a live row. */
    fun buffer(radioID: Uuid, publicKey: ByteArray, hopCount: Int, advertTimestamp: Long?) {
        val k = key(radioID, publicKey)
        val existing = pending[k]
        val adopted = adoptInboundHop(existing?.hopCount, existing?.advertTimestamp, hopCount, advertTimestamp)
            ?: return
        if (existing == null && pending.size >= maxEntries) {
            pending.keys.firstOrNull()?.let { pending.remove(it) }
        }
        pending[k] = Entry(adopted.hopCount, adopted.advertTimestamp)
    }

    /** Removes and returns any hop buffered before the row existed. */
    fun drain(radioID: Uuid, publicKey: ByteArray): AdoptedInboundHop? =
        pending.remove(key(radioID, publicKey))?.let { AdoptedInboundHop(it.hopCount, it.advertTimestamp) }

    companion object {
        const val MAX_PENDING_INBOUND_HOPS = 256
    }
}
