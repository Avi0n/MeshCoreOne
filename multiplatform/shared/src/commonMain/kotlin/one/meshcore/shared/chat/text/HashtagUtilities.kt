package one.meshcore.shared.chat.text

/**
 * Detection and normalization for hashtag channel references. Ported from the iOS `HashtagUtilities`; the
 * pattern and validity rules are the shared contract the linkifier (styling), the deep link, and the join
 * flow all read, so a name one accepts another cannot reject.
 */
object HashtagUtilities {
    /** Starts with `#` then an alphanumeric, followed by alphanumerics or hyphens. */
    const val PATTERN: String = "#[A-Za-z0-9][A-Za-z0-9-]*"

    private val hashtagRegex = Regex(PATTERN)

    /** A detected hashtag and its inclusive char range in the source text. [name] includes the `#`. */
    data class DetectedHashtag(val name: String, val range: IntRange)

    /**
     * Every valid hashtag in [text], skipping any match that falls fully inside one of [urlRanges] (so a
     * `#fragment` inside a URL is not a hashtag). [urlRanges] are inclusive char ranges, precomputed by
     * the shared URL scan so the detector does not re-scan for links.
     */
    fun extractHashtags(text: String, urlRanges: List<IntRange>): List<DetectedHashtag> {
        if (text.isEmpty()) return emptyList()
        return hashtagRegex.findAll(text).mapNotNull { match ->
            val range = match.range
            val insideUrl = urlRanges.any { range.first >= it.first && range.last <= it.last }
            if (insideUrl) null else DetectedHashtag(name = match.value, range = range)
        }.toList()
    }

    /**
     * Whether [name] (without a `#`) is a valid channel name: the first char is alphanumeric (no leading
     * hyphen) and every char is alphanumeric or a hyphen.
     */
    fun isValidHashtagName(name: String): Boolean {
        val first = name.firstOrNull() ?: return false
        if (!isAllowedNameChar(first, allowsHyphen = false)) return false
        return name.all { isAllowedNameChar(it, allowsHyphen = true) }
    }

    /**
     * Lowercases [input], keeps only alphanumerics and hyphens, and strips leading hyphens. The live
     * sanitizer for the join text field, so the value the user sees can always seed a valid channel key.
     */
    fun sanitizeHashtagNameInput(input: String): String = input.lowercase()
        .filter { isAllowedNameChar(it, allowsHyphen = true) }
        .trimStart('-')

    /** Lowercases [name] and drops a leading `#`. */
    fun normalizeHashtagName(name: String): String = name.lowercase().removePrefix("#")

    private fun isAllowedNameChar(char: Char, allowsHyphen: Boolean): Boolean = when (char) {
        in '0'..'9', in 'A'..'Z', in 'a'..'z' -> true
        '-' -> allowsHyphen
        else -> false
    }
}
