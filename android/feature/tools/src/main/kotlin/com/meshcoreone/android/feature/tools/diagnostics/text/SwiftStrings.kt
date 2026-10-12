// AndroidOnly: WP-316 Swift String/CharacterSet semantics the CLI parser and completion engine depend on (oracle-checked).
package com.meshcoreone.android.feature.tools.diagnostics.text

import java.text.BreakIterator
import java.text.Normalizer

/**
 * Swift/Foundation string behaviors reproduced explicitly instead of relying on JVM defaults
 * (`Char.isWhitespace`, `String.lowercase`, UTF-16 `compareTo`). Every set and rule here was
 * printed from a `swiftc` oracle; see docs/android/evidence/WP-316.
 */
internal object SwiftStrings {
    /** `CharacterSet.whitespaces`: tab, Zs separators and U+200B (no line breaks). */
    private val WHITESPACES: Set<Int> = buildSet {
        add(0x09); add(0x20); add(0xA0); add(0x1680)
        addAll(0x2000..0x200B)
        add(0x202F); add(0x205F); add(0x3000)
    }

    /** `CharacterSet.whitespacesAndNewlines`: [WHITESPACES] plus LF, VT, FF, CR, NEL, LS, PS. */
    private val WHITESPACES_AND_NEWLINES: Set<Int> = WHITESPACES + setOf(0x0A, 0x0B, 0x0C, 0x0D, 0x85, 0x2028, 0x2029)

    private const val DOTTED_CAPITAL_I = 0x0130

    fun trimmingWhitespaces(value: String): String = value.trim { it.code in WHITESPACES }

    fun trimmingWhitespacesAndNewlines(value: String): String = value.trim { it.code in WHITESPACES_AND_NEWLINES }

    /**
     * Swift `String.lowercased()`: per-scalar Unicode lowercase with the one unconditional
     * multi-scalar mapping (U+0130 to "i" + U+0307) and none of the context rules (no final sigma),
     * unlike `String.lowercase(Locale.ROOT)`.
     */
    fun lowercased(value: String): String {
        val result = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            if (codePoint == DOTTED_CAPITAL_I) {
                result.append('i').append('̇')
            } else {
                result.appendCodePoint(Character.toLowerCase(codePoint))
            }
            index += Character.charCount(codePoint)
        }
        return result.toString()
    }

    /**
     * Swift `split(separator:maxSplits:omittingEmptySubsequences:)` for a single-character
     * separator. With `maxSplits` reached, the unsplit remainder is kept unless it is empty and
     * empties are omitted.
     */
    fun split(
        value: String,
        separator: Char,
        maxSplits: Int = Int.MAX_VALUE,
        omittingEmptySubsequences: Boolean = true,
    ): List<String> {
        val pieces = mutableListOf<String>()
        var start = 0
        var index = 0
        while (index < value.length && pieces.size < maxSplits) {
            if (value[index] == separator) {
                val piece = value.substring(start, index)
                if (!omittingEmptySubsequences || piece.isNotEmpty()) pieces += piece
                start = index + 1
            }
            index++
        }
        val remainder = value.substring(start)
        if (!omittingEmptySubsequences || remainder.isNotEmpty()) pieces += remainder
        return pieces
    }

    /**
     * Swift `String` `<` ordering: canonical equivalence (compared in NFC) and Unicode scalar
     * order, so supplementary characters sort after U+E000..U+FFFF unlike UTF-16 `compareTo`.
     */
    val ORDER: Comparator<String> = Comparator { left, right -> compareScalars(nfc(left), nfc(right)) }

    fun sorted(values: Iterable<String>): List<String> = values.sortedWith(ORDER)

    fun hasPrefix(value: String, prefix: String): Boolean = nfc(value).startsWith(nfc(prefix))

    fun distinct(values: Iterable<String>): List<String> = values.distinctBy(::nfc)

    /** Swift `Character` (extended grapheme cluster) count. */
    fun characterCount(value: String): Int {
        val iterator = BreakIterator.getCharacterInstance()
        iterator.setText(value)
        var count = 0
        while (iterator.next() != BreakIterator.DONE) count++
        return count
    }

    /** Swift `dropFirst(n)` over `Character`s. */
    fun droppingFirstCharacters(value: String, count: Int): String {
        if (count <= 0) return value
        val iterator = BreakIterator.getCharacterInstance()
        iterator.setText(value)
        var boundary = 0
        repeat(count) {
            val next = iterator.next()
            if (next == BreakIterator.DONE) return ""
            boundary = next
        }
        return value.substring(boundary)
    }

    private fun nfc(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFC)

    private fun compareScalars(left: String, right: String): Int {
        var leftIndex = 0
        var rightIndex = 0
        while (leftIndex < left.length && rightIndex < right.length) {
            val leftPoint = left.codePointAt(leftIndex)
            val rightPoint = right.codePointAt(rightIndex)
            if (leftPoint != rightPoint) return leftPoint.compareTo(rightPoint)
            leftIndex += Character.charCount(leftPoint)
            rightIndex += Character.charCount(rightPoint)
        }
        return (left.length - leftIndex).compareTo(right.length - rightIndex)
    }
}
