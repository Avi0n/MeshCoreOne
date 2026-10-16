package one.meshcore.shared.pathedit

import one.meshcore.shared.map.containsIgnoringCaseAndDiacritics

/**
 * Query matching for the shared Add-Hop picker, ported from the iOS `HopNodeMatching`. Kept as pure
 * functions so both the picker and the view models reach them without coupling to a concrete VM.
 *
 * Name matching folds case AND diacritics so Turkish İ/ı and NFC/NFD Cyrillic match regardless of
 * runtime locale — the iOS `range(of:options:[.caseInsensitive, .diacriticInsensitive])` behavior.
 * The fold lowercases first (locale-independent: Kotlin's no-arg [String.lowercase] uses the Unicode
 * default, unlike `java.lang.String.toLowerCase()`, so İ → "i̇" not the Turkish "ı"), then strips
 * the combining marks NFD splits accents into (via [foldDiacritics], which also removes the combining
 * dot lowercase produced for İ). Doing it in this order is what makes the İ→i fold survive: the naive
 * `lowercase().contains(...)` would leave the U+0307 dot wedged between "i" and "stanbul".
 */
object HopNodeMatching {
    private const val HEX_RADIX = 16

    /**
     * True when [query] is non-empty and every character is a hex digit. All-digit names like "1234"
     * therefore match both name and pubkey branches — acceptable since both produce the same row.
     */
    fun isHexQuery(query: String): Boolean = query.isNotEmpty() && query.all { it.digitToIntOrNull(HEX_RADIX) != null }

    /**
     * True if [query] is empty, matches the node name as a case/diacritic-insensitive substring, or (for
     * a hex query) prefixes the public-key hex.
     */
    fun matches(node: PickerNode, query: String): Boolean {
        if (query.isEmpty()) return true
        val nameHit = node.displayName.containsIgnoringCaseAndDiacritics(query)
        if (isHexQuery(query)) {
            return nameHit || node.publicKeyHex.lowercase().startsWith(query.lowercase())
        }
        return nameHit
    }

    /** Filter [nodes] to those matching [query], preserving order. */
    fun filtered(nodes: List<PickerNode>, query: String): List<PickerNode> {
        if (query.isEmpty()) return nodes
        return nodes.filter { matches(it, query) }
    }
}
