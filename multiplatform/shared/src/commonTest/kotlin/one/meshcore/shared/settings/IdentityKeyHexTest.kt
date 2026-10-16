package one.meshcore.shared.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A validly RFC 8032-clamped 64-byte expanded private key: byte 0 = 0xF8 (low 3 bits clear),
 * byte 31 = 0x40 (high bit clear, second-highest set); the rest is arbitrary filler.
 */
private val VALID_EXPANDED_KEY_HEX = "f8" + "11".repeat(30) + "40" + "22".repeat(32)

class IdentityKeyHexTest {
    @Test
    fun `valid 128 hex chars parse to a clamped 64-byte expanded key`() {
        val bytes = parsePrivateKeyHex(VALID_EXPANDED_KEY_HEX)
        assertTrue(bytes != null && bytes.size == 64)
        assertEquals(0xF8.toByte(), bytes!![0])
        assertEquals(0x40.toByte(), bytes[31])
    }

    @Test
    fun groupedPasteKeepsOnlyUppercaseHexDigits() {
        val grouped = VALID_EXPANDED_KEY_HEX.chunked(2).joinToString(":")
        val sanitized = sanitizePrivateKeyHexInput(grouped)
        assertEquals(VALID_EXPANDED_KEY_HEX.uppercase(), sanitized)
        assertTrue(parsePrivateKeyHex(sanitized) != null)
    }

    @Test
    fun `0x prefix and whitespace are accepted`() {
        val hex = "0x$VALID_EXPANDED_KEY_HEX"
        assertTrue(parsePrivateKeyHex("  $hex  ") != null)
    }

    @Test
    fun `wrong length is rejected`() {
        assertNull(parsePrivateKeyHex("aabb"))
        // A bare 32-byte seed (the old, wrong accepted length) must not validate anymore:
        // the wire/export format is the 64-byte expanded key.
        assertNull(parsePrivateKeyHex("11".repeat(32)))
        assertNull(parsePrivateKeyHex(VALID_EXPANDED_KEY_HEX.dropLast(2)))
    }

    @Test
    fun `non-hex is rejected`() {
        assertNull(parsePrivateKeyHex("zz".repeat(64)))
    }

    @Test
    fun `unclamped scalar is rejected despite correct length`() {
        // Byte 0's low bits set (0x07 not clear) violates RFC 8032 clamping.
        val unclamped = "07" + VALID_EXPANDED_KEY_HEX.drop(2)
        assertNull(parsePrivateKeyHex(unclamped))
    }
}
