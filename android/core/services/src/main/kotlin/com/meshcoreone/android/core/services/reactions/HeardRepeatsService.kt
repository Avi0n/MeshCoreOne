// PortedFrom: MC1Services/Sources/MC1Services/Services/HeardRepeatsService.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.reactions

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.HeardRepeatPersisting
import com.meshcoreone.android.core.model.DecryptStatus
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageRepeatDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.RxLogEntryDTO
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.PayloadType
import java.time.Instant
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow

/**
 * Correlates RX log entries to known messages and tracks extra flood paths plus sent-echo repeats.
 *
 * Swift isolates this in an actor whose methods interleave at every store `await`; here the only mutable
 * state ([radioId]) is lock-confined and the store is called without holding the lock, which preserves that
 * reentrancy. Nothing is scheduled, so no coroutine scope or clock is needed (every timestamp comes from the
 * RX entry). Store failures are logged and treated as "no repeat"; cancellation always propagates.
 *
 * Repeat rows are keyed per radio on Android ([EntityKey]); a message's repeats are stored and checked under
 * the message's own radio, and the RX-entry duplicate check under the configured radio, which is the only
 * radio [processForRepeats] ever matches messages for.
 */
class HeardRepeatsService(private val dataStore: HeardRepeatPersisting) {
    private val logger: Logger = Logger.getLogger("HeardRepeatsService")
    private val lock = Any()

    /** Device ID for the current session. Guarded by [lock]. */
    private var radioId: RadioId? = null

    /** Multicast broadcaster for heard-repeat events. */
    internal val eventBroadcaster = HeardRepeatEventBroadcaster()

    /**
     * Returns a fresh stream of heard-repeat events. Registration is synchronous, so events yielded after this
     * call are never dropped. Each returned stream must be collected exactly once (it buffers until collected or
     * [finishEvents]; a second collection throws [IllegalStateException]). Consumers must re-subscribe per
     * connection because the owning service container is rebuilt on every connection.
     */
    fun events(): Flow<HeardRepeatEvent> = eventBroadcaster.subscribe()

    /** Ends every [events] subscriber's collection so consumer tasks release the service references they hold. */
    fun finishEvents() = eventBroadcaster.finish()

    /** Configures the service with the connected radio. Must be called once before processing any RX log entries. */
    fun configure(radioId: RadioId) {
        synchronized(lock) { this.radioId = radioId }
        logger.info { "Configured with radioID: ${radioId.value}" }
    }

    /** Checks if a repeat has already been recorded for this RX log entry; assumes duplicate on store error. */
    private suspend fun isDuplicateRepeat(entry: EntityKey): Boolean = try {
        dataStore.messageRepeatExists(entry)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        logger.log(Level.SEVERE, "Failed to check for existing repeat: ${error.message}", error)
        true // Assume duplicate on error to prevent potential duplicates.
    }

