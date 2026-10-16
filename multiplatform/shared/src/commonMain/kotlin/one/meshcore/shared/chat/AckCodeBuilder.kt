package one.meshcore.shared.chat

import one.meshcore.shared.crypto.sha256

private const val ATTEMPT_MASK = 0x03
private const val ACK_CODE_BYTE_COUNT = 4
private const val MAX_ATTEMPT_INDEX = 5
private const val BYTE_MASK = 0xFFL
private const val BITS_PER_BYTE = 8

/**
 * Computes the expected end-to-end ACK CRC for an outgoing direct message, mirroring firmware
 * `BaseChatMesh::sendMessage`:
 *
 *     sha256( LE32(timestamp) || byte(attempt & 0x03) || text || pubkey )[0..3]
 *
 * Used to populate the pending-ACK table before the send round-trip so the persistent ACK listener
 * cannot race ahead of `trackPendingAck`. The recipient is not in the hash, and the attempt index is
 * masked to two bits (attempt 4 reuses attempt 0's code), so a single message accumulates its
 * per-attempt codes in a set; see the Swift `AckCodeBuilder` for the full firmware-contract notes.
 * Mirrors the Swift `AckCodeBuilder`.
 */
object AckCodeBuilder {
    fun expectedAck(timestamp: Long, attempt: Int, text: String, senderPublicKey: ByteArray): ByteArray {
        require(attempt < MAX_ATTEMPT_INDEX) {
            "attempt $attempt exceeds the index range (4 direct + 1 flood) and would over-wrap the & 0x03 ACK mask"
        }
        val textBytes = text.encodeToByteArray()
        val input = ByteArray(ACK_CODE_BYTE_COUNT + 1 + textBytes.size + senderPublicKey.size)
        var offset = 0
        // LE32(timestamp): the low 32 bits, little-endian, matching the wire value the firmware hashes.
        for (i in 0 until ACK_CODE_BYTE_COUNT) {
            input[offset++] = ((timestamp ushr (BITS_PER_BYTE * i)) and BYTE_MASK).toByte()
        }
        input[offset++] = (attempt and ATTEMPT_MASK).toByte()
        textBytes.copyInto(input, offset)
        offset += textBytes.size
        senderPublicKey.copyInto(input, offset)
        return sha256(input).copyOf(ACK_CODE_BYTE_COUNT)
    }
}

/**
 * The 4-byte ACK code read as a little-endian unsigned integer widened to `Long`. This is the canonical
 * key form for the pending-ACK table and the persisted `ackCode` column, matching the Swift
 * `Data.ackCodeUInt32`.
 */
fun ByteArray.ackCodeLeUInt32(): Long {
    var result = 0L
    val count = minOf(size, ACK_CODE_BYTE_COUNT)
    for (i in 0 until count) {
        result = result or ((this[i].toLong() and BYTE_MASK) shl (BITS_PER_BYTE * i))
    }
    return result
}
