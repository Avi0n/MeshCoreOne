package one.meshcore.shared.tools

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.chat.RegionMatchResult
import one.meshcore.shared.chat.RegionScopeSemantics
import one.meshcore.shared.chat.pathdetail.PathEncoding
import one.meshcore.shared.chat.ui.TimestampFormatter
import one.meshcore.shared.contact.displayName
import one.meshcore.shared.map.SnrQuality
import one.meshcore.shared.map.snrQuality
import one.meshcore.shared.model.RouteType
import one.meshcore.shared.model.toUppercaseHex

/**
 * Pure display helpers for Tools → RX Log row expand, group-duplicates, path labels, and DM from/to.
 * Mirrors the computed properties on iOS `RxLogEntry` / `RxLogRowView`.
 */

/** Bytes per hop from the encoded [RxLogEntry.pathLength], defaulting to 1. */
val RxLogEntry.pathHashSize: Int
    get() = PathEncoding.decodePathLen(pathLength)?.hashSize ?: 1

/** Hop count from the encoded [RxLogEntry.pathLength]. */
val RxLogEntry.hopCount: Int
    get() = PathEncoding.decodePathLen(pathLength)?.hopCount ?: 0

/** SNR quality bucket for signal bars / color. */
val RxLogEntry.entrySnrQuality: SnrQuality
    get() = snrQuality(snr)

/** Whether this is a direct-route text message (DM) eligible for from/to display. */
val RxLogEntry.isDirectTextMessage: Boolean
    get() {
        if (payloadType != RxLogPayloadType.TEXT_MESSAGE) return false
        val route = RouteType.fromRaw(routeType)
        return route == RouteType.DIRECT || route == RouteType.TC_DIRECT
    }

/**
 * Sender public-key prefix for direct text messages (`packetPayload[1]`). Null when not a DM or
 * the payload is too short. Mirrors iOS `RxLogEntry.senderPrefix`.
 */
val RxLogEntry.senderPrefix: ByteArray?
    get() {
        if (!isDirectTextMessage) return null
        if (packetPayload.size < DM_PREFIX_PAIR_BYTES) return null
        return byteArrayOf(packetPayload[DM_SRC_HASH_OFFSET])
    }

/**
 * Recipient public-key prefix for direct text messages (`packetPayload[0]`). Null when not a DM or
 * the payload is too short. Mirrors iOS `RxLogEntry.recipientPrefix`.
 */
val RxLogEntry.recipientPrefix: ByteArray?
    get() {
        if (!isDirectTextMessage) return null
        if (packetPayload.size < DM_PREFIX_PAIR_BYTES) return null
        return byteArrayOf(packetPayload[DM_DEST_HASH_OFFSET])
    }

/** Channel-hash byte for successfully decrypted group traffic (first byte of packet payload). */
val RxLogEntry.channelHashByte: Int?
    get() {
        if (decryptStatus != DecryptStatus.SUCCESS) return null
        if (channelIndex == null) return null
        if (packetPayload.isEmpty()) return null
        return packetPayload[0].toInt() and BYTE_MASK
    }

/**
 * Space-separated uppercase hex of [RxLogEntry.rawPayload] for the expand "Raw Payload" section. The
 * separator is part of the copied text, so a payload pasted from either platform reads the same.
 */
fun RxLogEntry.rawPayloadHex(): String = rawPayload.joinToString(HEX_BYTE_SEPARATOR) { byte ->
    byteArrayOf(byte).toUppercaseHex()
}

/**
 * Wire names of the payload types, mirroring iOS `PayloadType.displayName`. Protocol identifiers, not
 * translated copy, so they read the same in every locale and on both platforms.
 */
