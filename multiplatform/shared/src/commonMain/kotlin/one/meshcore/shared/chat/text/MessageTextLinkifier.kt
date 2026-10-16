package one.meshcore.shared.chat.text

import one.meshcore.shared.contact.ContactCardUri
import one.meshcore.shared.contact.ContactShareToken
import one.meshcore.shared.contact.contactType
import one.meshcore.shared.map.formatFixed6

/** The linkified message body plus the first coordinate that drives the map preview, if any. */
data class LinkifyResult(val formatted: FormattedText, val firstCoordinate: ChatCoordinate?)

/**
 * The message-body linkifier: rewrites the raw text to a display string and produces the sorted,
 * non-overlapping [TextSpan] list a Compose renderer styles into an `AnnotatedString`. Ported from the
 * iOS `MessageTextNormalizer` + `MessageLinkTokenizer`; a pure, off-thread function keyed only by the raw
 * text and the local user name, so it can run inside the item builder off the main thread.
 *
 * Two stages. **Normalize** rewrites `@[name]` mention tokens to a visible `@name` and `<pubkey:type:name>`
 * contact-share tokens to a sanitized display name, freezing one display string with pre-spans pointing
 * into it, so every later detector runs on one stable string. **Tokenize** runs the URL, meshcore-link,
 * hashtag, and coordinate detectors over the display string, merges them with the pre-spans, and resolves
 * overlaps by the fixed [LinkKind] priority.
 *
 * Parity note: the iOS normalizer sanitizes the contact-share display name but leaves the mention display
 * name raw (sanitization there happens only at mention-tap resolution). This port matches that to preserve
 * iOS parity; see the slice report for the spec-prose divergence.
 */
object MessageTextLinkifier {
    private const val MESHCORE_SCHEME = "meshcore"
    private val meshCoreLinkRegex = Regex("""meshcore://[^\s<>"]+""")
    private const val MESHCORE_TRAILING_STRIP = ".,;:!?)"
    private val meshCoreLinkHosts = setOf("contact", "channel")

    fun linkify(rawText: String, currentUserName: String?): LinkifyResult {
        val normalized = normalize(rawText, currentUserName)
        return tokenize(normalized.string, normalized.preSpans)
    }

    // MARK: - Normalize

    private class Normalized(val string: String, val preSpans: List<TextSpan>)

    private class Replacement(
        val originalRange: IntRange,
        val replacement: String,
        val kind: LinkKind,
        val url: String?,
        val mentionName: String?,
        val isSelfMention: Boolean,
    )

    private fun normalize(text: String, currentUserName: String?): Normalized {
        val contactRanges = Regex(ContactShareToken.PATTERN).findAll(text).map { it.range }.toList()

        val replacements = mutableListOf<Replacement>()
        replacements += mentionReplacements(text, currentUserName, contactRanges)
        replacements += contactShareReplacements(text)
        // A mention can never sit inside a contact token (those are excluded) and contact tokens never
        // overlap each other, so sorting by start yields a clean left-to-right rewrite.
        replacements.sortBy { it.originalRange.first }

        if (replacements.isEmpty()) return Normalized(text, emptyList())

        val builder = StringBuilder()
        val spans = mutableListOf<TextSpan>()
        var cursor = 0
        for (replacement in replacements) {
            builder.append(text, cursor, replacement.originalRange.first)
            val spanStart = builder.length
            builder.append(replacement.replacement)
            spans += TextSpan(
                start = spanStart,
                endExclusive = builder.length,
                kind = replacement.kind,
                url = replacement.url,
                mentionName = replacement.mentionName,
                isSelfMention = replacement.isSelfMention,
            )
            cursor = replacement.originalRange.last + 1
        }
        builder.append(text, cursor, text.length)
        return Normalized(builder.toString(), spans)
    }

    private fun mentionReplacements(
        text: String,
        currentUserName: String?,
        contactRanges: List<IntRange>,
    ): List<Replacement> = Regex(MentionUtilities.MENTION_PATTERN).findAll(text).mapNotNull { match ->
        // A contact share name is attacker-controlled and may itself contain `@[name]`; the
        // contact-share rewrite owns that text, so drop a mention inside one.
        if (contactRanges.any { match.range.first <= it.last && it.first <= match.range.last }) {
            return@mapNotNull null
        }
        val name = match.groupValues[1]
        Replacement(
            originalRange = match.range,
            replacement = "@$name",
            kind = LinkKind.MENTION,
            url = MentionDeeplink.url(name),
            mentionName = name,
            isSelfMention = currentUserName?.let { name.equals(it, ignoreCase = true) } ?: false,
        )
    }.toList()

