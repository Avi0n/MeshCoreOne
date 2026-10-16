package one.meshcore.shared.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The diacritic-fold seam the region resolver normalizes geocoded names through (JVM actual). */
class DiacriticFoldTest {
    @Test
    fun stripsCombiningAccentsFromBaseLetters() {
        assertEquals("cordoba", foldDiacritics("córdoba"))
        assertEquals("uber", foldDiacritics("über"))
        assertEquals("aatlantique", foldDiacritics("aätlantique"))
    }

    @Test
    fun leavesUnaccentedTextUnchanged() {
        assertEquals("nassau", foldDiacritics("nassau"))
    }

    @Test
    fun containsIgnoringCaseAndDiacriticsMatchesAccentsAndTurkishI() {
        assertTrue("José".containsIgnoringCaseAndDiacritics("jose"))
        assertTrue("İstanbul".containsIgnoringCaseAndDiacritics("istanbul"))
        assertFalse("Bob".containsIgnoringCaseAndDiacritics("jose"))
    }
}
