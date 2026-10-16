package one.meshcore.shared.tools

import one.meshcore.shared.meshcore.RegionResolveHit
import one.meshcore.shared.meshcore.RegionResolveRow
import one.meshcore.shared.meshcore.RxLogDecryptKey
import one.meshcore.shared.meshcore.RxLogDecryptResult
import one.meshcore.shared.meshcore.RxLogDecryptRow
import one.meshcore.shared.model.RouteType

private const val BYTE_MASK = 0xFF

/** On-wire DM: `[destHash:1][srcHash:1]…` — sender prefix is the second byte. */
private const val DM_SRC_HASH_OFFSET = 1

internal data class PayloadDecryptOutcome(
    val channelIndex: Int?,
    val channelName: String?,
    val decryptStatus: DecryptStatus,
    val decodedText: String?,
    val senderTimestamp: Long?,
)

private val NOT_APPLICABLE_OUTCOME = PayloadDecryptOutcome(null, null, DecryptStatus.NOT_APPLICABLE, null, null)
private val CHANNEL_PENDING_OUTCOME = PayloadDecryptOutcome(null, null, DecryptStatus.PENDING, null, null)
private val CHANNEL_NO_KEY_OUTCOME = PayloadDecryptOutcome(null, null, DecryptStatus.NO_MATCHING_KEY, null, null)
private val DM_NO_KEY_OUTCOME = PayloadDecryptOutcome(null, null, DecryptStatus.DM_NO_MATCHING_KEY, null, null)

/**
 * What one row asks of the protocol implementation: a row to submit, or a settled outcome for a row
 * no key material applies to. Planning first is what lets a whole re-derivation pass cross the
 * bridge once instead of once per row.
 */
internal sealed interface DecryptPlan {
    class Submit(val row: RxLogDecryptRow) : DecryptPlan

    class Settled(val outcome: PayloadDecryptOutcome) : DecryptPlan
}

internal fun pathNodesToBytes(nodes: List<Int>): ByteArray =
    ByteArray(nodes.size) { index -> (nodes[index] and BYTE_MASK).toByte() }

private fun isDirectTextMessage(payloadType: Int, routeType: Int): Boolean {
    if (payloadType != RxLogPayloadType.TEXT_MESSAGE) return false
    val route = RouteType.fromRaw(routeType)
    return route == RouteType.DIRECT || route == RouteType.TC_DIRECT
}

/**
 * Decides what one row needs. A group packet always goes down with whatever secrets are known, so
 * the framing checks stay in the one protocol implementation; a DM only goes down once a sender key
 * for its prefix byte is on hand, which mirrors iOS `RxLogService.tryDecryptDirectMessage`.
 */
internal fun planDecrypt(
    payloadType: Int,
    routeType: Int,
    payload: ByteArray,
    decode: RxLogDecodeCache.DecodeSnapshot,
): DecryptPlan = when {
    payloadType == RxLogPayloadType.GROUP_TEXT || payloadType == RxLogPayloadType.GROUP_DATA ->
        DecryptPlan.Submit(
            RxLogDecryptRow.Channel(
                payload = payload,
                keys = decode.channelSecrets.map { RxLogDecryptKey(it.key, it.value) },
            ),
        )
    isDirectTextMessage(payloadType, routeType) -> planDirectDecrypt(payload, decode)
    else -> DecryptPlan.Settled(NOT_APPLICABLE_OUTCOME)
}

private fun planDirectDecrypt(payload: ByteArray, decode: RxLogDecodeCache.DecodeSnapshot): DecryptPlan {
    val privateKey = decode.myPrivateKey
    val senderPrefix = payload.getOrNull(DM_SRC_HASH_OFFSET)?.toInt()?.and(BYTE_MASK)
    val candidates = senderPrefix?.let { decode.contactX25519ByPrefix[it] }
    return when {
        privateKey == null || candidates.isNullOrEmpty() -> DecryptPlan.Settled(DM_NO_KEY_OUTCOME)
        else -> DecryptPlan.Submit(
            RxLogDecryptRow.Direct(
                payload = payload,
                privateKey = privateKey,
                keys = candidates.mapIndexed { index, key -> RxLogDecryptKey(index, key) },
            ),
        )
    }
}

