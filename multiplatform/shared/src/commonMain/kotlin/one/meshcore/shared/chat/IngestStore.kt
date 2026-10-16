package one.meshcore.shared.chat

import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.model.NotificationLevel
import one.meshcore.shared.model.RouteType
import one.meshcore.shared.model.Uuid

/** A contact resolved from an incoming DM's sender key prefix, carrying only the fields ingest reads. */
data class ResolvedContact(val id: Uuid, val displayName: String, val isBlocked: Boolean, val isMuted: Boolean)

/**
 * The local channel an incoming channel message resolves to. Live receive resolves by the volatile
 * firmware slot ([index]) because the wire carries no channel secret; the resolved row's stable [id]
 * is what the unread and last-message bookkeeping key on, never the slot. A null resolution is the
 * phantom-channel case (§13.3) and must not surface a notification.
 */
data class ResolvedChannel(
    val id: Uuid,
    val index: Int,
    val radioID: Uuid,
    val name: String,
    val notificationLevel: NotificationLevel,
)

/**
 * Path data recovered by correlating an incoming message with a stored RX-log entry. On a miss the
 * caller supplies the reported path length and null path nodes. Mirrors Swift `RxLogLookupResult`
 * (packetHash omitted: dedup is content-based, so the per-packet hash never reaches ingest).
 */
data class RxLogCorrelation(
    val pathNodes: ByteArray?,
    val pathLength: Int,
    val routeType: RouteType?,
    val regionScope: String?,
    val regionScopeMatches: List<String> = emptyList(),
)

/**
 * The persistence seam the incoming-ingest pipeline depends on: sender/channel resolution, RX-log
 * correlation, the content-based dedup check, and the message save. Unread and last-message
 * bookkeeping lives on [IngestBookkeeping]. A narrow port so [MessageIngestPipeline] is unit-testable
 * against [InMemoryIngestStore]. Mirrors the subset of `PersistenceStoreProtocol` the ingest path
 * calls. Every date is epoch seconds.
 */
interface IngestStore : IngestBookkeeping {
    /** The contact whose public key starts with [senderKeyPrefix] on [radioID], or null if unknown. */
    suspend fun resolveContact(radioID: Uuid, senderKeyPrefix: ByteArray): ResolvedContact?

    /** The local channel at the firmware slot [channelIndex] on [radioID], or null (phantom channel). */
    suspend fun resolveChannel(radioID: Uuid, channelIndex: Int): ResolvedChannel?

    /**
     * Correlates an incoming message with a stored RX-log entry to recover path data. Channel messages
     * match on ([radioID], [channelIndex], [senderTimestamp]); direct messages ([channelIndex] null) fall
     * back to the sender prefix byte within a recent window. Returns [defaultPathLength] and null nodes
     * on a miss.
     */
    suspend fun lookupRxLog(
        radioID: Uuid,
        channelIndex: Int?,
        senderTimestamp: Long,
        senderKeyPrefix: ByteArray?,
        defaultPathLength: Int,
    ): RxLogCorrelation

    /** Whether a message with this content-based [deduplicationKey] already persists for [radioID]. */
    suspend fun isDuplicateMessage(deduplicationKey: String, radioID: Uuid): Boolean

    /** Inserts the received message row. */
    suspend fun saveMessage(message: MessageDto)
}
