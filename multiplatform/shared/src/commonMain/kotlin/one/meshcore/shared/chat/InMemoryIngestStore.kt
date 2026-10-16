package one.meshcore.shared.chat

import kotlinx.coroutines.sync.withLock
import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.model.toLowercaseHex

/**
 * In-memory [IngestStore] holding rows in maps behind one mutex. Both the desktop/test backing for the
 * ingest pipeline and the behavior a Room-backed adapter must reproduce (paralleling [InMemoryMessageStore]
 * for the send path). Contacts, channels, and RX-log entries are pre-seeded because the ingest slice does
 * not own their persistence; counters and last-message dates are exposed for assertions. Dedup is
 * content-key membership over the saved rows for the radio. Counter writes delegate to
 * [InMemoryIngestBookkeeping] and the assertion reads to [InMemoryIngestCounters], both on this mutex.
 */
class InMemoryIngestStore private constructor(private val parts: InMemoryIngestParts) :
    IngestStore,
    IngestBookkeeping by parts.bookkeeping,
    IngestCounterRead by parts.counters {
    constructor() : this(InMemoryIngestParts())

    private val state = parts.state

    /** Registers a contact resolvable from [prefix] (the sender public-key prefix on the DM wire). */
    suspend fun seedContact(prefix: ByteArray, contact: ResolvedContact) = state.mutex.withLock {
        state.contactsByPrefix[prefix.toLowercaseHex()] = contact
    }

    /** Registers a local channel at [radioID]/[channelIndex]. */
    suspend fun seedChannel(radioID: Uuid, channelIndex: Int, channel: ResolvedChannel) = state.mutex.withLock {
        state.channelsBySlot[radioID to channelIndex] = channel
    }

    /** Registers an RX-log correlation for a channel message match. */
    suspend fun seedRxLog(radioID: Uuid, channelIndex: Int?, senderTimestamp: Long, correlation: RxLogCorrelation) =
        state.mutex.withLock {
            state.rxEntries[IngestRxLogKey(radioID, channelIndex, senderTimestamp)] = correlation
        }

    /** All saved messages in insertion order; test-only read. */
    suspend fun savedMessages(): List<MessageDto> = state.mutex.withLock { state.messages.toList() }

    override suspend fun resolveContact(radioID: Uuid, senderKeyPrefix: ByteArray): ResolvedContact? =
        state.mutex.withLock { state.contactsByPrefix[senderKeyPrefix.toLowercaseHex()] }

    override suspend fun resolveChannel(radioID: Uuid, channelIndex: Int): ResolvedChannel? =
        state.mutex.withLock { state.channelsBySlot[radioID to channelIndex] }

    override suspend fun lookupRxLog(
        radioID: Uuid,
        channelIndex: Int?,
        senderTimestamp: Long,
        senderKeyPrefix: ByteArray?,
        defaultPathLength: Int,
    ): RxLogCorrelation = state.mutex.withLock {
        state.rxEntries[IngestRxLogKey(radioID, channelIndex, senderTimestamp)]
            ?: RxLogCorrelation(
                pathNodes = null,
                pathLength = defaultPathLength,
                routeType = null,
                regionScope = null,
                regionScopeMatches = emptyList(),
            )
    }

    override suspend fun isDuplicateMessage(deduplicationKey: String, radioID: Uuid): Boolean = state.mutex.withLock {
        state.messages.any { it.radioID == radioID && it.deduplicationKey == deduplicationKey }
    }

    override suspend fun saveMessage(message: MessageDto) = state.mutex.withLock {
        state.messages += message
    }
}
