package one.meshcore.shared.contact

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.model.Uuid

/**
 * Flushes queued 0x8F deletes, then re-arms when `hasPendingDeltaSyncWork`. The scene already
 * reported active, so this does not re-read [isInForeground]. Mirrors iOS
 * `AdvertisementService.handleReturnToForeground`.
 */
suspend fun ContactService.handleReturnToForeground() = advertDeltaSchedule.handleReturnToForeground()

/**
 * Drops the delta handler, flushes parked 0x8F deletes, stamps queued lastHeard, and clears
 * advert queues. Mirrors iOS `AdvertisementService.stopEventMonitoring`.
 */
suspend fun ContactService.stopAdvertDeltaSync() = advertDeltaSchedule.stopAdvertDeltaSync()

internal suspend fun ContactService.flushPendingDeletedKeys() = advertDeltaSchedule.flushPendingDeletedKeys()

/**
 * Creates a local contact from a pending 0x80 key so a DM that arrives inside the advert
 * delta-sync debounce window can notify and list. Matches [matchingPrefix] against queued
 * advert keys. Exactly one match is required; ambiguous prefixes return null. The key stays
 * queued so the debounced GET_CONTACTS round still reconciles Discover. Mirrors iOS
 * `AdvertisementService.materializeContactForPendingAdvert`.
 */
suspend fun ContactService.materializeContactForPendingAdvert(matchingPrefix: ByteArray, radioID: Uuid): ContactDto? =
    syncHold.materializeContactForPendingAdvert(matchingPrefix, radioID)

internal suspend fun ContactService.recordPendingAdvertKeyLocked(publicKey: ByteArray, receivedAt: Long) =
    advertDeltaRound.recordPendingAdvertKeyLocked(publicKey, receivedAt)

internal suspend fun ContactService.recordPendingAdvertKey(publicKey: ByteArray, receivedAt: Long) =
    advertDeltaRound.recordPendingAdvertKey(publicKey, receivedAt)

internal fun ContactService.takeAdvertReceiveTimesLocked(keys: List<ByteArray>): Map<String, Long> =
    advertDeltaRound.takeAdvertReceiveTimesLocked(keys)

/**
 * Stamps phone recency for drained 0x80 keys that now have a row. Unknown contacts get
 * lastHeard null from [ContactUpsert.insert]; without this a stale radio lastModified makes
 * [ContactDto.matchesStaleNodePrune] true right after hear. Mirrors iOS `stampAdvertReceiveTimes`.
 */
internal suspend fun ContactService.stampAdvertReceiveTimes(
    drainedAdvert: List<ByteArray>,
    receiveTimes: Map<String, Long>,
    radioID: Uuid?,
) = advertDeltaFollowUp.stampAdvertReceiveTimes(drainedAdvert, receiveTimes, radioID)
