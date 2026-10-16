package one.meshcore.shared.persistence

import one.meshcore.shared.chat.RxLogCorrelation
import one.meshcore.shared.meshcore.RegionResolveHit
import one.meshcore.shared.meshcore.RegionResolveRow
import one.meshcore.shared.meshcore.RegionResolver
import one.meshcore.shared.meshcore.platformRegionResolver
import one.meshcore.shared.model.RouteType
import one.meshcore.shared.model.Uuid
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Matches an incoming message to the packet the RX log already recorded for it, recovering the routing
 * path, route type and flood region that the message frame itself does not carry. Mirrors the lookup in
 * the iOS `SyncCoordinator` handler helpers.
 *
 * A miss is ordinary rather than exceptional — the packet may never have been logged — and returns the
 * caller's own reported path length with everything else null.
 */
@OptIn(ExperimentalTime::class)
class RxLogCorrelator(
    private val rxLogEntryDao: RxLogEntryDao,
    private val nowEpochMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val regionNames: suspend () -> List<String> = { emptyList() },
    private val regionResolver: RegionResolver = platformRegionResolver(),
) {
    suspend fun correlate(
        radioID: Uuid,
        channelIndex: Int?,
        senderTimestamp: Long,
        senderKeyPrefix: ByteArray?,
        defaultPathLength: Int,
    ): RxLogCorrelation {
        val packet = packetFor(radioID, channelIndex, senderTimestamp)
            ?: directPacketBySenderPrefix(radioID, channelIndex, senderKeyPrefix)
        return packet?.asCorrelation() ?: RxLogCorrelation(
            pathNodes = null,
            pathLength = defaultPathLength,
            routeType = null,
            regionScope = null,
            regionScopeMatches = emptyList(),
        )
    }

    private suspend fun packetFor(radioID: Uuid, channelIndex: Int?, senderTimestamp: Long): RxLogEntryEntity? =
        if (channelIndex != null) {
            rxLogEntryDao.findChannelPacket(radioID, channelIndex, senderTimestamp)
        } else {
            rxLogEntryDao.findDirectPacket(radioID, senderTimestamp)
        }

    /**
     * The direct-message fallback: when the logged row has no sender timestamp yet — decryption may not
     * have reached it — the sender prefix byte carried in the packet payload identifies it instead,
     * bounded by a short window so an older packet from the same sender cannot claim the message.
     */
    private suspend fun directPacketBySenderPrefix(
        radioID: Uuid,
        channelIndex: Int?,
        senderKeyPrefix: ByteArray?,
    ): RxLogEntryEntity? {
        if (channelIndex != null) return null
        val prefixByte = senderKeyPrefix?.firstOrNull() ?: return null
        val since = nowEpochMs() - DIRECT_FALLBACK_WINDOW_MS
        return rxLogEntryDao.findRecentDirectPackets(radioID, since)
            .firstOrNull { it.packetPayload.getOrNull(DM_SENDER_PREFIX_BYTE_OFFSET) == prefixByte }
    }

    private suspend fun RxLogEntryEntity.asCorrelation(): RxLogCorrelation {
        val hit = resolveHit()
        val scope = if (hit != null) hit.uniqueName else regionScope
        val matches = when {
            hit != null -> hit.matches
            else -> listOfNotNull(regionScope)
        }
        return RxLogCorrelation(
            pathNodes = pathNodes,
            pathLength = pathLength,
            routeType = RouteType.fromRaw(routeType),
            regionScope = scope,
            regionScopeMatches = matches,
        )
    }

    /**
     * Re-resolves dual region fields from the stored transport code so ingest can stamp
     * `regionScopeMatches` without a Room column on the RX-log row. A hit is the current
     * cache projection and is applied verbatim, including uniqueName=null ambiguous.
     */
    private suspend fun RxLogEntryEntity.resolveHit(): RegionResolveHit? {
        val code = transportCode ?: return null
        if (packetPayload.isEmpty()) return null
        val names = regionNames()
        if (names.isEmpty()) return RegionResolveHit.NONE
        return regionResolver.resolve(
            names,
            listOf(RegionResolveRow(packetPayload, payloadTypeBits, code)),
        ).singleOrNull()
    }
}

/** How far back the prefix-byte fallback will look for an unattributed packet. */
internal const val DIRECT_FALLBACK_WINDOW_MS = 30_000L

/** Where the sender's key prefix sits in a direct-message packet payload. */
internal const val DM_SENDER_PREFIX_BYTE_OFFSET = 1
