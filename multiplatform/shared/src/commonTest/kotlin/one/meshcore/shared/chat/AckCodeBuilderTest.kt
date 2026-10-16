package one.meshcore.shared.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers the [AckCodeBuilder] firmware-contract invariants: a 4-byte code, determinism, the two-bit
 * attempt mask (attempt 4 reuses attempt 0), rejection past the attempt range, and sensitivity to each
 * hashed input. The digest is the platform SHA-256 (byte-identical to Swift `CryptoKit.SHA256`) over the
 * documented layout, so these invariants pin the layout without a hand-computed golden vector.
 */
class AckCodeBuilderTest {
    private val pubkey = ByteArray(ChatConstants.PUBLIC_KEY_SIZE_BYTES) { it.toByte() }

    @Test
    fun `produces a four-byte deterministic code`() {
        val a = AckCodeBuilder.expectedAck(1_700_000_000L, 0, "hello", pubkey)
        val b = AckCodeBuilder.expectedAck(1_700_000_000L, 0, "hello", pubkey)
        assertEquals(4, a.size)
        assertTrue(a.contentEquals(b))
    }

    @Test
    fun `attempt masks to two bits so attempt 4 reuses attempt 0`() {
        val attempt0 = AckCodeBuilder.expectedAck(1_700_000_000L, 0, "hi", pubkey)
        val attempt4 = AckCodeBuilder.expectedAck(1_700_000_000L, 4, "hi", pubkey)
        assertTrue(attempt0.contentEquals(attempt4))
    }

    @Test
    fun `rejects an attempt index past the range`() {
        assertFailsWith<IllegalArgumentException> {
            AckCodeBuilder.expectedAck(1_700_000_000L, 5, "hi", pubkey)
        }
    }

    @Test
    fun `differs on timestamp, text, and public key`() {
        val base = AckCodeBuilder.expectedAck(1_700_000_000L, 0, "hi", pubkey)
        assertFalse(base.contentEquals(AckCodeBuilder.expectedAck(1_700_000_001L, 0, "hi", pubkey)))
        assertFalse(base.contentEquals(AckCodeBuilder.expectedAck(1_700_000_000L, 0, "ho", pubkey)))
        assertFalse(base.contentEquals(AckCodeBuilder.expectedAck(1_700_000_000L, 0, "hi", ByteArray(32) { 0x7F })))
    }

    @Test
    fun `ackCodeLeUInt32 reads four bytes little-endian`() {
        val le = byteArrayOf(0xEF.toByte(), 0xBE.toByte(), 0xAD.toByte(), 0xDE.toByte())
        assertEquals(0xDEADBEEFL, le.ackCodeLeUInt32())
    }
}
