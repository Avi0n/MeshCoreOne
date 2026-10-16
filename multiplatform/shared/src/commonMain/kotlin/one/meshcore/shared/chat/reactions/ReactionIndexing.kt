package one.meshcore.shared.chat.reactions

import kotlinx.coroutines.sync.withLock
import one.meshcore.shared.model.Uuid

/** Indexes seen messages and holds reactions whose target has not arrived yet. */
interface ReactionIndexing {
    /** Indexes a channel message and returns any queued reactions whose target it now is. */
    suspend fun indexMessage(
        id: Uuid,
        channelIndex: Int,
        senderName: String,
        text: String,
        timestamp: Long,
    ): List<PendingReaction>

    /** Indexes a DM message and returns any queued DM reactions whose target it now is. */
    suspend fun indexDmMessage(id: Uuid, contactID: Uuid, text: String, timestamp: Long): List<PendingDmReaction>

    /** The most-recently indexed channel message id matching [parsed], or null. */
    suspend fun findTargetMessage(parsed: ParsedReaction, channelIndex: Int): Uuid?

    /** The most-recently indexed DM message id matching [messageHash] for [contactID], or null. */
    suspend fun findDmTargetMessage(messageHash: String, contactID: Uuid): Uuid?

    /** Queues a channel reaction whose target message has not been seen yet. */
    suspend fun queuePendingReaction(
        parsed: ParsedReaction,
        channelIndex: Int,
        senderNodeName: String,
        rawText: String,
        radioID: Uuid,
    )

    /** Queues a DM reaction whose target message has not been seen yet. */
    suspend fun queuePendingDmReaction(
        parsed: ParsedDmReaction,
        contactID: Uuid,
        senderName: String,
        rawText: String,
        radioID: Uuid,
    )

    /** Drops every pending reaction; call on disconnect. */
    suspend fun clearPendingReactions()
}

internal class ReactionIndexer(private val cache: ReactionMessageCache, private val state: ReactionServiceState) :
    ReactionIndexing {
    override suspend fun indexMessage(
        id: Uuid,
        channelIndex: Int,
        senderName: String,
        text: String,
        timestamp: Long,
    ): List<PendingReaction> {
        cache.index(id, channelIndex, senderName, text, timestamp)
        val hash = ReactionParser.generateMessageHash(text, timestamp)
        val key = ReactionServiceState.PendingChannelKey(channelIndex, senderName, hash)
        return state.mutex.withLock { state.pendingChannel.remove(key)?.toList() ?: emptyList() }
    }

    override suspend fun indexDmMessage(
        id: Uuid,
        contactID: Uuid,
        text: String,
        timestamp: Long,
    ): List<PendingDmReaction> {
        cache.indexDM(id, contactID, text, timestamp)
        val hash = ReactionParser.generateMessageHash(text, timestamp)
        val key = ReactionServiceState.PendingDirectKey(contactID, hash)
        return state.mutex.withLock { state.pendingDirect.remove(key)?.toList() ?: emptyList() }
    }

    override suspend fun findTargetMessage(parsed: ParsedReaction, channelIndex: Int): Uuid? =
        cache.lookup(channelIndex, parsed.targetSender, parsed.messageHash)
            .maxByOrNull { it.indexedAt }?.messageID

    override suspend fun findDmTargetMessage(messageHash: String, contactID: Uuid): Uuid? =
        cache.lookupDM(contactID, messageHash).maxByOrNull { it.indexedAt }?.messageID

    override suspend fun queuePendingReaction(
        parsed: ParsedReaction,
        channelIndex: Int,
        senderNodeName: String,
        rawText: String,
        radioID: Uuid,
    ) {
        val key = ReactionServiceState.PendingChannelKey(channelIndex, parsed.targetSender, parsed.messageHash)
        val pending = PendingReaction(parsed, channelIndex, senderNodeName, rawText, radioID, state.now())
        state.mutex.withLock {
            state.pendingChannel.getOrPut(key) { mutableListOf() }.add(pending)
            evict(state.pendingChannel)
        }
    }

    override suspend fun queuePendingDmReaction(
        parsed: ParsedDmReaction,
        contactID: Uuid,
        senderName: String,
        rawText: String,
        radioID: Uuid,
    ) {
        val key = ReactionServiceState.PendingDirectKey(contactID, parsed.messageHash)
        val pending = PendingDmReaction(parsed, contactID, senderName, rawText, radioID, state.now())
        state.mutex.withLock {
            state.pendingDirect.getOrPut(key) { mutableListOf() }.add(pending)
            evict(state.pendingDirect)
        }
    }

    override suspend fun clearPendingReactions() = state.mutex.withLock {
        state.pendingChannel.clear()
        state.pendingDirect.clear()
    }

    // Insertion order is the queue: drain the oldest key before newer ones.
    private fun <K, V> evict(queue: LinkedHashMap<K, MutableList<V>>) {
        var total = queue.values.sumOf { it.size }
        while (total > ReactionConstants.MAX_PENDING_REACTIONS && queue.isNotEmpty()) {
            val oldestKey = queue.keys.first()
            val entries = queue.getValue(oldestKey)
            if (entries.isEmpty()) {
                queue.remove(oldestKey)
            } else {
                entries.removeAt(0)
                total -= 1
                if (entries.isEmpty()) queue.remove(oldestKey)
            }
        }
    }
}