    /**
     * Processes an RX log entry to check if it's a repeat of a known channel message.
     *
     * Only successfully decrypted channel messages are processed. A sent message matches by exact channel,
     * sender timestamp and text (the body after the first colon); otherwise an incoming message matches by its
     * content-based deduplication key and the entry's path is recorded when it is a distinct extra path.
     *
     * @return the updated heard-repeats count if a repeat was recorded, null otherwise
     */
    suspend fun processForRepeats(entry: RxLogEntryDTO): Long? {
        if (entry.payloadType != PayloadType.GROUP_TEXT) return null
        if (entry.decryptStatus != DecryptStatus.SUCCESS) return null
        val decodedText = entry.decodedText ?: return null
        val channelIndex = entry.channelIndex ?: return null
        val senderTimestamp = entry.senderTimestamp ?: return null
        val radioId = synchronized(lock) { this.radioId } ?: return null

        // Body after the first colon is the stored outgoing text. Sender name is a join key only for incoming
        // extras (DeduplicationKey), not sent echoes.
        val parsed = ChannelMessageFormat.parse(decodedText)
        if (parsed == null) {
            // Swift logs the first 50 characters; decrypted content stays out of the Android log.
            logger.fine { "Failed to parse channel message text (${decodedText.length} UTF-16 units)" }
            return null
        }

        // Check for duplicate (already processed this RX entry).
        if (isDuplicateRepeat(EntityKey(radioId, entry.id))) {
            logger.info { "Repeat already recorded for RX entry: ${entry.id}" }
            return null
        }

        return try {
            val sent = dataStore.findSentChannelMessage(radioId, channelIndex, senderTimestamp, parsed.messageText)
            if (sent != null) {
                recordSentEcho(sent, entry)
            } else {
                val key = DeduplicationKey.contentBased(null, channelIndex, parsed.senderName, senderTimestamp, parsed.messageText)
                dataStore.fetchMessage(key, radioId)?.let { message ->
                    recordDistinctPathIfNeeded(
                        message = message,
                        pathNodes = entry.pathNodes,
                        pathLength = entry.pathLength,
                        snr = entry.snr,
                        rssi = entry.rssi,
                        receivedAt = entry.receivedAt,
                        rxLogEntryID = entry.id,
                    )
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.log(Level.SEVERE, "Failed to process repeat: ${error.message}", error)
            null
        }
    }

    /** Records every sent-channel echo, including identical hop lists. Store errors propagate to the caller. */
    private suspend fun recordSentEcho(message: MessageDTO, entry: RxLogEntryDTO): Long {
        val repeat = MessageRepeatDTO(
            messageID = message.id,
            receivedAt = entry.receivedAt,
            pathNodes = entry.pathNodes,
            pathLength = entry.pathLength,
            snr = entry.snr,
            rssi = entry.rssi,
            rxLogEntryID = entry.id,
        )
        dataStore.saveMessageRepeat(message.radioId, repeat)
        val newCount = dataStore.incrementMessageHeardRepeats(EntityKey(message.radioId, message.id))
        logger.info { "Recorded repeat #$newCount for message ${message.id}" }
        eventBroadcaster.yield(HeardRepeatEvent(message.id, newCount))
        return newCount
    }

    /**
     * Records a distinct extra incoming path. A null canonical path is unknown, not a 0-hop, so the first match
     * is adopted onto the message instead of stored as an extra.
     *
     * @return the updated heard-repeats count when an extra was recorded, null otherwise
     */
    suspend fun recordDistinctPathIfNeeded(
        message: MessageDTO,
        pathNodes: Bytes,
        pathLength: UByte,
        snr: Double?,
        rssi: Long?,
        receivedAt: Instant,
        rxLogEntryID: UUID?,
    ): Long? {
        val messageKey = EntityKey(message.radioId, message.id)
        val canonicalPath = message.pathNodes
        if (canonicalPath == null) {
            try {
                dataStore.adoptIncomingPathIfUnknown(messageKey, pathNodes, pathLength)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logger.log(Level.SEVERE, "Failed to adopt incoming path: ${error.message}", error)
            }
            return null
        }
        if (pathNodes == canonicalPath) return null

        return try {
            if (rxLogEntryID != null && dataStore.messageRepeatExists(EntityKey(message.radioId, rxLogEntryID))) return null
            val existing = dataStore.fetchMessageRepeats(messageKey)
            if (existing.any { it.pathNodes == pathNodes }) return null

            val repeat = MessageRepeatDTO(
                messageID = message.id,
                receivedAt = receivedAt,
                pathNodes = pathNodes,
                pathLength = pathLength,
                snr = snr,
                rssi = rssi,
                rxLogEntryID = rxLogEntryID,
            )
            dataStore.saveMessageRepeat(message.radioId, repeat)
            val newCount = dataStore.incrementMessageHeardRepeats(messageKey)
            logger.info { "Recorded extra path #$newCount for message ${message.id}" }
            eventBroadcaster.yield(HeardRepeatEvent(message.id, newCount))
            newCount
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.log(Level.SEVERE, "Failed to record extra path: ${error.message}", error)
            null
        }
    }

    /**
     * Adopts an unknown canonical path from the earliest [ChannelRXCorrelation] match, then records later
     * distinct paths as extras. Only incoming channel messages are harvested.
     */
    suspend fun harvestIncomingPaths(message: MessageDTO, decodedCandidates: List<RxLogEntryDTO>) {
        if (message.isOutgoing || message.channelIndex == null) return
        val matching = ChannelRXCorrelation.matching(decodedCandidates, message.deduplicationKey)
        var current = message
        for (entry in matching) {
            val wasUnknown = current.pathNodes == null
            recordDistinctPathIfNeeded(
                message = current,
                pathNodes = entry.pathNodes,
                pathLength = entry.pathLength,
                snr = entry.snr,
                rssi = entry.rssi,
                receivedAt = entry.receivedAt,
                rxLogEntryID = entry.id,
            )
            val key = current.deduplicationKey
            if (wasUnknown && key != null) {
                refetch(key, current.radioId)?.let { current = it }
            }
        }
    }

    /** Swift `try? fetchMessage(...)`: a failed refresh keeps the previous snapshot (logged here, not silent). */
    private suspend fun refetch(deduplicationKey: String, radioId: RadioId): MessageDTO? = try {
        dataStore.fetchMessage(deduplicationKey, radioId)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        logger.log(Level.WARNING, "Failed to refresh message after path adoption: ${error.message}", error)
        null
    }

    /**
     * Refreshes repeats for a message (used when opening the Repeat Details sheet).
     *
     * @return the stored repeats sorted by receivedAt, or empty on store error
     */
    suspend fun refreshRepeats(message: EntityKey): SnapshotList<MessageRepeatDTO> {
        logger.info { "refreshRepeats called for messageID: ${message.id}" }
        return try {
            dataStore.fetchMessageRepeats(message).also { logger.info { "refreshRepeats returning ${it.size} repeats" } }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.log(Level.SEVERE, "Failed to fetch repeats: ${error.message}", error)
            SnapshotList.empty()
        }
    }

    /** Test-visible configured radio. */
    internal val configuredRadioId: RadioId? get() = synchronized(lock) { radioId }

}
