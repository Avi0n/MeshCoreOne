package one.meshcore.shared.contact

/**
 * Installs the advert-driven GET_CONTACTS handler. When set, 0x80 keys (known and unknown)
 * queue for a debounced round; unknown keys skip the per-key `getContact`. Mirrors iOS
 * `setDeltaSyncHandler`.
 */
fun ContactService.setDeltaSyncHandler(handler: (suspend (Boolean) -> AdvertContactSyncOutcome)?) =
    advertDeltaSchedule.setDeltaSyncHandler(handler)

/**
 * Runs one advert delta round if a handler is installed and work is pending. Tests call this
 * instead of waiting out the debounce.
 */
suspend fun ContactService.runAdvertDeltaSync() = advertDeltaRound.runAdvertDeltaSync()

internal fun ContactService.hasPendingDeltaSyncWork(): Boolean = advertDeltaRound.hasPendingDeltaSyncWork()

internal suspend fun ContactService.hasPendingAdvertKey(publicKey: ByteArray): Boolean =
    advertDeltaSchedule.hasPendingAdvertKey(publicKey)

internal suspend fun ContactService.hasPendingPathKey(publicKey: ByteArray): Boolean =
    advertDeltaSchedule.hasPendingPathKey(publicKey)

internal suspend fun ContactService.hasPendingDeletedKey(publicKey: ByteArray): Boolean =
    advertDeltaSchedule.hasPendingDeletedKey(publicKey)
