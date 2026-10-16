package one.meshcore.shared.settings

import one.meshcore.shared.chat.pathdetail.PathEncoding
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The node-config path limits must be the shared [PathEncoding] codec, not a second copy of it. An
 * unclamped hop count overflows out of the low 6 bits into the hash-size mode, so the byte the planner
 * would write back describes a different route width than the path bytes it accompanies.
 */
class NodeConfigLimitsTest {
    @Test
    fun encodePathLenClampsAnOverlongHopCount() {
        val encoded = NodeConfigLimits.encodePathLen(hashSize = 1, hopCount = 64)

        val decoded = assertNotNull(PathEncoding.decodePathLen(encoded.toInt()))
        assertEquals(1, decoded.hashSize, "hop overflow must not bleed into the hash-size mode")
        assertEquals(PathEncoding.MAX_HOP_COUNT, decoded.hopCount)
    }

    @Test
    fun encodePathLenAgreesWithTheSharedCodecOverEveryValidPair() {
        for (hashSize in 1..3) {
            for (hopCount in 0..PathEncoding.MAX_HOP_COUNT) {
                assertEquals(
                    PathEncoding.encodePathLen(hashSize, hopCount).toUByte(),
                    NodeConfigLimits.encodePathLen(hashSize, hopCount),
                    "hashSize=$hashSize hopCount=$hopCount",
                )
            }
        }
    }

    @Test
    fun pathLimitsAreTheSharedCodecConstants() {
        assertEquals(PathEncoding.MAX_PATH_HASH_MODE, NodeConfigLimits.MAX_PATH_HASH_MODE)
        assertEquals(PathEncoding.MAX_HOP_COUNT, NodeConfigLimits.MAX_HOP_COUNT)
        assertEquals(PathEncoding.MAX_PATH_BYTES, NodeConfigLimits.MAX_PATH_BYTES)
    }
}
