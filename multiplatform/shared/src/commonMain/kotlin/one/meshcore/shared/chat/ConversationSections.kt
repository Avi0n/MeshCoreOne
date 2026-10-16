package one.meshcore.shared.chat

/**
 * Pinned conversations get a heading, and the remaining rows get one only when both groups
 * are shown. An empty pin group stays one flat list so the headings are not present with nothing
 * pinned.
 */
internal fun conversationSections(pinned: List<Conversation>, others: List<Conversation>): List<ConversationSection> {
    if (pinned.isEmpty()) return others.map { ConversationSection.Row(it) }
    return buildList {
        add(ConversationSection.Header(ConversationSection.Kind.PINNED))
        pinned.forEach { add(ConversationSection.Row(it)) }
        if (others.isNotEmpty()) {
            add(ConversationSection.Header(ConversationSection.Kind.OTHER))
            others.forEach { add(ConversationSection.Row(it)) }
        }
    }
}

internal sealed interface ConversationSection {
    data class Header(val kind: Kind) : ConversationSection

    data class Row(val conversation: Conversation) : ConversationSection

    enum class Kind { PINNED, OTHER }
}
