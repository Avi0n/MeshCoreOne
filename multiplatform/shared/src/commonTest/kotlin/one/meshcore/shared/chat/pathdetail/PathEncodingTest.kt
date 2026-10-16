package one.meshcore.shared.chat.pathdetail

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Mirrors the routing path-length codec expectations from the MeshCore `PathEncoding`: mode/hop packing,
 * the reserved mode returning null, encode/decode round-trips, hop-count clamping, and the per-hop hex
 * chunking driven by hash size.
 */
class PathEncodingTest {
    @Test
    fun decodeModeZeroIsOneByteHashes() {
        val decoded = PathEncoding.decodePathLen(0x05) // mode 0, 5 hops
        assertEquals(1, decoded?.hashSize)
        assertEquals(5, decoded?.hopCount)
        assertEquals(5, decoded?.byteLength)
    }

    @Test
    fun decodeModeOneIsTwoByteHashes() {
        val decoded = PathEncoding.decodePathLen(0x43) // mode 1, 3 hops
        assertEquals(2, decoded?.hashSize)
        assertEquals(3, decoded?.hopCount)
        assertEquals(6, decoded?.byteLength)
    }

    @Test
    fun decodeModeTwoIsThreeByteHashes() {
        val decoded = PathEncoding.decodePathLen(0x82) // mode 2, 2 hops
        assertEquals(3, decoded?.hashSize)
        assertEquals(2, decoded?.hopCount)
        assertEquals(6, decoded?.byteLength)
    }

    @Test
    fun reservedModeThreeDecodesToNull() {
        assertNull(PathEncoding.decodePathLen(0xC0)) // mode 3 reserved
        assertNull(PathEncoding.decodePathLen(0xFF)) // the no-path sentinel is also mode 3
    }

    @Test
    fun encodeIsTheInverseOfDecode() {
        for (hashSize in 1..3) {
            for (hopCount in intArrayOf(0, 1, 7, PathEncoding.MAX_HOP_COUNT)) {
                val encoded = PathEncoding.encodePathLen(hashSize, hopCount)
                val decoded = PathEncoding.decodePathLen(encoded)
                assertEquals(hashSize, decoded?.hashSize)
                assertEquals(hopCount, decoded?.hopCount)
            }
        }
    }

    @Test
    fun encodeClampsHopCountToMax() {
        val encoded = PathEncoding.encodePathLen(hashSize = 1, hopCount = 100)
        assertEquals(PathEncoding.MAX_HOP_COUNT, PathEncoding.decodePathLen(encoded)?.hopCount)
    }

    @Test
    fun pathHopsChunksByHashSize() {
        val bytes = byteArrayOf(0xA3.toByte(), 0x7F, 0x42)
        val hops = PathEncoding.pathHops(bytes, hashSize = 1)
        assertEquals(listOf("A3", "7F", "42"), hops.map { it.hex })
        assertTrue(hops[0].raw.contentEquals(byteArrayOf(0xA3.toByte())))
    }

    @Test
    fun pathHopsChunksTwoByteHashes() {
        val bytes = byteArrayOf(0xA3.toByte(), 0x7F, 0x42, 0x11)
        val hops = PathEncoding.pathHops(bytes, hashSize = 2)
        assertEquals(listOf("A37F", "4211"), hops.map { it.hex })
    }

    @Test
    fun pathHopsReturnsEmptyForNonPositiveHashSize() {
        assertTrue(PathEncoding.pathHops(byteArrayOf(1, 2, 3), hashSize = 0).isEmpty())
    }

    @Test
    fun pathHopsEmitsTrailingPartialChunk() {
        val bytes = byteArrayOf(0xA3.toByte(), 0x7F, 0x42)
        val hops = PathEncoding.pathHops(bytes, hashSize = 2)
        assertEquals(listOf("A37F", "42"), hops.map { it.hex })
    }

    @Test
    fun constantsMatchTheProtocol() {
        assertEquals(2, PathEncoding.MAX_PATH_HASH_MODE)
        assertEquals(63, PathEncoding.MAX_HOP_COUNT)
        assertEquals(64, PathEncoding.MAX_PATH_BYTES)
    }
}
