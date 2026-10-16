package one.meshcore.shared.chat.linkpreview

import one.meshcore.shared.chat.text.MentionUtilities
import one.meshcore.shared.chat.text.UrlDetector

/**
 * Selects the preview-eligible URL(s) in a message, a verbatim port of the Swift
 * `LinkPreviewService.extractAllURLs`/`extractFirstURL`. Three rules, in order:
 *
 * - a meshcore-open `g:{id}` short-code is the whole (trimmed) message and expands to a direct Giphy URL;
 * - otherwise the [UrlDetector] http/https spans are taken (it already restricts schemes and trims
 *   trailing sentence punctuation, the Android stand-in for `NSDataDetector`);
 * - a URL whose span overlaps an `@[mention]` token is dropped, so a link inside a mention name never
 *   triggers a fetch.
 *
 * This is the fetch trigger's front half: [extractFirst] is the single URL a message's preview/inline-image
 * fragment resolves against, matching the iOS `extractFirstURL` the per-message cache lookup keys on.
 */
object LinkPreviewUrlExtractor {
    private val giphyShortCode = Regex("""g:([A-Za-z0-9_-]+)""")
    private val mentionRegex = Regex(MentionUtilities.MENTION_PATTERN)

    /** The first preview-eligible URL in [text], or null when the message carries none. */
    fun extractFirst(text: String): String? = extractAll(text).firstOrNull()

    /** Every preview-eligible URL in [text], in document order. */
    fun extractAll(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        expandGiphy(text)?.let { return listOf(it) }

        val mentionRanges = mentionRegex.findAll(text).map { it.range }.toList()
        return UrlDetector.detect(text)
            .filterNot { detected -> mentionRanges.any { it.overlaps(detected.range) } }
            .map { it.url }
    }

    /**
     * Expands a `g:{id}` short-code that is the entire trimmed message into its direct Giphy URL, mirroring
     * `extractGiphyGIFURL`. Returns null when the message is not solely a short-code.
     */
    fun expandGiphy(text: String): String? {
        val match = giphyShortCode.matchEntire(text.trim()) ?: return null
        return "https://media.giphy.com/media/${match.groupValues[1]}/giphy.gif"
    }

    private fun IntRange.overlaps(other: IntRange): Boolean = first <= other.last && other.first <= last
}
