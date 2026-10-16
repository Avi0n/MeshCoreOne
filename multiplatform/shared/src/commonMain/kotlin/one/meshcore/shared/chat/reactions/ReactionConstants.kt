package one.meshcore.shared.chat.reactions

/** Tunables for reaction matching and aggregation, mirroring the Swift `ReactionService`/`SyncCoordinator`. */
object ReactionConstants {
    /** Symmetric window (seconds) around a reaction's anchor timestamp when searching for its target. */
    const val REACTION_TIMESTAMP_WINDOW_SECONDS: Long = 300

    const val MAX_WIRE_TIMESTAMP: Long = 0xFFFF_FFFFL

    /** Per-queue cap on session-lifetime pending reactions (FIFO eviction of the oldest). */
    const val MAX_PENDING_REACTIONS: Int = 100

    /** LRU-cache key capacity for recently indexed messages. */
    const val CACHE_CAPACITY: Int = 500

    /** Candidates retained per cache key (keeps the most recent on hash collision). */
    const val MAX_CANDIDATES_PER_KEY: Int = 5

    /** DB candidate fetch limit when falling back from the cache. */
    const val CANDIDATE_FETCH_LIMIT: Int = 200

    /** Reactions shown as individual badges before the overflow `+N` badge. */
    const val BADGE_PREFIX_COUNT: Int = 3

    /** Above this length a reacted-to message is truncated for the reaction-notification body. */
    const val REACTION_PREVIEW_MAX_LENGTH: Int = 50

    /** Characters kept from the start of a truncated reaction-notification preview. */
    const val REACTION_PREVIEW_KEEP_LENGTH: Int = 47

    /** Appended to a truncated reaction-notification preview. */
    const val REACTION_PREVIEW_ELLIPSIS: String = "..."
}
