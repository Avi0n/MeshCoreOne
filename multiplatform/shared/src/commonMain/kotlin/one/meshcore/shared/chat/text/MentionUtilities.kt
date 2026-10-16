package one.meshcore.shared.chat.text

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.contact.contactType
import one.meshcore.shared.contact.displayName
import one.meshcore.shared.map.containsIgnoringCaseAndDiacritics
import one.meshcore.shared.model.ContactType

/**
 * The MeshCore mention wire format `@[nodeContactName]` and its pure helpers. Ported from the iOS
 * `MentionUtilities`; the detection, extraction, and self-mention rules are the shared contract the
 * normalizer, the ingest self-mention hook, and the composer autocomplete all read from, so they can
 * never disagree about what a mention is.
 *
 * `NSRegularExpression`/ICU behaviours are bridged to `kotlin.text.Regex`: the mention pattern is plain
 * and identical; `localizedStandardContains` is [containsIgnoringCaseAndDiacritics].
 */
object MentionUtilities {
    /** The regex pattern for a mention: `@[name]`, capturing the interior name. */
    const val MENTION_PATTERN: String = """@\[([^\]]+)\]"""

    private val mentionRegex = Regex(MENTION_PATTERN)

    private val leadingMentionRegex = Regex("""^@\[[^\]]+\]\s*""")

    /** Length of the quoted preview `buildReplyText` keeps before eliding with `..`. */
    private const val REPLY_PREVIEW_LENGTH = 10

    /** Formats a mesh contact name (not nickname) into the wire mention `@[name]`. */
    fun createMention(name: String): String = "@[$name]"

    /**
     * Appends a mention to an existing composer draft, preserving it. Inserts a separating space only
     * when the draft does not already end in whitespace, and always leaves a trailing space.
     */
    fun appendMention(name: String, draft: String): String {
        val mention = createMention(name)
        if (draft.isEmpty()) return "$mention "
        val separator = if (draft.last().isWhitespace()) "" else " "
        return "$draft$separator$mention "
    }

    /**
     * Completes the active `@query` in [draft] with `@[name] `, leaving text before and after the
     * query intact. Locates `"@" + query` from the end so a later mid-word `@` (as in `foo@bar`) is
     * not the replacement target. No-ops when [detectActiveMention] is null. Mirrors iOS
     * `ChatConversationView.insertMention`.
     */
    fun insertMention(name: String, draft: String): String {
        val query = detectActiveMention(draft) ?: return draft
        val searchPattern = "@$query"
        val start = draft.lastIndexOf(searchPattern)
        if (start < 0) return draft
        return draft.replaceRange(start, start + searchPattern.length, "${createMention(name)} ")
    }

    /** All mentioned contact names (without the `@[]` wrapper) in order, including adjacent `@[A]@[B]`. */
    fun extractMentions(text: String): List<String> = mentionRegex.findAll(text).map { it.groupValues[1] }.toList()

    /** Whether [text] contains `@[selfName]`, matched case-insensitively. Empty [selfName] never matches. */
    fun containsSelfMention(text: String, selfName: String): Boolean {
        if (selfName.isEmpty()) return false
        return extractMentions(text).any { it.equals(selfName, ignoreCase = true) }
    }

    /**
     * The active mention query the composer autocomplete shows, or null when the caret is not in a
     * mention. Triggers on a `@` at string start or after whitespace; a standalone `@` returns `""` (show
     * all). Mid-word `email@`, `@ `, `@@`, an unclosed `@[`, and a completed `@[name]` all return null.
     * The backward scan matches the iOS `detectActiveMention` boundary cases.
     */
    fun detectActiveMention(text: String): String? = ActiveMention.detect(text)

    /**
     * Backward scan for the composer mention query. A mid-word `@` and a finished `@[name]` keep looking
     * earlier; `@ `, `@@`, and an unclosed `@[` stop the scan.
     */
    private object ActiveMention {
        fun detect(text: String): String? {
            if (text.isEmpty()) return null
            var searchEnd = text.length
            while (true) {
                when (val step = mentionStep(text, searchEnd)) {
                    is MentionScan.Earlier -> searchEnd = step.index
                    is MentionScan.Found -> return step.query
                }
            }
        }

        private fun mentionStep(text: String, searchEnd: Int): MentionScan {
            val atIndex = text.lastIndexOf('@', searchEnd - 1)
            if (atIndex < 0) return MentionScan.Found(null)
            return stepAt(text, atIndex)
        }

