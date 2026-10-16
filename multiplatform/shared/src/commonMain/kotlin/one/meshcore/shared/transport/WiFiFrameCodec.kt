package one.meshcore.shared.transport

// The 2-byte little-endian length field is packed one byte at a time.
private const val LOW_BYTE_MASK = 0xFF
private const val BYTE_WIDTH = 8

/**
 * The WiFi/TCP wire format, ported byte-for-byte from the Swift `WiFiFrameCodec`. MeshCore's WiFi
 * companion protocol is length-prefixed, unlike BLE (where each notification is already a whole frame):
 *
 * - Outbound (app to device): `<` (0x3C) + 2-byte length (little-endian) + payload.
 * - Inbound (device to app): `>` (0x3E) + 2-byte length (little-endian) + payload.
 *
 * A mismatch here is a protocol break, so this stays a faithful port: the same delimiters, the same
 * little-endian 2-byte length, and the same stateful buffered decode as the Swift `WiFiFrameDecoder`.
 */
object WiFiFrameCodec {
    /** Outbound frame delimiter (app to device). */
    const val OUTBOUND_DELIMITER: Int = 0x3C // '<'

    /** Inbound frame delimiter (device to app). */
    const val INBOUND_DELIMITER: Int = 0x3E // '>'

    /** Header size: delimiter (1) + length (2). */
    const val HEADER_SIZE: Int = 3

    /** Largest payload the 2-byte length field can describe. */
    const val MAX_PAYLOAD_SIZE: Int = 0xFFFF

    /**
     * Encodes a payload for transmission to the device: `<` + 2-byte little-endian length + payload.
     * The Swift codec traps if the payload exceeds the 2-byte length field; this rejects it explicitly.
     */
    fun encode(payload: ByteArray): ByteArray {
        require(payload.size <= MAX_PAYLOAD_SIZE) {
            "payload of ${payload.size} bytes exceeds the $MAX_PAYLOAD_SIZE-byte frame length field"
        }
        val frame = ByteArray(HEADER_SIZE + payload.size)
        frame[0] = OUTBOUND_DELIMITER.toByte()
        frame[1] = (payload.size and LOW_BYTE_MASK).toByte() // low byte
        frame[2] = ((payload.size ushr BYTE_WIDTH) and LOW_BYTE_MASK).toByte() // high byte
        payload.copyInto(frame, HEADER_SIZE)
        return frame
    }
}

/**
 * Stateful decoder for the inbound WiFi stream. TCP delivers arbitrary byte chunks, so this buffers
 * across reads: it skips any leading garbage before the delimiter, waits for a full header, then waits
 * for the full length-prefixed payload before emitting a frame. Mirrors the Swift `WiFiFrameDecoder`.
 *
 * Not thread-safe: drive it from a single receive loop, exactly as the transport does.
 */
class WiFiFrameDecoder {
    private var buffer = ByteArray(0)

    /** Appends [data], then returns every complete frame now available; partials stay buffered. */
    fun decode(data: ByteArray): List<ByteArray> {
        buffer += data
        val frames = mutableListOf<ByteArray>()
        while (true) {
            val frame = extractFrame() ?: break
            frames += frame
        }
        return frames
    }

    /** Extracts a single complete frame from the front of the buffer, or null if none is ready yet. */
    private fun extractFrame(): ByteArray? {
        dropBytesBeforeDelimiter()

        // Need at least the header.
        if (buffer.size < WiFiFrameCodec.HEADER_SIZE) return null

        // Read the little-endian length, then wait for the complete frame.
        val length = (buffer[1].toInt() and LOW_BYTE_MASK) or ((buffer[2].toInt() and LOW_BYTE_MASK) shl BYTE_WIDTH)
        val totalFrameSize = WiFiFrameCodec.HEADER_SIZE + length
        return if (buffer.size < totalFrameSize) {
            null
        } else {
            val payload = buffer.copyOfRange(WiFiFrameCodec.HEADER_SIZE, totalFrameSize)
            buffer = buffer.copyOfRange(totalFrameSize, buffer.size)
            payload
        }
    }

    /** Discards any leading bytes until the buffer starts with the inbound delimiter. */
    private fun dropBytesBeforeDelimiter() {
        var start = 0
        while (start < buffer.size && buffer[start] != WiFiFrameCodec.INBOUND_DELIMITER.toByte()) {
            start++
        }
        if (start > 0) buffer = buffer.copyOfRange(start, buffer.size)
    }

    /** Clears the internal buffer (on disconnect/reconnect). */
    fun reset() {
        buffer = ByteArray(0)
    }
}
