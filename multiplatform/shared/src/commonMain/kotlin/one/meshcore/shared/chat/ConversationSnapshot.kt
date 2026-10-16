package one.meshcore.shared.chat

/**
 * A complete, internally consistent pinned/other split of the conversation list. Committed as one
 * observed value so the list can never diff pins from one fetch generation against others from
 * another. Mirrors the Swift `ConversationSnapshot`.
 */
data class ConversationSnapshot(
    val pinned: List<Conversation> = emptyList(),
    val others: List<Conversation> = emptyList(),
) {
    /** Pins first, then others — the single concatenated section the list renders. */
    val all: List<Conversation> get() = pinned + others

    companion object {
        val EMPTY = ConversationSnapshot()
    }
}
