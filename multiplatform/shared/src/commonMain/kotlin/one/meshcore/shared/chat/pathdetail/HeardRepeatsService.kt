package one.meshcore.shared.chat.pathdetail

import one.meshcore.shared.backup.MessageRepeatDto
import one.meshcore.shared.chat.DeduplicationKey
import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.runRoom
import one.meshcore.shared.settings.PersistentLogger
import one.meshcore.shared.tools.DecryptStatus
import one.meshcore.shared.tools.RxLogEntry
import one.meshcore.shared.tools.RxLogPayloadType

private const val MS_PER_SECOND: Double = 1000.0

/**
 * A decoded RX-log echo the heard-repeats matcher consumes: the subset of the Swift `RxLogEntryDTO` the
 * service reads. Built from a live [RxLogEntry] via [HeardRepeatEcho.fromRxLogEntry]. Times are epoch
 * seconds ([receivedAt]) and wire seconds ([senderTimestamp]).
 */
data class HeardRepeatEcho(
    val id: Uuid,
    val isGroupText: Boolean,
    val isDecryptSuccess: Boolean,
    val decodedText: String?,
    val channelIndex: Int?,
    val senderTimestamp: Long?,
    val receivedAt: Double,
    val pathNodes: ByteArray,
    val pathLength: Int,
    val snr: Double? = null,
    val rssi: Int? = null,
) {
    companion object {
        /** Maps a Tools → RX Log row into the matcher input (iOS `processForRepeats(RxLogEntryDTO)`). */
        fun fromRxLogEntry(entry: RxLogEntry): HeardRepeatEcho = HeardRepeatEcho(
            id = entry.id,
            isGroupText = entry.payloadType == RxLogPayloadType.GROUP_TEXT,
            isDecryptSuccess = entry.decryptStatus == DecryptStatus.SUCCESS,
            decodedText = entry.decodedText,
            channelIndex = entry.channelIndex,
            senderTimestamp = entry.senderTimestamp,
            receivedAt = entry.receivedAtEpochMs / MS_PER_SECOND,
            pathNodes = entry.pathNodes,
            pathLength = entry.pathLength,
            snr = entry.snr,
            rssi = entry.rssi,
        )
    }
}

/** A recorded heard repeat: the message whose propagation was confirmed and its new repeat count. */
data class HeardRepeatEvent(val messageID: Uuid, val count: Int)

/** A path observed on a received channel delivery or its RX-log entry. */
data class IncomingPathObservation(
    val pathNodes: ByteArray,
    val pathLength: Int,
    val snr: Double?,
    val rssi: Int?,
    val receivedAt: Double,
    val rxLogEntryID: Uuid? = null,
)

/**
 * Correlates RX-log echoes to sent channel messages to track "heard repeats" — evidence a channel
 * broadcast propagated through the mesh. Channel broadcasts have no end-to-end ACK, so a repeater
 * re-broadcasting our own message is the only propagation signal. Port of the Swift `HeardRepeatsService`
 * actor; [configure] supplies the per-connection radio identity, [newRepeatId] mints
 * the row id, and [onRepeat] is the broadcast seam the Swift `events()` stream fills.
 *
 * The match is exact — same channel, sender timestamp, and text — with no time window; the old 10-second
 * wall-clock gate was removed so a late echo still counts.
 */
