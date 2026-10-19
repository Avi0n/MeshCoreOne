// AndroidOnly: WP-316 Foundation localizedCaseInsensitiveCompare equivalent for CLI session/login/node-list names.
package com.meshcoreone.android.feature.tools.diagnostics.text

import java.text.Collator
import java.util.Locale

/**
 * Foundation `localizedCaseInsensitiveCompare` is an ICU collation at secondary strength: case,
 * width, ligature and canonical-form differences are equal; accents and dotless/dotted i are
 * not. `java.text.Collator` at SECONDARY strength with full decomposition gives the same equality
 * on the oracle pairs; on Android it is ICU-backed, so ordering also follows ICU there.
 */
internal class LocalizedCompare(locale: Locale) {
    private val collator: Collator = Collator.getInstance(locale).apply {
        strength = Collator.SECONDARY
        decomposition = Collator.FULL_DECOMPOSITION
    }

    /** `a.localizedCaseInsensitiveCompare(b) == .orderedSame`. */
    fun equal(left: String, right: String): Boolean = synchronized(collator) { collator.compare(left, right) == 0 }

    /** Ascending `localizedCaseInsensitiveCompare` ordering; equal names keep their input order. */
    val comparator: Comparator<String> = Comparator { left, right -> synchronized(collator) { collator.compare(left, right) } }
}
