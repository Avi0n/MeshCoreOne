package one.meshcore.shared.pathedit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Mirrors the iOS `HopNodeMatching` assertions: hex detection, case- and diacritic-insensitive name
 * matching (the locale-independent Turkish İ/ı fold is the highest-risk port), the hex-prefix branch,
 * and order-preserving filtering.
 */
class HopNodeMatchingTest {
    private fun contact(name: String = "Basecamp", publicKey: ByteArray = PathEditFixtures.key(0xAA)) =
        PickerNode.Contact(PathEditFixtures.contact(name = name, publicKey = publicKey))

    @Test
    fun emptyQueryMatchesEverything() {
        assertTrue(HopNodeMatching.matches(contact(name = "Basecamp"), ""))
    }

    @Test
    fun nameSubstringIsCaseInsensitive() {
        val node = contact(name = "Basecamp North")
        assertTrue(HopNodeMatching.matches(node, "bas"))
        assertTrue(HopNodeMatching.matches(node, "BASE"))
        assertTrue(HopNodeMatching.matches(node, "north"))
        assertFalse(HopNodeMatching.matches(node, "zzz"))
    }

    @Test
    fun turkishDotlessIFoldsCorrectly() {
        // "İstanbul" lowercases (locale-independent) to "i" + combining dot U+0307; the diacritic fold
        // strips that dot so the query "istanbul" matches — the naive lowercase().contains would not.
        val node = contact(name = "İstanbul Relay")
        assertTrue(HopNodeMatching.matches(node, "istanbul"))
    }

    @Test
    fun composedAccentFoldsCorrectly() {
        // Diacritic-insensitive: a composed "ó" (NFC) matches an un-accented query.
        val node = contact(name = "Córdoba")
        assertTrue(HopNodeMatching.matches(node, "cordoba"))
    }

    @Test
    fun hexPrefixMatchesFullPubkey() {
        val node = contact(name = "Unrelated", publicKey = PathEditFixtures.key(0xA3, 0xF2))
        assertTrue(HopNodeMatching.matches(node, "a3f2"))
        assertTrue(HopNodeMatching.matches(node, "A3F2"))
        assertFalse(HopNodeMatching.matches(node, "b7"))
    }

    @Test
    fun allHexQueryMatchesBothNameAndPubkeyHex() {
        val node = contact(name = "A3 Basecamp", publicKey = PathEditFixtures.key(0xA3))
        assertTrue(HopNodeMatching.matches(node, "a3"))
    }

    @Test
    fun nonHexQueryIgnoresPubkey() {
        val node = contact(name = "Summit", publicKey = PathEditFixtures.key(0xF0))
        assertFalse(HopNodeMatching.matches(node, "foo"))
    }

    @Test
    fun isHexQueryDetectsValidHexDigits() {
        assertTrue(HopNodeMatching.isHexQuery("a3f2"))
        assertTrue(HopNodeMatching.isHexQuery("A3F2"))
        assertTrue(HopNodeMatching.isHexQuery("0123456789abcdefABCDEF"))
        assertFalse(HopNodeMatching.isHexQuery("a3z"))
        assertFalse(HopNodeMatching.isHexQuery(""))
    }

    @Test
    fun filteredReturnsAllNodesForEmptyQuery() {
        val nodes = listOf(contact(name = "A"), contact(name = "B"))
        assertEquals(2, HopNodeMatching.filtered(nodes, "").size)
    }

    @Test
    fun filteredKeepsOnlyNameSubstringMatchesForNonHexQuery() {
        val nodes = listOf(contact(name = "Basecamp"), contact(name = "Summit"))
        val result = HopNodeMatching.filtered(nodes, "base")
        assertEquals(1, result.size)
        assertEquals("Basecamp", result[0].displayName)
    }

    @Test
    fun filteredHexQueryMatchesBothNameAndPubkeyHexPrefixBranches() {
        val nodes = listOf(
            contact(name = "Foo", publicKey = PathEditFixtures.key(0xA3)),
            contact(name = "A3 Basecamp", publicKey = PathEditFixtures.key(0x89)),
            contact(name = "Zeta", publicKey = PathEditFixtures.key(0xF0)),
        )
        assertEquals(2, HopNodeMatching.filtered(nodes, "a3").size)
    }
}