class HeardRepeatsService(
    private val store: HeardRepeatStore,
    private val newRepeatId: () -> Uuid,
    private val onRepeat: suspend (HeardRepeatEvent) -> Unit = {},
    private val logger: PersistentLogger? = null,
) {
    private var radioID: Uuid? = null

    /** Binds the service to a connection's radio identity. Call once before processing. */
    fun configure(radioID: Uuid) {
        this.radioID = radioID
    }

    /**
     * Processes an echo, recording a heard repeat when it is a successfully-decrypted channel message that
     * matches a sent message on this radio. Sender name is not a join key — [HeardRepeatStore.findSentChannelMessage]
     * already scopes to this radio by channel, timestamp, and text. Returns the new repeat count on a match,
     * else null. A duplicate RX-log entry (already recorded) is counted once; on a dedup-check error it is
     * treated as a duplicate to avoid double counting.
     */
    suspend fun processForRepeats(echo: HeardRepeatEcho): Int? {
        val candidate = repeatCandidate(echo) ?: return null
        if (isDuplicate(echo.id)) {
            logger?.info(repeatAlreadyRecordedLine(echo.id))
            return null
        }
        // A store fault leaves this echo uncounted.
        return runRoom(
            warning = { line -> logger?.error(line) },
            failure = "Failed to process repeat",
            fallback = { null },
        ) {
            recordMatchedRepeat(
                candidate.radioID,
                echo,
                candidate.channelIndex,
                candidate.senderTimestamp,
                candidate.parsed,
            )
        }
    }

    private fun repeatCandidate(echo: HeardRepeatEcho): RepeatCandidate? {
        if (!echo.isGroupText || !echo.isDecryptSuccess) return null
        val decodedText = echo.decodedText ?: return null
        val channelIndex = echo.channelIndex ?: return null
        val senderTimestamp = echo.senderTimestamp ?: return null
        val radioID = this.radioID ?: return null
        val parsed = ChannelMessageFormat.parse(decodedText)
        if (parsed == null) {
            logger?.info(failedToParseChannelMessageTextLine(decodedText.take(CHANNEL_TEXT_PARSE_LOG_PREFIX)))
            return null
        }
        return RepeatCandidate(radioID, channelIndex, senderTimestamp, parsed)
    }

    private suspend fun recordMatchedRepeat(
        radioID: Uuid,
        echo: HeardRepeatEcho,
        channelIndex: Int,
        senderTimestamp: Long,
        parsed: ChannelMessageParts,
    ): Int? {
        val sentMessage = store.findSentChannelMessage(
            radioID = radioID,
            channelIndex = channelIndex,
            timestamp = senderTimestamp,
            text = parsed.messageText,
        )
        return if (sentMessage == null) {
            val key = DeduplicationKey.contentBased(
                contactID = null,
                channelIndex = channelIndex,
                senderNodeName = parsed.senderName,
                timestamp = senderTimestamp,
                content = parsed.messageText,
            )
            val incoming = store.findIncomingChannelMessage(radioID, key)
            if (incoming == null) {
                null
            } else {
                recordDistinctIncomingPath(
                    incoming,
                    IncomingPathObservation(
                        echo.pathNodes,
                        echo.pathLength,
                        echo.snr,
                        echo.rssi,
                        echo.receivedAt,
                        echo.id,
                    ),
                )
            }
        } else {
            store.saveMessageRepeat(
                MessageRepeatDto(
                    id = newRepeatId(),
                    messageID = sentMessage.id,
                    receivedAt = echo.receivedAt,
                    pathNodes = echo.pathNodes,
                    pathLength = echo.pathLength,
                    snr = echo.snr,
                    rssi = echo.rssi,
                    rxLogEntryID = echo.id,
                ),
            )
            val newCount = store.incrementMessageHeardRepeats(sentMessage.id)
            logger?.info(recordedRepeatLine(newCount, sentMessage.id))
            onRepeat(HeardRepeatEvent(messageID = sentMessage.id, count = newCount))
            newCount
        }
    }

    /** Records a duplicate channel delivery whose correlated RX-log path is already known. */
    suspend fun recordIncomingPath(deduplicationKey: String, observation: IncomingPathObservation): Int? {
        val radioID = radioID ?: return null
        // A store fault leaves this extra path unrecorded.
        return runRoom(
            warning = { line -> logger?.error(line) },
            failure = "Failed to record incoming path",
            fallback = { null },
        ) {
            val message = store.findIncomingChannelMessage(radioID, deduplicationKey) ?: return@runRoom null
            recordDistinctIncomingPath(message, observation)
        }
    }

    /** Reconciles RX-log rows that arrived before their incoming channel message was saved. */
    suspend fun harvestIncomingPaths(
        message: one.meshcore.shared.backup.MessageDto,
        decodedCandidates: List<HeardRepeatEcho>,
    ) {
        if (message.direction != MessageDirection.INCOMING || message.channelIndex == null) return
        val key = message.deduplicationKey ?: return
        decodedCandidates
            .filter { entry ->
                if (!entry.isGroupText || !entry.isDecryptSuccess) return@filter false
                val text = entry.decodedText ?: return@filter false
                val channel = entry.channelIndex ?: return@filter false
                val timestamp = entry.senderTimestamp ?: return@filter false
                val parsed = ChannelMessageFormat.parse(text) ?: return@filter false
                DeduplicationKey.contentBased(null, channel, parsed.senderName, timestamp, parsed.messageText) == key
            }
            .sortedBy { it.receivedAt }
            .forEach { entry ->
                recordIncomingPath(
                    key,
                    IncomingPathObservation(
                        entry.pathNodes,
                        entry.pathLength,
                        entry.snr,
                        entry.rssi,
                        entry.receivedAt,
                        entry.id,
                    ),
                )
            }
    }

    private suspend fun recordDistinctIncomingPath(
        message: one.meshcore.shared.backup.MessageDto,
        observation: IncomingPathObservation,
    ): Int? {
        val canonicalPath = message.pathNodes
        val sameOrStored = canonicalPath != null &&
            (
                observation.pathNodes.contentEquals(canonicalPath) ||
                    store.fetchMessageRepeats(message.id).any { it.pathNodes.contentEquals(observation.pathNodes) }
                )
        return when {
            canonicalPath == null -> {
                store.adoptIncomingPathIfUnknown(message.id, observation.pathNodes, observation.pathLength)
                null
            }
            sameOrStored -> null
            else -> {
                store.saveMessageRepeat(
                    MessageRepeatDto(
                        id = newRepeatId(),
                        messageID = message.id,
                        receivedAt = observation.receivedAt,
                        pathNodes = observation.pathNodes,
                        pathLength = observation.pathLength,
                        snr = observation.snr,
                        rssi = observation.rssi,
                        rxLogEntryID = observation.rxLogEntryID,
                    ),
                )
                val newCount = store.incrementMessageHeardRepeats(message.id)
                onRepeat(HeardRepeatEvent(message.id, newCount))
                newCount
            }
        }
    }

    /** Stored repeats for [messageID], sorted by receive time. Called when the Repeat Details sheet opens. */
    suspend fun refreshRepeats(messageID: Uuid): List<MessageRepeatDto> {
        logger?.info(refreshRepeatsCalledLine(messageID))
        // A Room fetch fault opens Repeat Details with no rows.
        return runRoom(
            warning = { line -> logger?.error(line) },
            failure = "Failed to fetch repeats",
            fallback = { emptyList() },
        ) {
            val results = store.fetchMessageRepeats(messageID)
            logger?.info(refreshRepeatsReturningLine(results.size))
            results
        }
    }

    // A dedup-check fault counts as already recorded so one echo is not stored twice.
    private suspend fun isDuplicate(rxLogEntryID: Uuid): Boolean = runRoom(
        warning = { line -> logger?.error(line) },
        failure = "Failed to check for existing repeat",
        fallback = { true },
    ) {
        store.messageRepeatExists(rxLogEntryID)
    }

    private class RepeatCandidate(
        val radioID: Uuid,
        val channelIndex: Int,
        val senderTimestamp: Long,
        val parsed: ChannelMessageParts,
    )
}
