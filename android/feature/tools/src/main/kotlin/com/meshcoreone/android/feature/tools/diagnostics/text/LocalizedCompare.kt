// AndroidOnly: WP-316 Foundation localizedCaseInsensitiveCompare equivalent for CLI session/login/node-list names.
package com.meshcoreone.android.feature.tools.diagnostics.text

import java.text.Collator
import java.text.Normalizer
import java.util.Locale

/**
 * Foundation `localizedCaseInsensitiveCompare` is an ICU collation at secondary strength: case,
 * width, ligature and canonical-form differences are equal; accents and dotless/dotted i are
 * not. Compatibility decomposition is applied to comparison views, since Android's ICU bridge
 * rejects FULL_DECOMPOSITION. The collator uses supported canonical decomposition and SECONDARY
 * strength; caller-owned names retain their original spelling.
 */
internal class LocalizedCompare(locale: Locale) {
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
            Normalizer.normalize(left, Normalizer.Form.NFKD),
            Normalizer.normalize(right, Normalizer.Form.NFKD),
        )
    }
}
