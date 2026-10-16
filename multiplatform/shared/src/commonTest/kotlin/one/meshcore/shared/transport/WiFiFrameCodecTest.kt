package one.meshcore.shared.transport

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Byte-exact port of the Swift `WiFiFrameCodecTests` (all six vectors), plus fragmentation cases the
 * TCP stream makes possible: a frame delivered one byte at a time and garbage before the delimiter must
 * decode identically. This is the wire format, so the vectors match the Swift ones exactly.
 */
class WiFiFrameCodecTest {
    // MARK: - Encode (mirrors the Swift encode tests)

    @Test
    fun encodesFrameWithCorrectDelimiterAndLength() {
        val encoded = WiFiFrameCodec.encode(byteArrayOf(0x01, 0x02, 0x03))
        // Expected: '<' (0x3C) + length (3, 0) little-endian + payload.
        assertContentEquals(byteArrayOf(0x3C, 0x03, 0x00, 0x01, 0x02, 0x03), encoded)
    }

    @Test
    fun encodesEmptyFrame() {
        val encoded = WiFiFrameCodec.encode(ByteArray(0))
        assertContentEquals(byteArrayOf(0x3C, 0x00, 0x00), encoded)
    }

    // MARK: - Decode (mirrors the Swift decode tests)

    @Test
    fun decodesSingleCompleteFrame() {
        val decoder = WiFiFrameDecoder()
        val frames = decoder.decode(byteArrayOf(0x3E, 0x03, 0x00, 0x01, 0x02, 0x03))
        assertEquals(1, frames.size)
        assertContentEquals(byteArrayOf(0x01, 0x02, 0x03), frames[0])
    }

    @Test
    fun decodesMultipleFramesInOneChunk() {
        val decoder = WiFiFrameDecoder()
        val frames = decoder.decode(
            byteArrayOf(
                0x3E, 0x02, 0x00, 0xAA.toByte(), 0xBB.toByte(), // frame 1
                0x3E, 0x01, 0x00, 0xCC.toByte(), // frame 2
            ),
        )
        assertEquals(2, frames.size)
        assertContentEquals(byteArrayOf(0xAA.toByte(), 0xBB.toByte()), frames[0])
        assertContentEquals(byteArrayOf(0xCC.toByte()), frames[1])
    }

    @Test
    fun buffersIncompleteFrame() {
        val decoder = WiFiFrameDecoder()

        // Header only.
        assertTrue(decoder.decode(byteArrayOf(0x3E, 0x05, 0x00)).isEmpty())
        // Partial payload.
        assertTrue(decoder.decode(byteArrayOf(0x01, 0x02)).isEmpty())
        // Complete the frame.
        val frames = decoder.decode(byteArrayOf(0x03, 0x04, 0x05))
        assertEquals(1, frames.size)
        assertContentEquals(byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05), frames[0])
    }

    @Test
    fun handlesFrameSplitAcrossChunks() {
        val decoder = WiFiFrameDecoder()

        assertTrue(decoder.decode(byteArrayOf(0x3E)).isEmpty()) // delimiter only
        assertTrue(decoder.decode(byteArrayOf(0x03, 0x00, 0xAA.toByte())).isEmpty()) // length + partial
        val frames = decoder.decode(byteArrayOf(0xBB.toByte(), 0xCC.toByte())) // rest of payload
        assertEquals(1, frames.size)
        assertContentEquals(byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte()), frames[0])
    }

    // MARK: - Fragmentation cases beyond the Swift vectors

    @Test
    fun decodesFrameFedOneByteAtATime() {
        val decoder = WiFiFrameDecoder()
        val wire = byteArrayOf(0x3E, 0x03, 0x00, 0x0A, 0x0B, 0x0C)
        val frames = mutableListOf<ByteArray>()
        for (b in wire) {
            frames += decoder.decode(byteArrayOf(b))
        }
        assertEquals(1, frames.size)
        assertContentEquals(byteArrayOf(0x0A, 0x0B, 0x0C), frames[0])
    }

    @Test
    fun twoFramesFedOneByteAtATimeDecodeIdentically() {
        val decoder = WiFiFrameDecoder()
        val wire = byteArrayOf(
            0x3E, 0x02, 0x00, 0xAA.toByte(), 0xBB.toByte(),
            0x3E, 0x01, 0x00, 0xCC.toByte(),
        )
        val frames = mutableListOf<ByteArray>()
        for (b in wire) {
            frames += decoder.decode(byteArrayOf(b))
        }
        assertEquals(2, frames.size)
        assertContentEquals(byteArrayOf(0xAA.toByte(), 0xBB.toByte()), frames[0])
        assertContentEquals(byteArrayOf(0xCC.toByte()), frames[1])
    }

    @Test
    fun skipsGarbageBeforeDelimiter() {
        val decoder = WiFiFrameDecoder()
        // Leading bytes that are not the inbound delimiter must be discarded until '>' appears.
        val frames = decoder.decode(
            byteArrayOf(0x00, 0xFF.toByte(), 0x11, 0x3E, 0x02, 0x00, 0xDE.toByte(), 0xAD.toByte()),
        )
        assertEquals(1, frames.size)
        assertContentEquals(byteArrayOf(0xDE.toByte(), 0xAD.toByte()), frames[0])
    }

    @Test
    fun encodeThenDecodeRoundTripsWithInboundDelimiter() {
        // A payload framed for send, re-delimited as inbound, decodes back to the same bytes.
        val payload = byteArrayOf(0x10, 0x20, 0x30, 0x40)
        val encoded = WiFiFrameCodec.encode(payload)
        // Swap the outbound '<' for the inbound '>' to feed the decoder the mirror-direction frame.
        val inbound = encoded.copyOf().also { it[0] = WiFiFrameCodec.INBOUND_DELIMITER.toByte() }
        val frames = WiFiFrameDecoder().decode(inbound)
        assertEquals(1, frames.size)
        assertContentEquals(payload, frames[0])
    }
}
