// PortedFrom: MC1Services/Sources/MC1Services/Services/ReactionService.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.reactions

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.ParsedReaction
import com.meshcoreone.android.core.contracts.domain.ReactionPersisting
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.ReactionDTO
import com.meshcoreone.android.core.services.rendering.MessageCandidate
import com.meshcoreone.android.core.services.rendering.MessageLRUCache
import com.meshcoreone.android.core.services.rendering.SwiftText
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.coroutines.cancellation.CancellationException

/** A reaction waiting for its target message to be indexed. */
data class PendingReaction(
    val parsed: ParsedReaction,
    val channelIndex: UByte,
    val senderNodeName: String,
    val rawText: String,
    val radioId: RadioId,
    val receivedAt: Instant,
)

/** A DM reaction waiting for its target message to be indexed. */
data class PendingDMReaction(
    val parsed: ParsedDMReaction,
    val contactID: UUID,
    val senderName: String,
    val rawText: String,
    val radioId: RadioId,
    val receivedAt: Instant,
)

/** Result of persisting a reaction. */
data class ReactionPersistResult(val messageID: UUID, val summary: String)

/**
 * Service for handling emoji reactions on channel and DM messages: hash-indexed target lookup through the
 * shared [MessageLRUCache] and a session-lifetime queue of reactions whose target has not arrived yet.
 *
 * Swift isolates this in an actor. Here the pending queues are immutable values swapped under one lock that
 * is never held across a suspension; [MessageLRUCache] has its own lock. Nothing is scheduled, so no
 * coroutine scope is needed; [clock] stamps `receivedAt` on queued reactions.
 */
class ReactionService internal constructor(
    private val messageCache: MessageLRUCache,
    private val clock: Clock,
) {
    /** A service with its own message cache; [clock] also stamps the cache's `indexedAt`. */
    constructor(clock: Clock = Clock.systemUTC()) : this(MessageLRUCache(clock = clock), clock)

    private val logger: Logger = Logger.getLogger("ReactionService")
    private val lock = Any()

    // Pending reactions queues: no TTL, session lifetime. Guarded by [lock].
    private var pendingReactions = PendingQueue.empty<PendingReactionKey, PendingReaction>()
    private var pendingDMReactions = PendingQueue.empty<PendingDMReactionKey, PendingDMReaction>()

    /** [targetSender] is canonical (NFC) because Swift hashes `String` keys by canonical equivalence. */
    private data class PendingReactionKey(val channelIndex: UByte, val targetSender: String, val messageHash: String) {
        companion object {
            fun of(channelIndex: UByte, targetSender: String, messageHash: String) =
                PendingReactionKey(channelIndex, SwiftText.canonical(targetSender), messageHash)
        }
    }

    private data class PendingDMReactionKey(val contactID: UUID, val messageHash: String)

    /** Indexes a message for reaction matching and returns any pending reactions that now match. */
    fun indexMessage(id: UUID, channelIndex: UByte, senderName: String, text: String, timestamp: UInt): List<PendingReaction> {
        messageCache.index(messageID = id, channelIndex = channelIndex, senderName = senderName, text = text, timestamp = timestamp)
        // Check pending queue for matching reactions.
        val key = PendingReactionKey.of(channelIndex, senderName, ReactionParser.generateMessageHash(text, timestamp))
        val matched = synchronized(lock) {
            val (remaining, taken) = pendingReactions.take(key)
            pendingReactions = remaining
            taken
        } ?: return emptyList()
        logger.fine { "Matched ${matched.size} pending reaction(s) to message $id" }
        return matched
    }

    /** Builds channel reaction wire text: `@[sender]emoji` then newline and hash. */
    fun buildReactionText(emoji: String, targetSender: String, targetText: String, targetTimestamp: UInt): String =
        "@[$targetSender]$emoji\n${ReactionParser.generateMessageHash(targetText, targetTimestamp)}"

    /** Builds DM reaction wire format (shorter, no sender). */
    fun buildDMReactionText(emoji: String, targetText: String, targetTimestamp: UInt): String =
        ReactionParser.buildDMReactionText(emoji, targetText, targetTimestamp)

    /** Finds the target message ID for a parsed reaction: the most recently indexed hash match. */
    fun findTargetMessage(parsed: ParsedReaction, channelIndex: UByte): UUID? =
        messageCache.lookup(channelIndex = channelIndex, senderName = parsed.targetSender, messageHash = parsed.messageHash)
            .mostRecentlyIndexed()?.messageID

    /** Attempts to parse incoming text as a channel reaction; null means process it as a regular message. */
    internal fun tryProcessAsReaction(text: String): ParsedReaction? = ReactionParser.parse(text)

    /** Queues a reaction that couldn't find its target message. */
    fun queuePendingReaction(
        parsed: ParsedReaction,
        channelIndex: UByte,
        senderNodeName: String,
        rawText: String,
        radioId: RadioId,
    ) {
        val key = PendingReactionKey.of(channelIndex, parsed.targetSender, parsed.messageHash)
        val pending = PendingReaction(parsed, channelIndex, senderNodeName, rawText, radioId, clock.instant())
        synchronized(lock) { pendingReactions = pendingReactions.enqueue(key, pending, MAX_PENDING_REACTIONS) }
        logger.fine { "Queued pending reaction ${parsed.emoji} for ${parsed.targetSender}" }
    }

    /** Clears all pending reactions (call on disconnect). */
    internal fun clearPendingReactions() {
        val cleared = synchronized(lock) {
            val count = pendingReactions.count + pendingDMReactions.count
            pendingReactions = PendingQueue.empty()
            pendingDMReactions = PendingQueue.empty()
            count
        }
        if (cleared > 0) logger.fine { "Cleared $cleared pending reaction(s)" }
    }

    // MARK: - Persistence

    /**
     * Persists a reaction and updates the message's reaction summary (built from the newest 100 reactions, the
     * store's default fetch limit). Store failures are logged and yield null; cancellation propagates.
     */
    suspend fun persistReactionAndUpdateSummary(reaction: ReactionDTO, dataStore: ReactionPersisting): ReactionPersistResult? {
        val message = EntityKey(reaction.radioId, reaction.messageID)
        try {
            dataStore.saveReaction(reaction)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.log(Level.SEVERE, "Failed to save reaction for message ${reaction.messageID}: ${error.message}", error)
            return null
        }

        val reactions = try {
            dataStore.fetchReactions(message)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.log(Level.SEVERE, "Failed to fetch reactions for message ${reaction.messageID}: ${error.message}", error)
            return null
        }

        val summary = ReactionParser.buildSummary(reactions)
        try {
            dataStore.updateMessageReactionSummary(message, summary)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.log(Level.SEVERE, "Failed to update reaction summary for message ${reaction.messageID}: ${error.message}", error)
            return null
        }
        return ReactionPersistResult(reaction.messageID, summary)
    }

    // MARK: - DM Reactions

    /** Indexes a DM message for reaction matching and returns any pending reactions that now match. */
    fun indexDMMessage(id: UUID, contactID: UUID, text: String, timestamp: UInt): List<PendingDMReaction> {
        messageCache.indexDM(messageID = id, contactID = contactID, text = text, timestamp = timestamp)
        val key = PendingDMReactionKey(contactID, ReactionParser.generateMessageHash(text, timestamp))
        val matched = synchronized(lock) {
            val (remaining, taken) = pendingDMReactions.take(key)
            pendingDMReactions = remaining
            taken
        } ?: return emptyList()
        logger.fine { "Matched ${matched.size} pending DM reaction(s) to message $id" }
        return matched
    }

    /** Finds the target DM message ID by hash and contact: the most recently indexed match. */
    fun findDMTargetMessage(messageHash: String, contactID: UUID): UUID? =
        messageCache.lookupDM(contactID = contactID, messageHash = messageHash).mostRecentlyIndexed()?.messageID

    /** Queues a DM reaction that couldn't find its target message. */
    fun queuePendingDMReaction(
        parsed: ParsedDMReaction,
        contactID: UUID,
        senderName: String,
        rawText: String,
        radioId: RadioId,
    ) {
        val key = PendingDMReactionKey(contactID, parsed.messageHash)
        val pending = PendingDMReaction(parsed, contactID, senderName, rawText, radioId, clock.instant())
        synchronized(lock) { pendingDMReactions = pendingDMReactions.enqueue(key, pending, MAX_PENDING_REACTIONS) }
        logger.fine { "Queued pending DM reaction ${parsed.emoji}" }
    }

    /** Test-visible total of queued reactions (channel, DM). */
    internal val pendingCounts: Pair<Int, Int> get() = synchronized(lock) { pendingReactions.count to pendingDMReactions.count }

    internal companion object {
        const val MAX_PENDING_REACTIONS: Int = 100

        /**
         * The candidate with the latest `indexedAt`, keeping the first equal date like Swift's `max(by:)`.
         */
        private fun List<MessageCandidate>.mostRecentlyIndexed(): MessageCandidate? =
            fold(null as MessageCandidate?) { best, candidate -> if (best == null || candidate.indexedAt > best.indexedAt) candidate else best }
    }
}

