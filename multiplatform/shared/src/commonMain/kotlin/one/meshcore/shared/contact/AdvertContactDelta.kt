package one.meshcore.shared.contact

import one.meshcore.shared.connection.ContactWatermarkUse
import one.meshcore.shared.connection.PRUNE_FREE_FULL_FETCH_SINCE
import one.meshcore.shared.connection.contactWatermarkUse
import one.meshcore.shared.connection.incrementalContactSince
import one.meshcore.shared.runThrowableResult

/**
 * One advert-driven GET_CONTACTS round: claim the radio, honor the watermark gate, persist the
 * new stamp. Mirrors iOS `SyncCoordinator.performAdvertContactSync`.
 */
internal suspend fun runAdvertContactDelta(delta: AdvertContactDeltaRun): AdvertContactSyncOutcome {
    if (!delta.claim.tryBeginAdvert()) return AdvertContactSyncOutcome.BUSY
    try {
        val watermarkUse = contactWatermarkUse(delta.watermark, delta.referenceNowSeconds)
        val hasCompletedFullSync = delta.claim.hasCompletedFullContactSync()
        val isReady = watermarkUse !is ContactWatermarkUse.None || hasCompletedFullSync
        if (!isReady) return AdvertContactSyncOutcome.NOT_READY

        val recoveredInvalid = delta.claim.hasRecoveredInvalidWatermark()
        val fetch = advertContactFetch(watermarkUse, delta.fullRefetch, recoveredInvalid)
        return syncedAdvertContact(delta.claim, fetch, delta.sync, delta.persistWatermark)
    } finally {
        delta.claim.endAdvert()
    }
}

private suspend fun syncedAdvertContact(
    claim: AdvertContactSyncClaim,
    fetch: AdvertContactFetch,
    sync: suspend (since: Long) -> Long,
    persistWatermark: suspend (Long) -> Unit,
): AdvertContactSyncOutcome = runThrowableResult({ AdvertContactSyncOutcome.FAILED }) {
    val last = sync(fetch.since)
    if (fetch.recoversInvalidWatermark) claim.markInvalidWatermarkRecovered()
    if (last > 0L) persistWatermark(last)
    AdvertContactSyncOutcome.SYNCED
}

private data class AdvertContactFetch(val since: Long, val recoversInvalidWatermark: Boolean = false)

private fun advertContactFetch(
    watermarkUse: ContactWatermarkUse,
    fullRefetch: Boolean,
    recoveredInvalid: Boolean,
): AdvertContactFetch {
    if (fullRefetch) return AdvertContactFetch(PRUNE_FREE_FULL_FETCH_SINCE)
    return when (watermarkUse) {
        ContactWatermarkUse.None -> AdvertContactFetch(PRUNE_FREE_FULL_FETCH_SINCE)
        is ContactWatermarkUse.Incremental -> AdvertContactFetch(incrementalContactSince(watermarkUse.watermark))
        is ContactWatermarkUse.Invalid -> {
            if (recoveredInvalid) {
                AdvertContactFetch(incrementalContactSince(watermarkUse.stored))
            } else {
                AdvertContactFetch(PRUNE_FREE_FULL_FETCH_SINCE, recoversInvalidWatermark = true)
            }
        }
    }
}

internal data class AdvertContactDeltaRun(
    val claim: AdvertContactSyncClaim,
    val watermark: Long,
    val fullRefetch: Boolean,
    val referenceNowSeconds: Long,
    val sync: suspend (since: Long) -> Long,
    val persistWatermark: suspend (Long) -> Unit,
)
