package one.meshcore.shared.trace

import one.meshcore.shared.model.toUppercaseHex

/**
 * One hop in a completed trace result. [hashBytes] is `null` for the start/end node (the local device,
 * which transmitted rather than being heard); [snr] is the received SNR in dB (0 for the start node).
 * [latitude]/[longitude] are the resolved node location when known. Mirrors the Swift `TraceHop`.
 *
 * SNR-quality classification is intentionally not modeled here: the shared `snrQuality` classifier
 * already buckets an SNR value, so the UI layer calls it directly rather than duplicating it on the hop.
 */
data class TraceHop(
    val hashBytes: ByteArray?,
    val resolvedName: String?,
    val snr: Double,
    val isStartNode: Boolean,
    val isEndNode: Boolean,
    val latitude: Double? = null,
    val longitude: Double? = null,
) {
    /** Uppercase-hex of every hash byte, or `null` for the local-device start/end node. */
    val hashDisplayString: String?
        get() = hashBytes?.toUppercaseHex()

    /**
     * Whether this hop has a usable (non-zero) location. OR logic mirrors `ContactDto.hasLocation`:
     * `(0, 0)` is Null Island and treated as "no location".
     */
    val hasLocation: Boolean
        get() {
            val lat = latitude ?: return false
            val lon = longitude ?: return false
            return lat != 0.0 || lon != 0.0
        }

    override fun equals(other: Any?): Boolean = this === other ||
        (
            other is TraceHop &&
                hashBytes.contentEqualsNullable(other.hashBytes) &&
                resolvedName == other.resolvedName &&
                snr == other.snr &&
                isStartNode == other.isStartNode &&
                isEndNode == other.isEndNode &&
                latitude == other.latitude &&
                longitude == other.longitude
            )

    override fun hashCode(): Int {
        var result = hashBytes?.contentHashCode() ?: 0
        result = 31 * result + (resolvedName?.hashCode() ?: 0)
        result = 31 * result + snr.hashCode()
        result = 31 * result + isStartNode.hashCode()
        result = 31 * result + isEndNode.hashCode()
        result = 31 * result + (latitude?.hashCode() ?: 0)
        result = 31 * result + (longitude?.hashCode() ?: 0)
        return result
    }
}