/**
 * Immutable pending-reaction queue: entries grouped by key plus first-queued key order. Swift keeps the two
 * dictionaries/arrays inline and duplicates the eviction loop for channel and DM; the semantics here are the same.
 */
internal class PendingQueue<K, V> private constructor(
    private val entries: Map<K, List<V>>,
    private val order: List<K>,
) {
    val count: Int get() = entries.values.sumOf { it.size }

    /** Appends [value] under [key] (a new key joins the end of the order), then evicts down to [maxCount]. */
    fun enqueue(key: K, value: V, maxCount: Int): PendingQueue<K, V> {
        val existing = entries[key]
        val queued = if (existing != null) {
            PendingQueue(entries + (key to existing + value), order)
        } else {
            PendingQueue(entries + (key to listOf(value)), order + key)
        }
        return queued.evicted(maxCount)
    }

    /** Removes every value queued under [key]; returns the remaining queue and the values (null when none). */
    fun take(key: K): Pair<PendingQueue<K, V>, List<V>?> {
        val matched = entries[key] ?: return this to null
        return PendingQueue(entries - key, order.filter { it != key }) to matched
    }

    /**
     * Swift `evictIfNeeded`: while over [maxCount], drop the oldest value of the oldest key; a key whose list
     * empties (or that has no list) leaves the order.
     */
    private fun evicted(maxCount: Int): PendingQueue<K, V> {
        var total = count
        if (total <= maxCount) return this
        var nextEntries = entries
        var nextOrder = order
        while (total > maxCount && nextOrder.isNotEmpty()) {
            val oldestKey = nextOrder.first()
            val values = nextEntries[oldestKey]
            if (!values.isNullOrEmpty()) {
                val remaining = values.drop(1)
                total -= 1
                if (remaining.isEmpty()) {
                    nextEntries = nextEntries - oldestKey
                    nextOrder = nextOrder.drop(1)
                } else {
                    nextEntries = nextEntries + (oldestKey to remaining)
                }
            } else {
                nextOrder = nextOrder.drop(1)
            }
        }
        return PendingQueue(nextEntries, nextOrder)
    }

    companion object {
        fun <K, V> empty(): PendingQueue<K, V> = PendingQueue(emptyMap(), emptyList())
    }
}
