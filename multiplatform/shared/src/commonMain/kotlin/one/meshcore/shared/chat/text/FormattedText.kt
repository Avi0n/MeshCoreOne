package one.meshcore.shared.chat.text

/**
 * The framework-free rich-text model for a chat message body: the normalized display string plus the
 * sorted, non-overlapping [spans] a Compose renderer turns into an `AnnotatedString`. The shared layer
 * carries no UI colour type, so per-span styling (colour, underline, bold, self-mention background) is
 * derived by the renderer from [TextSpan.kind]; only the structural facts a detector froze — the range,
 * the kind, the tap target, and the resolved mention name — live here.
 *
 * The Kotlin analogue of the iOS linkifier's authored `AttributedString`. Value-equal over every field so
 * it drives list diffing: two payloads with identical spans are equal and the row is not rebuilt.
 */
data class FormattedText(val displayString: String, val spans: List<TextSpan>)

/**
 * The kinds a linkified span can be, in their fixed overlap-resolution priority (declaration order =
 * priority, [CONTACT_SHARE] highest). When two detections cover overlapping characters the
 * higher-priority kind wins and the lower is dropped, so a merged span list is non-overlapping. The
 * order encodes the resolutions: a contact chip or mention shadows any link/hashtag/coordinate inside its
 * run; a URL shadows a hashtag or coordinate it contains; and a hashtag sitting inside a `meshcore://`
 * link wins over that link ([HASHTAG] < [MESHCORE_LINK]), so the link is left unstyled and only the
 * hashtag is styled.
 */
enum class LinkKind {
    CONTACT_SHARE,
    MENTION,
    URL,
    HASHTAG,
    MESHCORE_LINK,
    COORDINATE,
}

/**
 * One detected, styled span in a normalized message body. [start]/[endExclusive] are UTF-16 char offsets
 * into [FormattedText.displayString] — the same units Compose `AnnotatedString` addresses — so a span
 * range applies verbatim without re-indexing.
 *
 * @property url the link to install, or null when a span is styled but not tappable (a mention whose name
 *   cannot be percent-encoded keeps its colour and underline but no link).
 * @property mentionName the resolved, sanitized display name for a [LinkKind.MENTION], carried so the
 *   renderer can solve the per-name identity colour without re-decoding [url]; null for other kinds.
 * @property isSelfMention true when a mention names the local user, selecting the self-mention background.
 */
data class TextSpan(
    val start: Int,
    val endExclusive: Int,
    val kind: LinkKind,
    val url: String?,
    val mentionName: String? = null,
    val isSelfMention: Boolean = false,
)
