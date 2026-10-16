package one.meshcore.shared.persistence

import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.tools.RxLogEntry
import one.meshcore.shared.tools.RxLogPayloadType

/**
 * Stamps flood region and hop hashes that resolved after a message was already stored. The RX log
 * re-derives its rows whenever a secret lands late, and a live packet can arrive after get_msg; without
 * this the chat footer stays on the empty-path "Flood" label and Path Map never appears. Mirrors the
 * message half of the iOS `RxLogService` re-processing pass, plus the hop fields SwiftData can copy
 * from an unsaved insert at ingest.
 *
 * Hop hashes fill only when the message still has none, so a late packet cannot clobber a live
 * path. Region fields copy the RX-log projection whenever it differs — including unique →
 * ambiguous and a cache-empty clear — matching iOS `reprocessRegionEntries` message updates.
 * A decrypted packet matches `senderTimestamp` or, when ingest left that column null,
 * `timestamp`. Flood DMs never decrypt, so they match a recent incoming bubble by the
 * unencrypted srcHash byte instead.
 */
class MessageRegionBackfill(private val messageDao: MessageDao) {
    /** Applies every resolved region and hop path in [entries], returning the message IDs that changed. */
    suspend fun apply(entries: List<RxLogEntry>): List<Uuid> {
        val touched = linkedSetOf<Uuid>()
        for (entry in entries) {
            val matches = entry.regionScopeMatches.ifEmpty { listOfNotNull(entry.regionScope) }
            val hasRegion = entry.regionScope != null || matches.isNotEmpty()
            val hasTransportCode = entry.transportCode?.isNotEmpty() == true
            val hasPath = entry.pathNodes.isNotEmpty()
            if (!hasRegion && !hasPath && !hasTransportCode) continue
            val stamp = entry.senderTimestamp
            val channelIndex = entry.channelIndex
            val applyRegion = hasRegion || hasTransportCode
            val ids = when {
                stamp != null && channelIndex != null ->
                    backfillChannel(entry, channelIndex, stamp, matches, applyRegion)
                stamp != null ->
                    backfillDirect(entry, stamp, matches, applyRegion)
                hasPath && entry.isUnattributedDirectText() ->
                    backfillDirectByPrefixWindow(entry, matches, applyRegion)
                else -> emptyList()
            }
            touched += ids
        }
        return touched.toList()
    }

    private suspend fun backfillChannel(
        entry: RxLogEntry,
        channelIndex: Int,
        stamp: Long,
        matches: List<String>,
        applyRegion: Boolean,
    ): List<Uuid> {
        val targets = messageDao.incomingChannelMessagesForWireTimestamp(
            entry.radioID,
            channelIndex,
            stamp,
            MessageDirection.INCOMING.raw,
        )
        return stampTargets(targets, entry, matches, applyRegion)
    }

    private suspend fun backfillDirect(
        entry: RxLogEntry,
        stamp: Long,
        matches: List<String>,
        applyRegion: Boolean,
    ): List<Uuid> {
        val prefixByte = entry.packetPayload.getOrNull(DM_SENDER_PREFIX_BYTE_OFFSET) ?: return emptyList()
        val targets = messageDao.incomingDirectMessagesForWireTimestamp(
            entry.radioID,
            stamp,
            MessageDirection.INCOMING.raw,
        ).filter { it.senderKeyPrefix?.firstOrNull() == prefixByte }
        return stampTargets(targets, entry, matches, applyRegion)
    }

    /**
     * Flood DMs never decrypt, so the RX-log row has hop hashes but no wire timestamp. Match a recent
     * incoming bubble by the unencrypted srcHash byte — the inverse of ingest's 30s prefix fallback.
     */
    private suspend fun backfillDirectByPrefixWindow(
        entry: RxLogEntry,
        matches: List<String>,
        applyRegion: Boolean,
    ): List<Uuid> {
        val prefixByte = entry.packetPayload.getOrNull(DM_SENDER_PREFIX_BYTE_OFFSET) ?: return emptyList()
        val receivedAtSeconds = entry.receivedAtEpochMs / MILLIS_PER_SECOND
        val windowSeconds = DIRECT_FALLBACK_WINDOW_MS / MILLIS_PER_SECOND
        val targets = messageDao.incomingDirectMessagesAroundCreatedAt(
            entry.radioID,
            receivedAtSeconds - windowSeconds,
            receivedAtSeconds + windowSeconds,
            MessageDirection.INCOMING.raw,
        ).filter { it.senderKeyPrefix?.firstOrNull() == prefixByte }
        return stampTargets(targets, entry, matches, applyRegion)
    }

    private fun RxLogEntry.isUnattributedDirectText(): Boolean =
        payloadType == RxLogPayloadType.TEXT_MESSAGE && channelIndex == null

    private suspend fun stampTargets(
        targets: List<MessageEntity>,
        entry: RxLogEntry,
        matches: List<String>,
        applyRegion: Boolean,
    ): List<Uuid> {
        val hasPath = entry.pathNodes.isNotEmpty()
        val touched = mutableListOf<Uuid>()
        for (row in targets) {
            val next = row.stamped(entry, matches, applyRegion, hasPath) ?: continue
            messageDao.upsert(next)
            touched += row.id
        }
        return touched
    }

    private fun MessageEntity.stamped(
        entry: RxLogEntry,
        matches: List<String>,
        applyRegion: Boolean,
        hasPath: Boolean,
    ): MessageEntity? {
        val stampRegion = applyRegion && (regionScope != entry.regionScope || regionScopeMatches != matches)
        val stampPath = hasPath && isMissingPath()
        if (!stampRegion && !stampPath) return null
        return copy(
            regionScope = if (stampRegion) entry.regionScope else regionScope,
            regionScopeMatches = if (stampRegion) matches else regionScopeMatches,
            pathNodes = if (stampPath) entry.pathNodes.copyOf() else pathNodes,
            pathLength = if (stampPath) entry.pathLength else pathLength,
            routeTypeRawValue = if (stampPath) entry.routeType else routeTypeRawValue,
        )
    }

    private fun MessageEntity.isMissingPath(): Boolean = pathNodes == null || pathNodes.isEmpty()

    private companion object {
        const val MILLIS_PER_SECOND = 1_000.0
    }
}
