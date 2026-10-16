package one.meshcore.shared.chat.text

/** A detected web link and its inclusive char range in the source text (trailing punctuation excluded). */
data class DetectedUrl(val range: IntRange, val url: String)

/**
 * Hand-written `http`/`https` link detector, the Android stand-in for iOS `NSDataDetector`. iOS detects
 * every link type then keeps only http/https (mailto/tel are dropped — this app has no phone or mail
 * links). Scheme-less hosts (`example.com`) are inferred as `http://`, matching `NSDataDetector`. One scan
 * feeds both the URL spans and the ranges the hashtag detector uses to skip a `#fragment` inside a URL.
 *
 * Trailing sentence punctuation is excluded from the match so "see https://example.com." links only the
 * URL. A closing bracket is trimmed only when unbalanced, so a URL that legitimately ends in `)` (a
 * Wikipedia article) keeps it — the same boundary behaviour `NSDataDetector` applies.
 */
object UrlDetector {
    private val urlRegex = Regex("""(?i)https?://[^\s<>"]+""")

    /**
     * A dotted host with a letter TLD, optional `www.`, port, path, query, or fragment. Lookbehind
     * refuses a host that is already a scheme URL or an email (`ftp://x.com`, `a@b.com`).
     */
    private val schemeLessRegex = Regex(
        """(?i)(?<![@/:.\w-])(?:www\.)?""" +
            """(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,}""" +
            """(?::\d{1,5})?(?:[/?#][^\s<>"]*)?""",
    )

    // Always-trailing punctuation that cannot be part of a URL boundary.
    private const val TRAILING_PUNCTUATION = ".,;:!?“”‘’'\""

    private val closerToOpener = mapOf(')' to '(', ']' to '[', '}' to '{')

    fun detect(text: String): List<DetectedUrl> {
        val schemeful = matchesOf(urlRegex, text) { it }
        val occupied = schemeful.map { it.range }
        val schemeless = matchesOf(schemeLessRegex, text) { SCHEMELESS_PREFIX + it }
            .filter { candidate -> occupied.none { it.overlaps(candidate.range) } }
        return (schemeful + schemeless).sortedBy { it.range.first }
    }

    private fun matchesOf(pattern: Regex, text: String, toUrl: (String) -> String): List<DetectedUrl> =
        pattern.findAll(text).mapNotNull { match ->
            val trimmed = trimTrailing(match.value)
            if (trimmed.isEmpty()) {
                null
            } else {
                DetectedUrl(
                    range = match.range.first..(match.range.first + trimmed.length - 1),
                    url = toUrl(trimmed),
                )
            }
        }.toList()

    private fun IntRange.overlaps(other: IntRange): Boolean = first <= other.last && other.first <= last

    private fun trimTrailing(url: String): String {
        var end = url.length
        while (end > 0) {
            val c = url[end - 1]
            val opener = closerToOpener[c]
            val strip = when {
                c in TRAILING_PUNCTUATION -> true
                // Trim a closing bracket only when it is unbalanced within the URL.
                opener != null -> url.take(end).count { it == opener } < url.take(end).count { it == c }
                else -> false
            }
            if (!strip) break
            end--
        }
        return url.take(end)
    }

    /** `NSDataDetector` infers `http://` for a host with no scheme. */
    private const val SCHEMELESS_PREFIX = "http://"
}
