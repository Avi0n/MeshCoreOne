// AndroidOnly: WP-316 Expected values printed by the swiftc oracle (docs/android/evidence/WP-316/oracle.swift.txt).
package com.meshcoreone.android.feature.tools.diagnostics.text

import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class SwiftTextParityTest {
    @Test
    fun `CharacterSet whitespaces and whitespacesAndNewlines membership`() {
        val horizontal = listOf(0x09, 0x20, 0xA0, 0x1680, 0x2000, 0x2007, 0x200A, 0x200B, 0x202F, 0x205F, 0x3000)
        horizontal.forEach { assertEquals("", SwiftStrings.trimmingWhitespaces(it.toChar().toString()), "U+%04X".format(it)) }
        listOf(0x0A, 0x0D, 0x85, 0x2028, 0xFEFF, 0x180E).forEach {
            assertEquals(1, SwiftStrings.trimmingWhitespaces(it.toChar().toString()).length, "U+%04X".format(it))
        }
        listOf(0x0A, 0x0B, 0x0C, 0x0D, 0x85, 0x2028, 0x2029).forEach {
            assertEquals("", SwiftStrings.trimmingWhitespacesAndNewlines(it.toChar().toString()), "U+%04X".format(it))
        }
        assertEquals("a b", SwiftStrings.trimmingWhitespaces("　a b\t"))
    }

    @Test
    fun `lowercased maps per scalar without final sigma`() {
        assertEquals("i̇", SwiftStrings.lowercased("İ"))
        assertEquals("σασ", SwiftStrings.lowercased("ΣΑΣ"))
        assertEquals("straße", SwiftStrings.lowercased("Straße"))
        assertEquals("ǆ", SwiftStrings.lowercased("ǅ"))
    }

    @Test
    fun `split follows Swift maxSplits and empty-subsequence rules`() {
        assertEquals(listOf("a", "", "b", ""), SwiftStrings.split("a  b ", ' ', omittingEmptySubsequences = false))
        assertEquals(listOf("a", " b "), SwiftStrings.split("  a  b ", ' ', maxSplits = 1))
        assertEquals(listOf("a", "  b  c"), SwiftStrings.split("a   b  c", ' ', maxSplits = 1))
        assertEquals(listOf("a"), SwiftStrings.split("a ", ' ', maxSplits = 1))
        assertEquals(listOf("a", "  "), SwiftStrings.split("a   ", ' ', maxSplits = 1))
        assertEquals(4, SwiftStrings.split(",1,2,3,4", ',').size)
    }

    @Test
    fun `string order is Unicode scalar order after NFC`() {
        val input = listOf("b", "B", "a", "Ａ", "😀", "", "é", "éf", "Z", "_")
        val expected = listOf("B", "Z", "_", "a", "b", "é", "éf", "", "Ａ", "😀")
        assertEquals(expected, SwiftStrings.sorted(input))
    }

    @Test
    fun `grapheme counting and dropping match Swift Character semantics`() {
        assertEquals(1, SwiftStrings.characterCount("😀"))
        assertEquals("", SwiftStrings.droppingFirstCharacters("é", 1))
        assertEquals("x", SwiftStrings.droppingFirstCharacters("éx", 1))
    }

    @Test
    fun `Double, Int and UInt8 parsing match the Swift initializers`() {
        val doubles = mapOf(
            "1" to 1.0, "+1" to 1.0, "1e3" to 1000.0, "0x10" to 16.0, "0x1p3" to 8.0, ".5" to 0.5, "5." to 5.0,
            "0X1P-2" to 0.25, "+.5e-1" to 0.05, "00012" to 12.0, "0x.8" to 0.5, "0x1." to 1.0, "1.e5" to 100000.0,
            "4.9e-325" to 0.0,
        )
        doubles.forEach { (text, value) -> assertEquals(value, SwiftNumbers.parseDouble(text), text) }
        listOf(" 1", "1 ", "1.0f", "1d", "1_000", "١", "1,5", "", "-", "e5", "1e", "nan(", "0x", "0x1p", "1e+", "..5", "infinite")
            .forEach { assertNull(SwiftNumbers.parseDouble(it), it) }
        listOf("inf", "Inf", "infinity", "+inf", "INFINITY").forEach { assertEquals(Double.POSITIVE_INFINITY, SwiftNumbers.parseDouble(it), it) }
        assertEquals(Double.NEGATIVE_INFINITY, SwiftNumbers.parseDouble("-Infinity"))
        listOf("nan", "NaN", "-NaN", "nan()", "nan(abc_1)", "nan(1)").forEach { assertTrue(SwiftNumbers.parseDouble(it)?.isNaN() == true, it) }
        assertEquals(Double.POSITIVE_INFINITY, SwiftNumbers.parseDouble("1.7976931348623159e308"))

        assertEquals(9_223_372_036_854_775_807L, SwiftNumbers.parseInt("9223372036854775807"))
        assertEquals(Long.MIN_VALUE, SwiftNumbers.parseInt("-9223372036854775808"))
        assertEquals(7L, SwiftNumbers.parseInt("007"))
        assertEquals(0L, SwiftNumbers.parseInt("-0"))
        listOf(" 1", "1 ", "٣", "0x10", "99999999999999999999", "1.0", "", "-", "+").forEach { assertNull(SwiftNumbers.parseInt(it), it) }

        assertEquals(0.toUByte(), SwiftNumbers.parseUInt8("-0"))
        assertEquals(1.toUByte(), SwiftNumbers.parseUInt8("+1"))
        assertEquals(255.toUByte(), SwiftNumbers.parseUInt8("255"))
        listOf("-1", "256", " 1", "٣").forEach { assertNull(SwiftNumbers.parseUInt8(it), it) }
    }

    @Test
    fun `printf fixed formatting keeps C spellings`() {
        assertEquals("nan", SwiftNumbers.printfFixed(Double.NaN, 3))
        assertEquals("inf", SwiftNumbers.printfFixed(Double.POSITIVE_INFINITY, 3))
        assertEquals("-inf", SwiftNumbers.printfFixed(Double.NEGATIVE_INFINITY, 3))
        assertEquals("-0.000000", SwiftNumbers.printfFixed(-0.0, 6))
    }

    @Test
    fun `localized case-insensitive equality matches Foundation`() {
        val compare = LocalizedCompare(Locale.US)
        val pairs = listOf(
            "abc" to "ABC", "straße" to "STRASSE", "é" to "é", "Ａ" to "A", "node 1" to "NODE 1",
            "ǆ" to "Ǆ", "ﬁ" to "FI",
        )
        pairs.forEach { (a, b) -> assertTrue(compare.equal(a, b), "$a|$b") }
        listOf("é" to "e", "ı" to "I", "İ" to "i").forEach { (a, b) -> assertFalse(compare.equal(a, b), "$a|$b") }
    }
}
