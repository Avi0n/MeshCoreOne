package one.meshcore.shared.chat.reactions

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import one.meshcore.shared.backup.ReactionDto
import one.meshcore.shared.runExceptionFallback

/** Writes a reaction row and rebuilds the target message's summary. */
interface ReactionPersistence {
    /**
     * Persists [reaction], re-fetches the message's reactions, rebuilds its summary, and writes the summary
     * cache. Returns the (message, summary) on success or null if any store step fails. Mirrors the Swift
     * `persistReactionAndUpdateSummary`.
     */
    suspend fun persistReactionAndUpdateSummary(reaction: ReactionDto, store: ReactionStore): ReactionPersistResult?

    /**
     * Persists [reaction] only when no identical (message, sender, emoji) row exists, then notifies [sink]
     * with the rebuilt summary. Mirrors the Swift `persistReactionIfNew`.
     *
     * @return true when the reaction was newly persisted (a duplicate or a failed persist return false),
     * so the caller can distinguish a genuinely new reaction worth notifying about from a re-delivered one.
     */
    suspend fun persistReactionIfNew(reaction: ReactionDto, store: ReactionStore, sink: ReactionSummarySink): Boolean
}

internal class ReactionPersister : ReactionPersistence {
    private val updates = MutableSharedFlow<ReactionPersistResult>(
        extraBufferCapacity = SUMMARY_UPDATE_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Emits after every persist that rewrote a summary. [ReactionService.summaryUpdates] forwards this. */
    val summaryUpdates: SharedFlow<ReactionPersistResult> = updates.asSharedFlow()

    override suspend fun persistReactionAndUpdateSummary(
        reaction: ReactionDto,
        store: ReactionStore,
    ): ReactionPersistResult? = runExceptionFallback({ null }) {
        store.saveReaction(reaction)
        val reactions = store.fetchReactions(reaction.messageID)
        val summary = ReactionParser.buildSummaryFromReactions(
            reactions.map { ReactionRow(it.emoji, it.receivedAt) },
        )
        store.updateMessageReactionSummary(reaction.messageID, summary)
        ReactionPersistResult(reaction.messageID, summary).also { updates.tryEmit(it) }
    }

    override suspend fun persistReactionIfNew(
        reaction: ReactionDto,
        store: ReactionStore,
        sink: ReactionSummarySink,
    ): Boolean {
        if (reactionExists(reaction, store)) return false
        val result = persistReactionAndUpdateSummary(reaction, store) ?: return false
        sink.onReactionSummary(result.messageID, result.summary)
        return true
    }

    private suspend fun reactionExists(reaction: ReactionDto, store: ReactionStore): Boolean =
        runExceptionFallback({ false }) {
            store.reactionExists(reaction.messageID, reaction.senderName, reaction.emoji)
        }

    private companion object {
        /** Summary updates buffered for a thread that is mid-rebuild; a burst drops its oldest, never blocks. */
        const val SUMMARY_UPDATE_BUFFER = 64
    }
}
