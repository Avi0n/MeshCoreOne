package one.meshcore.shared.tools

import one.meshcore.shared.meshcore.ParsedRxLogData
import one.meshcore.shared.meshcore.RegionResolveHit
import one.meshcore.shared.model.Uuid

/**
 * Build a display row from a parsed RX frame: channel decrypt (try all secrets), DM ECDH decrypt
 * when keys are present, region match on transport code.
 */
fun processRxLogEntry(
    radioID: Uuid,
    data: ParsedRxLogData,
    receivedAtEpochMs: Long,
    decode: RxLogDecodeCache.DecodeSnapshot,
    id: Uuid = newRxLogEntryId(),
): RxLogEntry {
    val payload = data.packetPayload
    val plan = planDecrypt(data.payloadType, data.routeType, payload, decode)
    val outcome = resolveDecryptPlans(listOf(plan), decode).single()
    val hit = resolveRegionHits(
        listOf(planRegion(data.transportCode, data.payloadTypeBits, payload)),
        decode,
    ).single()
    return RxLogEntry(
        id = id,
        radioID = radioID,
        receivedAtEpochMs = receivedAtEpochMs,
        snr = data.snr,
        rssi = data.rssi,
        routeType = data.routeType,
        payloadType = data.payloadType,
        pathLength = data.pathLength,
        packetHash = data.packetHash,
        packetPayloadSize = payload.size,
        decryptStatus = outcome.decryptStatus,
        regionScope = hit?.uniqueName,
        regionScopeMatches = hit?.matches.orEmpty(),
        channelIndex = outcome.channelIndex,
        channelName = outcome.channelName,
        decodedText = outcome.decodedText,
        senderTimestamp = outcome.senderTimestamp,
        rawPayload = data.rawPayload.copyOf(),
        packetPayload = payload.copyOf(),
        pathNodes = pathNodesToBytes(data.pathNodes),
        payloadVersion = data.payloadVersion,
        transportCode = data.transportCode?.copyOf(),
        payloadTypeBits = data.payloadTypeBits,
    )
}

/**
 * Re-derives every entry's plaintext, channel attribution and flood region from current [decode]
 * material in one pass, mirroring iOS `RxLogService.decryptEntry` plus `reprocessRegionEntries`.
 * A row that still fails to decrypt keeps whatever body it already had, so a momentarily empty
 * secret cache never erases a live row. Transport-coded rows always re-resolve region, including
 * unique first-matches, so a later colliding name can rewrite sticky unique to ambiguous.
 * The returned list is positionally aligned with [entries].
 */
fun redecryptRxLogEntries(entries: List<RxLogEntry>, decode: RxLogDecodeCache.DecodeSnapshot): List<RxLogEntry> {
    val plans = entries.map { planDecrypt(it.payloadType, it.routeType, it.packetPayload, decode) }
    val outcomes = resolveDecryptPlans(plans, decode)
    val regionPlans = entries.map { entry ->
        planRegion(entry.transportCode, entry.payloadTypeBits, entry.packetPayload)
    }
    val hits = resolveRegionHits(regionPlans, decode)
    return entries.mapIndexed { index, entry -> entry.merging(outcomes[index], hits[index]) }
}

private fun RxLogEntry.merging(outcome: PayloadDecryptOutcome, hit: RegionResolveHit?): RxLogEntry {
    // A planned hit is the current cache projection, including uniqueName=null ambiguous and
    // NONE after the last matching name is removed. Unplanned rows (no transport code) keep
    // whatever they already stored.
    val scope = if (hit == null) regionScope else hit.uniqueName
    val matches = if (hit == null) regionScopeMatches else hit.matches
    return when (outcome.decodedText) {
        null -> copy(regionScope = scope, regionScopeMatches = matches)
        else -> copy(
            decryptStatus = DecryptStatus.SUCCESS,
            channelIndex = outcome.channelIndex ?: channelIndex,
            channelName = outcome.channelName ?: channelName,
            decodedText = outcome.decodedText,
            senderTimestamp = outcome.senderTimestamp ?: senderTimestamp,
            regionScope = scope,
            regionScopeMatches = matches,
        )
    }
}

fun newRxLogEntryId(): Uuid = Uuid.random()