        private fun stepAt(text: String, atIndex: Int): MentionScan {
            if (isMidWord(text, atIndex)) return MentionScan.Earlier(atIndex)
            return queryAt(text, atIndex)
        }

        private fun isMidWord(text: String, atIndex: Int): Boolean {
            if (atIndex == 0) return false
            return !text[atIndex - 1].isWhitespace()
        }

        private fun queryAt(text: String, atIndex: Int): MentionScan {
            val afterAt = text.substring(atIndex + 1)
            if (afterAt.isEmpty()) return MentionScan.Found("")
            return queryAfterAt(afterAt, atIndex)
        }

        private fun queryAfterAt(afterAt: String, atIndex: Int): MentionScan {
            if (afterAt[0].isWhitespace() || afterAt[0] == '@') return MentionScan.Found(null)
            if (!afterAt.startsWith("[")) return MentionScan.Found(afterAt.takeWhile { !it.isWhitespace() })
            return closedMention(afterAt, atIndex)
        }

        private fun closedMention(afterAt: String, atIndex: Int): MentionScan {
            val closeBracket = afterAt.indexOf(']').takeIf { it >= 0 } ?: return MentionScan.Found(null)
            val afterMention = afterAt.substring(closeBracket + 1)
            if (afterMention.isEmpty()) return MentionScan.Found(null)
            return MentionScan.Earlier(atIndex)
        }

        private sealed interface MentionScan {
            class Found(val query: String?) : MentionScan
            class Earlier(val index: Int) : MentionScan
        }
    }

    /**
     * Chat-type contacts for a mention suggestion list, filtered by [query] and sorted. Repeaters and
     * rooms are dropped. With [senderOrder] the most recently heard sender sorts first (a contact with a
     * timestamp always precedes one without), then alphabetically; without it, purely alphabetically.
     */
    fun filterContacts(
        contacts: List<ContactDto>,
        query: String,
        senderOrder: Map<String, UInt>? = null,
    ): List<ContactDto> {
        val filtered = contacts
            .filter { it.contactType == ContactType.CHAT }
            .filter { query.isEmpty() || it.displayName.containsIgnoringCaseAndDiacritics(query) }

        if (senderOrder.isNullOrEmpty()) {
            return filtered.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName })
        }

        return filtered.sortedWith { a, b ->
            val aTimestamp = senderOrder[a.name]
            val bTimestamp = senderOrder[b.name]
            when {
                aTimestamp != null && bTimestamp != null -> bTimestamp.compareTo(aTimestamp)
                aTimestamp != null -> -1
                bTimestamp != null -> 1
                else -> String.CASE_INSENSITIVE_ORDER.compare(a.displayName, b.displayName)
            }
        }
    }

    /**
     * Reply text with a mention and a quoted preview of the original message. Any leading mention is
     * stripped from the source before the preview, which is the first [REPLY_PREVIEW_LENGTH] chars plus
     * `..` when longer.
     */
    fun buildReplyText(mentionName: String, messageText: String): String {
        val match = leadingMentionRegex.find(messageText)
        val previewSource = if (match != null) messageText.substring(match.range.last + 1) else messageText
        val preview = previewSource.take(REPLY_PREVIEW_LENGTH)
        val suffix = if (previewSource.length > REPLY_PREVIEW_LENGTH) ".." else ""
        return "${createMention(mentionName)}\n>$preview$suffix\n"
    }

    /**
     * Strips invisible and control scalars from an attacker-controlled name (a mention or contact-share
     * name). The cleaned form is used for both the visible chip and the deep-link URL so a bidi override,
     * zero-width joiner, or line break cannot hide or reorder the visible identity.
     *
     * The iOS pass strips bidi-control, default-ignorable, and category control/format/lineSeparator/
     * paragraphSeparator scalars. commonMain exposes only [CharCategory], so this strips CONTROL, FORMAT,
     * LINE_SEPARATOR, and PARAGRAPH_SEPARATOR — which subsumes every bidi-control and zero-width format
     * character (all category Format), the spoofing vectors that matter. Non-format default-ignorables
     * (variation selectors) are left intact, which never occur in a legitimate identity name.
     */
    fun sanitizeDisplayName(name: String): String = name.filterNot { it.category in STRIPPABLE_CATEGORIES }

    private val STRIPPABLE_CATEGORIES = setOf(
        CharCategory.CONTROL,
        CharCategory.FORMAT,
        CharCategory.LINE_SEPARATOR,
        CharCategory.PARAGRAPH_SEPARATOR,
    )
}