    private fun contactShareReplacements(text: String): List<Replacement> =
        Regex(ContactShareToken.PATTERN).findAll(text).mapNotNull { match ->
            val card = ContactShareToken.parse(match.value) ?: return@mapNotNull null
            // Sanitize once and carry the cleaned name through both the visible chip and the add-contact
            // link. If sanitizing leaves nothing, keep the literal token rather than an empty chip.
            val cleanName = MentionUtilities.sanitizeDisplayName(card.name)
            if (cleanName.isEmpty()) return@mapNotNull null
            Replacement(
                originalRange = match.range,
                replacement = cleanName,
                kind = LinkKind.CONTACT_SHARE,
                url = ContactCardUri.export(cleanName, card.publicKey, card.contactType),
                mentionName = null,
                isSelfMention = false,
            )
        }.toList()

    // MARK: - Tokenize

    private fun tokenize(normalized: String, preSpans: List<TextSpan>): LinkifyResult {
        val urls = UrlDetector.detect(normalized)
        val coordinateHits = ChatCoordinateDetector.matches(normalized)

        val hits = mutableListOf<TextSpan>()
        hits += preSpans
        hits += urls.map { it.range.toSpan(LinkKind.URL, it.url) }
        hits += meshCoreLinkSpans(normalized)
        hits += HashtagUtilities.extractHashtags(normalized, urls.map { it.range }).mapNotNull { hashtag ->
            val name = HashtagUtilities.normalizeHashtagName(hashtag.name)
            HashtagDeeplink.url(name)?.let { hashtag.range.toSpan(LinkKind.HASHTAG, it) }
        }
        hits += coordinateHits.mapNotNull { hit ->
            mapUrl(hit.coordinate)?.let { hit.range.toSpan(LinkKind.COORDINATE, it) }
        }

        val merged = resolveOverlaps(hits)

        // The map preview follows the first coordinate surviving overlap resolution, in document order; a
        // coordinate shadowed by a higher-priority token does not drive the preview.
        val survivingCoordinateStarts = merged.filter { it.kind == LinkKind.COORDINATE }.map { it.start }.toSet()
        val firstCoordinate = coordinateHits
            .filter { it.range.first in survivingCoordinateStarts }
            .minByOrNull { it.range.first }
            ?.coordinate

        return LinkifyResult(FormattedText(normalized, merged), firstCoordinate)
    }

    private fun meshCoreLinkSpans(text: String): List<TextSpan> = meshCoreLinkRegex.findAll(text).mapNotNull { match ->
        var end = match.range.last
        while (end >= match.range.first && text[end] in MESHCORE_TRAILING_STRIP) end--
        if (end < match.range.first) return@mapNotNull null
        val url = text.substring(match.range.first, end + 1)
        val parts = parseMeshLink(url) ?: return@mapNotNull null
        if (parts.scheme != MESHCORE_SCHEME || parts.host !in meshCoreLinkHosts) return@mapNotNull null
        (match.range.first..end).toSpan(LinkKind.MESHCORE_LINK, url)
    }.toList()

    /**
     * Accepts detections highest priority first, dropping any that overlaps one already accepted, then
     * returns the survivors in document order. Resolving by [LinkKind] priority rather than start index is
     * what lets a higher-priority kind beginning inside a lower one still win: a hashtag inside a
     * `meshcore://` link keeps its styling and leaves the surrounding link unstyled.
     */
    private fun resolveOverlaps(spans: List<TextSpan>): List<TextSpan> {
        val byPriority = spans.sortedWith(compareBy({ it.kind.ordinal }, { it.start }))
        val accepted = mutableListOf<TextSpan>()
        for (span in byPriority) {
            if (accepted.none { it.start < span.endExclusive && span.start < it.endExclusive }) {
                accepted += span
            }
        }
        return accepted.sortedBy { it.start }
    }

    private fun IntRange.toSpan(kind: LinkKind, url: String?): TextSpan =
        TextSpan(start = first, endExclusive = last + 1, kind = kind, url = url)

    /**
     * Builds `meshcore://map?lat=&lon=` with locale-independent 6-decimal values so the link round-trips
     * through the map parser on every locale — a comma-decimal locale's formatter would emit `37,334900`,
     * which the parser's decimal gate rejects.
     */
    private fun mapUrl(coordinate: ChatCoordinate): String? {
        val lat = formatFixed6(coordinate.latitude)
        val lon = formatFixed6(coordinate.longitude)
        return "$MESHCORE_SCHEME://map?lat=$lat&lon=$lon"
    }
}
