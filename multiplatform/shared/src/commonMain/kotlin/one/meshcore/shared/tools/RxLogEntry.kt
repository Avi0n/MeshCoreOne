package one.meshcore.shared.tools

import one.meshcore.shared.model.RouteType
import one.meshcore.shared.model.Uuid

/**
 * One displayed RX log row. Volatile (not backed up); mirrors the load-bearing fields of iOS
 * `RxLogEntry` used by the Tools → RX Log list, including region and decrypt outcomes.
 *
 * [rawPayload] / [packetPayload] / [pathNodes] support row expand, raw-hex copy, path hop labels,
 * and DM from/to resolution. They are not backed up.
 *
 * [decodedText] is derived, never durable: a decrypted body is re-derived from the payload each time
 * a row is loaded, so plaintext traffic never reaches disk.
 */
data class RxLogEntry(
    val id: Uuid,
    val radioID: Uuid,
    val receivedAtEpochMs: Long,
    val snr: Double?,
    val rssi: Int?,
    val routeType: Int,
    val payloadType: Int,
    val pathLength: Int,
    val packetHash: String,
    val packetPayloadSize: Int,
    val decryptStatus: DecryptStatus = DecryptStatus.NOT_APPLICABLE,
    val regionScope: String? = null,
    val regionScopeMatches: List<String> = emptyList(),
    val channelIndex: Int? = null,
    val channelName: String? = null,
    val decodedText: String? = null,
    val senderTimestamp: Long? = null,
    val rawPayload: ByteArray = ByteArray(0),
    val packetPayload: ByteArray = ByteArray(0),
    val pathNodes: ByteArray = ByteArray(0),
    val payloadVersion: Int = 0,
    /** `transport_codes[0]` bytes from the header, kept so region resolution can be replayed on back-fill. */
    val transportCode: ByteArray? = null,
    /**
     * Raw 4-bit payload-type nibble from the header, which is the firmware HMAC input. Kept separately
     * because reserved nibbles do not survive the mapping into [payloadType].
     */
    val payloadTypeBits: Int = 0,
) {
    val isFlood: Boolean
        get() {
            val route = RouteType.fromRaw(routeType)
            return route == RouteType.FLOOD || route == RouteType.TC_FLOOD
        }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RxLogEntry) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}

/** App-only cap for the live RX log buffer. Older rows drop when exceeded (newest-first). */
const val MAX_RX_LOG_ENTRIES: Int = 1000

/**
 * Rows a radio partition may run past [MAX_RX_LOG_ENTRIES] before a durable store prunes back down to
 * the cap, mirroring the iOS `pruneRxLogEntries` threshold. Amortizing the prune keeps a delete pass
 * off the hot receive path without letting the log grow without bound.
 */
const val RX_LOG_PRUNE_THRESHOLD: Int = 100
