package one.meshcore.shared.meshcore

/**
 * Stands in for the bridge region resolver in pure-JVM tests.
 *
 * It attributes a row by looking its transport code up in a table it was handed, never by hashing
 * anything. That is the point: a region name can only reach a test through this seam, so moving the
 * HMAC back above the bridge makes these tests fail rather than quietly keep passing.
 */
class FakeRegionResolver(
    private val regionByCode: Map<Int, String> = emptyMap(),
    private val matchesByCode: Map<Int, List<String>> = emptyMap(),
) : RegionResolver {
    /** Batches submitted, so a test can prove a whole re-derivation pass crosses the boundary once. */
    var batchCount: Int = 0
        private set

    /** Rows submitted across every batch. */
    var rowCount: Int = 0
        private set

    /** The region names of the most recent batch, as the caller submitted them. */
    var lastRegionNames: List<String> = emptyList()
        private set

    override fun resolve(regionNames: List<String>, rows: List<RegionResolveRow>): List<RegionResolveHit> {
        batchCount++
        rowCount += rows.size
        lastRegionNames = regionNames
        return rows.map { row ->
            val code = codeOf(row.transportCode)
            val matches = matchesByCode[code]?.filter { it in regionNames }
            if (matches != null) {
                when (matches.size) {
                    0 -> RegionResolveHit.NONE
                    1 -> RegionResolveHit.unique(matches[0])
                    else -> RegionResolveHit(uniqueName = null, matches = matches)
                }
            } else {
                val name = regionByCode[code]?.takeIf { it in regionNames }
                if (name == null) RegionResolveHit.NONE else RegionResolveHit.unique(name)
            }
        }
    }

    private fun codeOf(transportCode: ByteArray): Int = when {
        transportCode.size < TRANSPORT_CODE_BYTES -> NO_CODE
        else -> (transportCode[0].toInt() and BYTE_MASK) or ((transportCode[1].toInt() and BYTE_MASK) shl SHIFT_8)
    }

    private companion object {
        const val TRANSPORT_CODE_BYTES = 2
        const val BYTE_MASK = 0xFF
        const val SHIFT_8 = 8
        const val NO_CODE = -1
    }
}
