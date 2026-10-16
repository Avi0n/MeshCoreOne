package one.meshcore.shared.connection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import one.meshcore.shared.contact.waitForAdvertIdle

/**
 * Schedules the bounded channel-only retry for [indices], the slots the connect pass and its in-phase
 * retry both left unread, mirroring the Swift `scheduleChannelOnlyRetry`. Contacts and messages stay out
 * of it: the connection is usable, only some channel rows are missing.
 *
 * A missing row is not cosmetic. Every inbound message on that slot is ingested as an unconfigured
 * channel — saved with no unread count, no notification and no conversation — so the traffic is silently
 * lost to the user until some later connect happens to read the slot.
 *
 * Only the connect path schedules this, matching Swift: the resync loop replays the whole sync, channel
 * phase included, on every attempt.
 */
internal fun ConnectionManager.scheduleChannelOnlyRetry(container: ServiceContainer, indices: List<Int>) {
    val initialIndices = indices.distinct().sorted()
    if (initialIndices.isEmpty()) return
    cancelChannelRetry()
    channelRetryJob = scope.launch {
        var pending = initialIndices
        var attempt = 1
        while (attempt <= channelRetryPolicy.maxAttempts && pending.isNotEmpty()) {
            delay(channelRetryPolicy.backoff(attempt))
            // The same fence the resync loop keeps: a retry orphaned by a disconnect or by a later
            // connection must not read a radio this manager has let go.
            if (!isSyncLoopValid(container)) break
            logger.info(
                channelOnlyRetryAttemptLine(attempt, channelRetryPolicy.maxAttempts, pending.size),
            )
            logger.info(CHANNEL_ONLY_RETRY_SYNCING)
            // The connection is already Ready here, so the connection rung alone would leave the pill
            // hidden while the radio is being read; Swift raises the same activity for this retry.
            startSyncActivity(container)
            val next = retryChannelSlotsOnce(container, pending)
            when {
                next == null -> {
                    logger.warning(CHANNEL_ONLY_RETRY_NON_RETRYABLE)
                    pending = emptyList()
                }
                next.isEmpty() -> {
                    logger.info(CHANNEL_ONLY_RETRY_RECOVERED)
                    pending = emptyList()
                }
                else -> pending = next
            }
            endSyncActivity(container, succeeded = pending.isEmpty())
            attempt++
        }
        if (pending.isNotEmpty()) {
            logger.warning(channelOnlyRetryExhaustedLine(pending.size))
        }
    }
}

internal suspend fun ConnectionManager.retryChannelSlotsOnce(
    container: ServiceContainer,
    indices: List<Int>,
): List<Int>? = readChannelRetrySlots(
    waitForAdvertIdle = { container.advertContactSyncClaim.waitForAdvertIdle() },
    readSlots = { container.liveSync.retryChannelSlots(indices).retryableIndices },
    logWarning = logger::warning,
)

// Null stops the caller's loop. A thrown read is non-retryable. An advert-wait timeout logs the
// wait line and skips the failure line.
@Suppress("TooGenericExceptionCaught")
internal suspend fun readChannelRetrySlots(
    waitForAdvertIdle: suspend () -> Boolean,
    readSlots: suspend () -> List<Int>,
    logWarning: (String) -> Unit,
): List<Int>? {
    return try {
        if (!waitForAdvertIdle()) {
            logWarning(CHANNEL_ONLY_RETRY_ADVERT_WAIT_TIMED_OUT)
            return null
        }
        readSlots()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        logWarning(channelOnlyRetryFailedLine(error.message ?: error.toString()))
        null
    }
}

internal fun ConnectionManager.cancelChannelRetry() {
    channelRetryJob?.cancel()
    channelRetryJob = null
}