private val PAYLOAD_TYPE_NAMES: Map<Int, String> = mapOf(
    RxLogPayloadType.REQUEST to "REQUEST",
    RxLogPayloadType.RESPONSE to "RESPONSE",
    RxLogPayloadType.TEXT_MESSAGE to "TEXT_MSG",
    RxLogPayloadType.ACK to "ACK",
    RxLogPayloadType.ADVERT to "ADVERT",
    RxLogPayloadType.GROUP_TEXT to "GROUP_TEXT",
    RxLogPayloadType.GROUP_DATA to "GROUP_DATA",
    RxLogPayloadType.ANON_REQUEST to "ANON_REQ",
    RxLogPayloadType.PATH to "PATH",
    RxLogPayloadType.TRACE to "TRACE",
    RxLogPayloadType.MULTIPART to "MULTIPART",
    RxLogPayloadType.CONTROL to "CONTROL",
    RxLogPayloadType.RAW_CUSTOM to "RAW_CUSTOM",
)

private const val UNKNOWN_PAYLOAD_TYPE_NAME = "UNKNOWN"

/** Wire name of this packet's payload type for the row preview and the expanded Type row. */
val RxLogEntry.payloadTypeName: String
    get() = PAYLOAD_TYPE_NAMES[payloadType] ?: UNKNOWN_PAYLOAD_TYPE_NAME

/**
 * TRACE target hashes from the payload (tag/auth/flags then hop hashes), or null when not a TRACE
 * packet / too short / misaligned. Mirrors iOS traceTargetHashes.
 */
fun RxLogEntry.traceTargetHashes(): List<ByteArray>? {
    if (payloadType != RxLogPayloadType.TRACE) return null
    if (packetPayload.size <= TRACE_HASHES_OFFSET) return null
    val pathSz = packetPayload[TRACE_FLAGS_OFFSET].toInt() and TRACE_HASH_SIZE_MASK
    val hashSize = 1 shl pathSz
    val hashBytes = packetPayload.copyOfRange(TRACE_HASHES_OFFSET, packetPayload.size)
    if (hashBytes.isEmpty() || hashBytes.size % hashSize != 0) return null
    return (0 until hashBytes.size / hashSize).map { i ->
        hashBytes.copyOfRange(i * hashSize, (i + 1) * hashSize)
    }
}

/**
 * Per-hop hex id strings for the path (or TRACE route). When [localPublicKeyPrefix] matches a hop
 * chunk, the entry is [youLabel] instead of hex. Mirrors iOS hopIdParts / traceRouteIdParts.
 */
fun RxLogEntry.pathHopLabels(localPublicKeyPrefix: ByteArray?, youLabel: String): List<String> {
    if (payloadType == RxLogPayloadType.TRACE) {
        val targets = traceTargetHashes() ?: return emptyList()
        return targets.map { chunk ->
            if (localPublicKeyPrefix != null && localPublicKeyPrefix.startsWithBytes(chunk)) {
                youLabel
            } else {
                chunk.toUppercaseHex()
            }
        }
    }
    if (pathNodes.isEmpty()) return emptyList()
    val hashSize = pathHashSize
    return PathEncoding.pathHops(pathNodes, hashSize).map { hop ->
        if (localPublicKeyPrefix != null && localPublicKeyPrefix.startsWithBytes(hop.raw)) {
            youLabel
        } else {
            hop.hex
        }
    }
}

/**
 * Resolve a public-key prefix to [youLabel], a contact name from [nodeNames], or uppercase hex.
 * [nodeNames] keys are uppercase hex of the prefix bytes.
 */
fun resolveHashLabel(
    hashBytes: ByteArray,
    localPublicKeyPrefix: ByteArray?,
    nodeNames: Map<String, String>,
    youLabel: String,
): String {
    if (localPublicKeyPrefix != null && localPublicKeyPrefix.startsWithBytes(hashBytes)) {
        return youLabel
    }
    val hex = hashBytes.toUppercaseHex()
    return nodeNames[hex] ?: hex
}

/**
 * Prefix→name map for RX Log DM and path-hop resolution, keyed by uppercase hex of 1-, 2-, and 3-byte
 * public-key prefixes. A prefix is included only when it uniquely identifies a single contact, mirroring
 * the Swift `RxLogViewModel.buildNodeNameMap`. Ambiguous prefixes fall back to hex in [resolveHashLabel].
 */
