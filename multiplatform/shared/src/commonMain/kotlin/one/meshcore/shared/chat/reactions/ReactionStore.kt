package one.meshcore.shared.chat.reactions

import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.backup.ReactionDto
import one.meshcore.shared.model.Uuid

/**
 * The persistence seam the reaction layer depends on: save/fetch reactions, the dedup existence check, the
 * summary-cache write, and the target-message lookups the receive path falls back to when the LRU cache
 * misses. Contact, channel, and message row reads live on [ReactionTargetLookup]. A narrow port so the
 * service and ingest handler are unit-testable against an in-memory fake; the Room-backed adapter is the
 * production store. Mirrors the reaction subset of the Swift `PersistenceStoreProtocol`
 * (`ReactionPersisting` plus the message-candidate queries). Timestamps in [window] are epoch seconds;
 * every `receivedAt` is epoch seconds.
 */
interface ReactionStore : ReactionTargetLookup {
    /** Inserts (or replaces by id) a reaction row. */
    suspend fun saveReaction(reaction: ReactionDto)

    /** Reactions for [messageID], most recent first, capped at [limit]. */
    suspend fun fetchReactions(messageID: Uuid, limit: Int = DEFAULT_FETCH_LIMIT): List<ReactionDto>

    /** Whether a reaction with this ([messageID], [senderName], [emoji]) already persists (dedup guard). */
    suspend fun reactionExists(messageID: Uuid, senderName: String, emoji: String): Boolean

    /** Writes a message's cached reaction-summary string (null clears it). */
    suspend fun updateMessageReactionSummary(messageID: Uuid, summary: String?)

    /**
     * The DM message in [window] whose PocketMesh hash equals [messageHash], skipping rows that are
     * themselves reactions. Candidates are scanned most-recent first.
     */
    suspend fun findDirectMessageForReaction(
        radioID: Uuid,
        contactID: Uuid,
        messageHash: String,
        window: LongRange,
        limit: Int,
    ): MessageDto?

    /**
     * The channel message in [window] matching [parsed]: an outgoing candidate matches only when
     * [localNodeName] equals the reaction's target sender, an incoming one when its `senderNodeName` does,
     * and in both cases the PocketMesh hash must equal `parsed.messageHash`.
     */
    suspend fun findChannelMessageForReaction(
        lookup: ChannelReactionLookup,
        window: LongRange,
        limit: Int,
    ): MessageDto?

    /** Raw DM candidates in [window] (most-recent first) for meshcore-open Dart-hash matching by the caller. */
    suspend fun fetchDirectMessageCandidates(
        radioID: Uuid,
        contactID: Uuid,
        window: LongRange,
        limit: Int,
    ): List<MessageDto>

    /** Raw channel candidates in [window] (most-recent first) for meshcore-open Dart-hash matching. */
    suspend fun fetchChannelMessageCandidates(
        radioID: Uuid,
        channelIndex: Int,
        window: LongRange,
        limit: Int,
    ): List<MessageDto>

    companion object {
        const val DEFAULT_FETCH_LIMIT: Int = 100
    }
}
