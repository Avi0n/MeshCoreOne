// AndroidOnly: WP-316 Foundation localizedCaseInsensitiveCompare equivalent for CLI session/login/node-list names.
package com.meshcoreone.android.feature.tools.diagnostics.text

import android.icu.lang.UCharacter
import java.text.Collator
import java.text.Normalizer
import java.util.Locale

/**
 * Foundation's retained comparison vectors require full case folding before accent-sensitive
 * collation: Android SECONDARY strength alone distinguishes sharp-s from ss. Default (non-Turkic)
 * ICU folding preserves dotless i and the combining dot in dotted I. Turkish/Azeri casing is
 * resolved by ICU before decomposition, preserving their locale-tailored I equivalences.
 * NFKD comparison views retain
 * width/ligature equivalence without Android's unsupported FULL_DECOMPOSITION mode.
 * Caller-owned names retain their original spelling.
 */
internal class LocalizedCompare(locale: Locale) {
    private val turkicLocale = locale.takeIf { it.language == "tr" || it.language == "az" }
    private val collator: Collator = Collator.getInstance(locale).apply {
        strength = Collator.SECONDARY
        decomposition = Collator.CANONICAL_DECOMPOSITION
    }

    /** `a.localizedCaseInsensitiveCompare(b) == .orderedSame`. */
    fun equal(left: String, right: String): Boolean = compare(left, right) == 0

    /** Ascending `localizedCaseInsensitiveCompare` ordering; equal names keep their input order. */
    val comparator: Comparator<String> = Comparator(::compare)

    private fun compare(left: String, right: String): Int = synchronized(collator) {
        collator.compare(
            comparisonView(left),
            comparisonView(right),
        )
    }

    private fun comparisonView(value: String): String {
        val cased = turkicLocale?.let { UCharacter.toLowerCase(it, value) } ?: value
        return Normalizer.normalize(
            UCharacter.foldCase(Normalizer.normalize(cased, Normalizer.Form.NFKD), UCharacter.FOLD_CASE_DEFAULT),
            Normalizer.Form.NFKD,
        )
    }
}
