package one.meshcore.shared.meshcore

/**
 * One received packet to attribute to a flood region. [transportCode] is the header field verbatim
 * and [payloadTypeBits] the raw nibble: how many bytes of the field are read, and which bits of the
 * nibble matter, is the protocol implementation's business.
 */
class RegionResolveRow(val payload: ByteArray, val payloadTypeBits: Int, val transportCode: ByteArray)

/**
 * One row's unique name (or null) plus every matching name. Ambiguous rows keep [uniqueName] null
 * and a 2+ [matches] list so a chip can show the collision instead of Unknown.
 */
data class RegionResolveHit(val uniqueName: String?, val matches: List<String> = emptyList()) {
    companion object {
        val NONE = RegionResolveHit(uniqueName = null, matches = emptyList())

        fun unique(name: String): RegionResolveHit = RegionResolveHit(name, listOf(name))
    }
}

/**
 * The RX log's window onto MeshCore's flood-region matching.
 *
 * A packet names its region only through `transport_codes[0]`, an HMAC keyed by a scope key derived
 * from the region name. Both the derivation and the match live below the bridge, so no scope key is
 * ever computed or held above it — the caller passes the region names it knows and gets names back.
 *
 * Rows are submitted as a batch because a region list arriving after packets were logged re-derives
 * the whole held log at once, and one boundary crossing for the batch beats one per row.
 */
fun interface RegionResolver {
    /**
     * Returns one [RegionResolveHit] per row, in submission order. Unique names come back trimmed
     * as they were submitted; none and ambiguous rows have a null [RegionResolveHit.uniqueName].
     */
    fun resolve(regionNames: List<String>, rows: List<RegionResolveRow>): List<RegionResolveHit>
}

private object UnavailableRegionResolver : RegionResolver {
    override fun resolve(regionNames: List<String>, rows: List<RegionResolveRow>): List<RegionResolveHit> =
        rows.map { RegionResolveHit.NONE }
}

internal object PlatformBridge {
    var regionResolver: RegionResolver = UnavailableRegionResolver
    var rxLogDecryptor: RxLogDecryptor = UnavailableRxLogDecryptor
}

/** Forwards to the resolver installed on [PlatformBridge], which a session may set after this cache exists. */
private object LiveRegionResolver : RegionResolver {
    override fun resolve(regionNames: List<String>, rows: List<RegionResolveRow>): List<RegionResolveHit> =
        PlatformBridge.regionResolver.resolve(regionNames, rows)
}

/** The resolver this platform can reach. Hosts without a bridge attribute nothing. */
fun platformRegionResolver(): RegionResolver = LiveRegionResolver