/**
 * Submits every planned row in one batch and returns an outcome per plan, in order. Rows that need
 * no key material never reach the boundary.
 */
internal fun resolveDecryptPlans(
    plans: List<DecryptPlan>,
    decode: RxLogDecodeCache.DecodeSnapshot,
): List<PayloadDecryptOutcome> {
    val rows = plans.filterIsInstance<DecryptPlan.Submit>().map { it.row }
    val results = if (rows.isEmpty()) emptyList() else decode.decryptor.decrypt(rows)
    var answered = 0
    return plans.map { plan ->
        when (plan) {
            is DecryptPlan.Settled -> plan.outcome
            is DecryptPlan.Submit -> outcomeOf(plan.row, results.getOrNull(answered++), decode)
        }
    }
}

private fun outcomeOf(
    row: RxLogDecryptRow,
    result: RxLogDecryptResult?,
    decode: RxLogDecodeCache.DecodeSnapshot,
): PayloadDecryptOutcome = when (row) {
    is RxLogDecryptRow.Channel -> channelOutcome(result, decode)
    is RxLogDecryptRow.Direct -> directOutcome(result)
}

/**
 * A channel row the bridge could not answer stays [DecryptStatus.PENDING] rather than
 * [DecryptStatus.NO_MATCHING_KEY]: "no key opened it" is a claim only a completed attempt can make.
 */
private fun channelOutcome(
    result: RxLogDecryptResult?,
    decode: RxLogDecodeCache.DecodeSnapshot,
): PayloadDecryptOutcome = when (result) {
    is RxLogDecryptResult.Success -> PayloadDecryptOutcome(
        channelIndex = result.keyIndex,
        channelName = decode.channelNames[result.keyIndex] ?: "Channel ${result.keyIndex}",
        decryptStatus = DecryptStatus.SUCCESS,
        decodedText = result.text,
        senderTimestamp = result.senderTimestamp,
    )
    RxLogDecryptResult.NoMatchingKey -> CHANNEL_NO_KEY_OUTCOME
    else -> CHANNEL_PENDING_OUTCOME
}

private fun directOutcome(result: RxLogDecryptResult?): PayloadDecryptOutcome = when (result) {
    is RxLogDecryptResult.Success -> PayloadDecryptOutcome(
        channelIndex = null,
        channelName = null,
        decryptStatus = DecryptStatus.SUCCESS,
        decodedText = result.text,
        senderTimestamp = result.senderTimestamp,
    )
    else -> DM_NO_KEY_OUTCOME
}

/**
 * What one row asks of the region matcher. Takes the header fields rather than a parsed frame so
 * back-fill can replay the firmware HMAC input from a persisted row. A packet with no transport code
 * names no region, so it has nothing to ask and never reaches the boundary; how many bytes of the
 * field are readable is decided below it.
 */
internal fun planRegion(transportCode: ByteArray?, payloadTypeBits: Int, payload: ByteArray): RegionResolveRow? =
    transportCode?.let { RegionResolveRow(payload = payload, payloadTypeBits = payloadTypeBits, transportCode = it) }

/**
 * Submits every planned row in one batch and returns a region per plan, in order. An empty
 * known-regions list still projects [RegionResolveHit.NONE] for planned rows so writers can
 * clear a sticky unique label, matching iOS `resolveRegionStorage` on an empty cache.
 */
internal fun resolveRegionHits(
    plans: List<RegionResolveRow?>,
    decode: RxLogDecodeCache.DecodeSnapshot,
): List<RegionResolveHit?> {
    val rows = plans.filterNotNull()
    return when {
        rows.isEmpty() -> List(plans.size) { null }
        decode.knownRegions.isEmpty() -> plans.map { plan -> plan?.let { RegionResolveHit.NONE } }
        else -> {
            val resolved = decode.regionResolver.resolve(decode.knownRegions, rows)
            var answered = 0
            plans.map { plan -> plan?.let { resolved.getOrNull(answered++) } }
        }
    }
}
