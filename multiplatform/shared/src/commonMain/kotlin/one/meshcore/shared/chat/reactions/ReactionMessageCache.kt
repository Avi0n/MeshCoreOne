package one.meshcore.shared.chat.reactions

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import one.meshcore.shared.model.Uuid

/** A candidate message for reaction matching; [indexedAt] resolves ties toward the most-recently indexed. */
data class MessageCandidate(val messageID: Uuid, val text: String, val timestamp: Long, val indexedAt: Long)

/**
 * LRU cache of recently seen messages keyed by their reaction hash, so an inbound reaction can find its
 * target without a DB scan. Channel messages key on (channel index, sender name, hash); DMs on (contact,
 * hash). Collisions keep the [ReactionConstants.MAX_CANDIDATES_PER_KEY] most-recent candidates per key, and
 * a full cache evicts the least-recently-touched key. Port of the Swift `MessageLRUCache` actor; a [Mutex]
 * gives the same serialized access. [monotonicTick] supplies a strictly increasing indexed order so ties
 * are deterministic without wall-clock granularity.
 */
class ReactionMessageCache(
    private val capacity: Int = ReactionConstants.CACHE_CAPACITY,
    private val maxCandidatesPerKey: Int = ReactionConstants.MAX_CANDIDATES_PER_KEY,
) {
    private data class ChannelKey(val channelIndex: Int, val senderName: String, val messageHash: String)
    private data class DirectKey(val contactID: Uuid, val messageHash: String)

    private val mutex = Mutex()
    private val channelCache = LinkedHashMap<ChannelKey, MutableList<MessageCandidate>>()
    private val directCache = LinkedHashMap<DirectKey, MutableList<MessageCandidate>>()
    private var tick = 0L

    suspend fun index(messageID: Uuid, channelIndex: Int, senderName: String, text: String, timestamp: Long) {
        val hash = ReactionParser.generateMessageHash(text, timestamp)
        val key = ChannelKey(channelIndex, senderName, hash)
        mutex.withLock {
            insert(channelCache, key, MessageCandidate(messageID, text, timestamp, nextTick()))
        }
    }

    suspend fun lookup(channelIndex: Int, senderName: String, messageHash: String): List<MessageCandidate> {
        val key = ChannelKey(channelIndex, senderName, messageHash)
        return mutex.withLock { channelCache[key]?.toList() ?: emptyList() }
    }

    suspend fun indexDM(messageID: Uuid, contactID: Uuid, text: String, timestamp: Long) {
        val hash = ReactionParser.generateMessageHash(text, timestamp)
        val key = DirectKey(contactID, hash)
        mutex.withLock {
            insert(directCache, key, MessageCandidate(messageID, text, timestamp, nextTick()))
        }
    }

    suspend fun lookupDM(contactID: Uuid, messageHash: String): List<MessageCandidate> {
        val key = DirectKey(contactID, messageHash)
        return mutex.withLock { directCache[key]?.toList() ?: emptyList() }
    }

    suspend fun clear() = mutex.withLock {
        channelCache.clear()
        directCache.clear()
    }

    private fun nextTick(): Long = ++tick

    private fun <K> insert(
        cache: LinkedHashMap<K, MutableList<MessageCandidate>>,
        key: K,
        candidate: MessageCandidate,
    ) {
        // Re-touch: remove then re-put so the key moves to the most-recently-used tail of the ordering.
        val existing = cache.remove(key)?.also { it.removeAll { c -> c.messageID == candidate.messageID } }
            ?: mutableListOf()
        existing += candidate
        while (existing.size > maxCandidatesPerKey) existing.removeAt(0)
        cache[key] = existing
        if (cache.size > capacity) {
            val oldest = cache.keys.firstOrNull()
            if (oldest != null) cache.remove(oldest)
        }
    }
}
