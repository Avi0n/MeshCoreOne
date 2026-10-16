package one.meshcore.shared.trace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Byte-layout parity for the trace path: mirror construction, chunked display, rebuild, and reconstruction. */
class TracePathCodecTest {
    private fun hop(vararg bytes: Int, publicKey: ByteArray? = null) =
        PathHop(hashBytes = bytes.map { it.toByte() }.toByteArray(), publicKey = publicKey)

    @Test
    fun autoReturnMirrorsPathDroppingTurnaroundHop() {
        val outbound = listOf(hop(0xA), hop(0xB), hop(0xC))
        val full = buildFullPathBytes(outbound, autoReturnPath = true)
        // A B C then reversed minus the turnaround (C): B A -> A B C B A
        assertEquals(listOf(0xA, 0xB, 0xC, 0xB, 0xA), full.map { it.toInt() and 0xFF })
    }

    @Test
    fun withoutAutoReturnKeepsOutboundOnly() {
        val outbound = listOf(hop(0xA), hop(0xB), hop(0xC))
        val full = buildFullPathBytes(outbound, autoReturnPath = false)
        assertEquals(listOf(0xA, 0xB, 0xC), full.map { it.toInt() and 0xFF })
    }

    @Test
    fun emptyOutboundYieldsEmptyBuffer() {
        assertEquals(0, buildFullPathBytes(emptyList(), autoReturnPath = true).size)
    }

    @Test
    fun chunkedHexPathGroupsByHashSize() {
        val bytes = byteArrayOf(0x31, 0xA7.toByte(), 0x4F, 0x09)
        assertEquals("31A7,4F09", chunkedHexPath(bytes, hashSize = 2))
        assertEquals("31,A7,4F,09", chunkedHexPath(bytes, hashSize = 1))
        assertEquals("31A74F09", chunkedHexPath(bytes, hashSize = 4))
    }

    @Test
    fun rebuildToWiderWidthReprefixesFromPublicKeyAndZeroPadsKeyless() {
        val keyed = hop(0x31, publicKey = byteArrayOf(0x31, 0xA7.toByte(), 0x4F, 0x09, 0x11))
        val keyless = hop(0x42)
        val rebuilt = rebuildHopsToWidth(listOf(keyed, keyless), hashSize = 4)
        // Keyed hop takes the first 4 bytes of its public key.
        assertEquals(listOf(0x31, 0xA7, 0x4F, 0x09), rebuilt[0].hashBytes.map { it.toInt() and 0xFF })
        // Keyless hop widens from its single byte and zero-pads.
        assertEquals(listOf(0x42, 0x00, 0x00, 0x00), rebuilt[1].hashBytes.map { it.toInt() and 0xFF })
    }

    @Test
    fun rebuildToNarrowerWidthTruncates() {
        val keyed = hop(0x31, 0xA7, 0x4F, 0x09, publicKey = byteArrayOf(0x31, 0xA7.toByte(), 0x4F, 0x09))
        val rebuilt = rebuildHopsToWidth(listOf(keyed), hashSize = 1)
        assertEquals(listOf(0x31), rebuilt[0].hashBytes.map { it.toInt() and 0xFF })
    }

    @Test
    fun outboundHopCountRoundsUpTheMirroredPath() {
        // Outbound [A,B,C] at 2 bytes/hop -> full A B C B A = 5 hops = 10 bytes; outbound half = 3.
        assertEquals(3, outboundHopCount(totalBytes = 10, hashSize = 2))
        // Single-hop path (no mirror hop dropped): 1 hop -> outbound 1.
        assertEquals(1, outboundHopCount(totalBytes = 2, hashSize = 2))
        // Two-hop outbound -> A B A = 3 hops; outbound half rounds up to 2.
        assertEquals(2, outboundHopCount(totalBytes = 3, hashSize = 1))
    }

    @Test
    fun outboundReconstructionRoundTripsThroughSavedFullPath() {
        val outbound = listOf(hop(0x31, 0xA7), hop(0x4F, 0x09), hop(0x11, 0x22))
        val full = buildFullPathBytes(outbound, autoReturnPath = true)
        val slices = outboundHashSlices(full, hashSize = 2)
        assertEquals(outbound.size, slices.size)
        slices.forEachIndexed { index, slice ->
            assertTrue(slice.contentEquals(outbound[index].hashBytes))
        }
    }
}
