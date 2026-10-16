package one.meshcore.shared.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VanityPrefixTest {
    @Test
    fun `valid short prefixes are ok`() {
        assertEquals(VanityPrefix.Validation.Ok, VanityPrefix.validate("a"))
        assertEquals(VanityPrefix.Validation.Ok, VanityPrefix.validate("2BA1"))
    }

    @Test
    fun `empty and long prefixes fail length`() {
        assertEquals(VanityPrefix.Validation.InvalidLength, VanityPrefix.validate(""))
        assertEquals(VanityPrefix.Validation.InvalidLength, VanityPrefix.validate("abcde"))
    }

    @Test
    fun `reserved prefixes are rejected`() {
        assertEquals(VanityPrefix.Validation.Reserved, VanityPrefix.validate("00"))
        assertEquals(VanityPrefix.Validation.Reserved, VanityPrefix.validate("FF"))
        assertEquals(VanityPrefix.Validation.Reserved, VanityPrefix.validate("00A1"))
        assertEquals(VanityPrefix.Validation.Reserved, VanityPrefix.validate("FFB2"))
    }

    @Test
    fun `single hex digit 0 or F is not reserved`() {
        assertEquals(VanityPrefix.Validation.Ok, VanityPrefix.validate("0"))
        assertEquals(VanityPrefix.Validation.Ok, VanityPrefix.validate("F"))
    }

    @Test
    fun sanitizeKeepsAsciiHexAndDropsUnicodeDigits() {
        assertEquals("2BA1", VanityPrefix.sanitize("2b:a1-zz"))
        assertEquals("ABCD", VanityPrefix.sanitize("abcde"))
        assertEquals("A", VanityPrefix.sanitize("A\u0661"))
    }

    @Test
    fun `non-hex fails`() {
        assertEquals(VanityPrefix.Validation.InvalidHex, VanityPrefix.validate("zz"))
    }

    @Test
    fun `matchesPrefix is case-insensitive`() {
        assertTrue(VanityPrefix.matchesPrefix("2ba1deadbeef", "2BA1"))
        assertFalse(VanityPrefix.matchesPrefix("aabb", "2BA1"))
    }
}
