package one.meshcore.shared.chat

/**
 * Detects whether a message mentions the local node. The ingest pipeline uses it to set
 * `containsSelfMention`. Mirrors the `MentionUtilities.containsSelfMention` contract.
 */
fun interface SelfMentionDetector {
    /** Whether [text] mentions [selfName] (a `@[name]` token matching case-insensitively). */
    fun containsSelfMention(text: String, selfName: String): Boolean
}

/**
 * The default [SelfMentionDetector]: extracts `@[name]` mention tokens and matches any against the self
 * name case-insensitively. This is the same wire mention format the iOS `MentionUtilities` parses;
 * autocomplete and rendering use the same membership rule.
 */
object BasicSelfMentionDetector : SelfMentionDetector {
    private val mentionRegex = Regex("""@\[([^\]]+)\]""")

    override fun containsSelfMention(text: String, selfName: String): Boolean =
        mentionRegex.findAll(text).any { it.groupValues[1].equals(selfName, ignoreCase = true) }
}
