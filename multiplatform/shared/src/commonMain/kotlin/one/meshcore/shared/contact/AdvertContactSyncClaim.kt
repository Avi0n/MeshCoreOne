package one.meshcore.shared.contact

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Spacing between polls while an advert round still holds the radio. */
private const val ADVERT_CLAIM_POLL_MS: Long = 50

/**
 * Mutual exclusion between connect/full contact sync and advert-driven GET_CONTACTS.
 * Mirrors iOS `SyncCoordinator.isSyncInProgress` plus `advertContactSyncActive`:
 * a delta round returns [AdvertContactSyncOutcome.BUSY] while a full sync holds the radio.
 */
class AdvertContactSyncClaim {
    private val mutex = Mutex()
    private var fullSyncActive = false
    private var manualSyncActive = false
    private var advertSyncActive = false
    private var fullContactSyncCompleted = false
    private var invalidWatermarkRecovered = false

    /** Connect and resync hold this for contacts, channels, and backlog. */
    suspend fun setFullSyncActive(active: Boolean) = mutex.withLock {
        fullSyncActive = active
    }

    /** Nodes pull-to-refresh holds this so advert delta returns BUSY. */
    suspend fun setManualSyncActive(active: Boolean) = mutex.withLock {
        manualSyncActive = active
    }

    /**
     * Waits out an advert claim, then marks a user-initiated refresh in the same lock so a delta
     * cannot start in the gap. False when [timeoutMs] elapses while the advert round still holds.
     */
    suspend fun claimManualContactSync(timeoutMs: Long = ADVERT_CLAIM_WAIT_TIMEOUT_MS): Boolean {
        var waitedMs = 0L
        while (true) {
            val claimed = mutex.withLock {
                if (!advertSyncActive) {
                    manualSyncActive = true
                    true
                } else {
                    false
                }
            }
            if (claimed) return true
            if (waitedMs >= timeoutMs) return false
            delay(ADVERT_CLAIM_POLL_MS)
            waitedMs += ADVERT_CLAIM_POLL_MS
        }
    }

    /**
     * Claims the advert round. False when a full sync, manual refresh, or another advert round
     * already holds the radio.
     */
    suspend fun tryBeginAdvert(): Boolean = mutex.withLock {
        if (fullSyncActive || manualSyncActive || advertSyncActive) return@withLock false
        advertSyncActive = true
        true
    }

    suspend fun endAdvert() = mutex.withLock {
        advertSyncActive = false
    }

    /** True while an advert-driven GET_CONTACTS round holds the radio. */
    suspend fun isAdvertActive(): Boolean = mutex.withLock { advertSyncActive }

    /**
     * Stamped after a connect `since == null` contacts phase succeeds, including an empty table that
     * writes no watermark. Advert delta needs that difference to run at all.
     */
    suspend fun markFullContactSyncCompleted() = mutex.withLock {
        fullContactSyncCompleted = true
    }

    suspend fun hasCompletedFullContactSync(): Boolean = mutex.withLock { fullContactSyncCompleted }

    /** One prune-free invalid-watermark recovery per container lifetime. */
    suspend fun markInvalidWatermarkRecovered() = mutex.withLock {
        invalidWatermarkRecovered = true
    }

    suspend fun hasRecoveredInvalidWatermark(): Boolean = mutex.withLock { invalidWatermarkRecovered }

    companion object {
        /**
         * Bound for waiting out an advert-owned contact-sync claim. Above the 180s contact-stream
         * hard timeout so a large table can finish, but a wedged wait cannot hang forever.
         */
        const val ADVERT_CLAIM_WAIT_TIMEOUT_MS: Long = 200_000
    }
}

/**
 * Waits until the advert round releases, or [timeoutMs] elapses. Connect and full sync
 * must not start GET_CONTACTS while a delta round is on the radio.
 */
suspend fun AdvertContactSyncClaim.waitForAdvertIdle(
    timeoutMs: Long = AdvertContactSyncClaim.ADVERT_CLAIM_WAIT_TIMEOUT_MS,
): Boolean {
    var waitedMs = 0L
    while (isAdvertActive()) {
        if (waitedMs >= timeoutMs) return false
        delay(ADVERT_CLAIM_POLL_MS)
        waitedMs += ADVERT_CLAIM_POLL_MS
    }
    return true
}