fun buildRxLogNodeNames(contacts: List<ContactDto>): Map<String, String> {
    val map = mutableMapOf<String, String>()
    for (prefixLength in 1..NODE_NAME_MAX_PREFIX) {
        val counts = mutableMapOf<String, Pair<String, Int>>()
        for (contact in contacts) {
            if (contact.publicKey.size < prefixLength) continue
            val prefix = contact.publicKey.copyOfRange(0, prefixLength).toUppercaseHex()
            val existing = counts[prefix]
            counts[prefix] = if (existing == null) {
                contact.displayName to 1
            } else {
                existing.first to (existing.second + 1)
            }
        }
        for ((prefix, entry) in counts) {
            if (entry.second == 1) map[prefix] = entry.first
        }
    }
    return map
}

private const val NODE_NAME_MAX_PREFIX = 3

/**
 * Flood-region detail value. Unique uses the name; ambiguous joins the match list; none is
 * [unresolved]. Mirrors iOS `RegionScopeSemantics.coalesce` on the RX-log expand row.
 */
fun rxLogRegionLabel(scope: String?, matches: List<String>, unresolved: String): String =
    when (val match = RegionScopeSemantics.coalesce(scope, matches)) {
        RegionMatchResult.None -> unresolved
        is RegionMatchResult.Unique -> match.name
        is RegionMatchResult.Ambiguous -> match.names.joinToString(RX_LOG_REGION_JOIN)
    }

private const val RX_LOG_REGION_JOIN = ", "

/**
 * Local calendar date and clock for a logged packet's receive time, for the collapsed row. The iOS row
 * shows `MM/dd HH:mm:ss`; the seconds are load-bearing, since repeated packets of the same hash arrive
 * within one minute and the log exists to order them.
 */
internal fun rxLogReceivedLabel(receivedAtEpochMs: Long, formatter: TimestampFormatter): String {
    val epochSeconds = (receivedAtEpochMs.toDouble() / MILLIS_PER_SECOND).toLong()
    val date = formatter.date(epochSeconds)
    val clock = formatter.clockWithSeconds(epochSeconds)
    return if (date != null && clock != null) "$date $clock" else formatter.unavailable
}

private const val MILLIS_PER_SECOND = 1000.0

/**
 * Count of entries sharing each [RxLogEntry.packetHash] (for the ×N badge when grouping).
 * Mirrors iOS `RxLogViewModel.groupCounts`.
 */
fun rxLogGroupCounts(entries: List<RxLogEntry>): Map<String, Int> {
    if (entries.isEmpty()) return emptyMap()
    val counts = mutableMapOf<String, Int>()
    for (entry in entries) {
        counts[entry.packetHash] = (counts[entry.packetHash] ?: 0) + 1
    }
    return counts
}

/**
 * When [groupDuplicates] is true, keep only the first (newest) row per [RxLogEntry.packetHash].
 * Mirrors iOS `displayEntries`.
 */
fun displayRxLogEntries(entries: List<RxLogEntry>, groupDuplicates: Boolean): List<RxLogEntry> {
    if (!groupDuplicates) return entries
    val seen = mutableSetOf<String>()
    return entries.filter { entry ->
        if (entry.packetHash in seen) {
            false
        } else {
            seen += entry.packetHash
            true
        }
    }
}

/** True when [prefix] starts with the same bytes as [chunk] (hop-size comparison). */
private fun ByteArray.startsWithBytes(chunk: ByteArray): Boolean {
    if (chunk.isEmpty() || size < chunk.size) return false
    for (i in chunk.indices) {
        if (this[i] != chunk[i]) return false
    }
    return true
}

private const val HEX_BYTE_SEPARATOR = " "
private const val DM_DEST_HASH_OFFSET = 0
private const val DM_SRC_HASH_OFFSET = 1
private const val DM_PREFIX_PAIR_BYTES = 2
private const val BYTE_MASK = 0xFF
private const val TRACE_FLAGS_OFFSET = 8
private const val TRACE_HASHES_OFFSET = 9
private const val TRACE_HASH_SIZE_MASK = 0x03
