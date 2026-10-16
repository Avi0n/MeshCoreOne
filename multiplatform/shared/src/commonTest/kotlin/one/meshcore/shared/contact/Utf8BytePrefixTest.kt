package one.meshcore.shared.contact

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Boundary tests for the shared [String.utf8BytePrefix] helper (the single copy the parser, the channel
 * service, and the Discover importer all cap names with). A cap landing inside a multi-byte character must
 * back up to the previous full character rather than split the encoding.
 */
class Utf8BytePrefixTest {
    @Test
    fun returns_the_whole_string_when_it_fits() {
        assertEquals("café", "café".utf8BytePrefix(5))
        assertEquals("abc", "abc".utf8BytePrefix(3))
    }

    @Test
    fun cap_inside_a_two_byte_character_truncates_to_the_previous_full_character() {
        // "café" is 5 UTF-8 bytes: c a f then é (0xC3 0xA9). A 4-byte cap lands on é's trailing byte.
        assertEquals("caf", "café".utf8BytePrefix(4))
    }

    @Test
    fun cap_inside_a_three_byte_character_truncates_to_the_previous_full_character() {
        // "a你" is 4 UTF-8 bytes: a then 你 (0xE4 0xBD 0xA0). Any cap of 1..3 keeps only "a".
        assertEquals("a", "a你".utf8BytePrefix(3))
        assertEquals("a", "a你".utf8BytePrefix(2))
        assertEquals("a", "a你".utf8BytePrefix(1))
    }

    @Test
    fun cap_on_a_character_boundary_keeps_the_completed_character() {
        assertEquals("café", "caféX".utf8BytePrefix(5))
    }

    @Test
    fun a_cap_that_splits_the_first_character_yields_empty() {
        assertEquals("", "你".utf8BytePrefix(2))
    }
}
