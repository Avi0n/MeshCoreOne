package one.meshcore.shared.channel

import kotlinx.coroutines.CancellationException
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.settings.PersistentLogger

/**
 * The connect-path channel phase: one sync pass, then a single retry of the slots whose failures a retry
 * could recover. Mirrors the channel phase of the Swift `SyncCoordinator`, which retries the sync result's
 * retryable indices once before deciding the phase is clean.
 *
 * A dropped retry is not cosmetic: a slot that timed out has no local row, so every inbound message on it
 * is treated as an unconfigured channel and suppressed until some later connect happens to read it.
 */
object ChannelSyncRetry {
    /**
     * Returns the phase's effective result: complete when the retry recovered every retryable slot and the
     * first pass left no non-retryable failure behind, otherwise carrying the failures still outstanding
     * (whose [ChannelSyncResult.retryableIndices] are the slots a further attempt could still recover).
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun sync(
        service: ChannelService,
        radioID: Uuid,
        maxChannels: Int,
        usePipelinedRead: Boolean,
        logger: PersistentLogger? = null,
    ): ChannelSyncResult {
        val initial = service.syncChannels(radioID, maxChannels, usePipelinedRead)
        val retryableIndices = initial.retryableIndices
        if (initial.isComplete || retryableIndices.isEmpty()) return initial

        logger?.info(retryingFailedChannelsCountLine(retryableIndices.size))
        val retry = try {
            service.retryFailedChannels(radioID, retryableIndices)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            logger?.warning(channelRetryFailedLine(error.message ?: error.toString()))
            // A thrown retry leaves every slot it was asked to recover still outstanding, and still
            // retryable — the same shape Swift synthesizes so a later attempt sees the full set.
            ChannelSyncResult(
                channelsSynced = 0,
                errors = retryableIndices.map {
                    ChannelSyncError(it, ChannelSyncErrorType.Timeout, error.message.orEmpty())
                },
            )
        }

        // The first pass's non-retryable failures survive the retry: it never re-read those slots.
        val hasNonRetryableErrors = initial.errors.size > initial.retryableIndices.size
        val merged = ChannelSyncResult(
            channelsSynced = initial.channelsSynced + retry.channelsSynced,
            errors = initial.errors.filterNot { it.isRetryable } + retry.errors,
        )
        if (retry.isComplete && !hasNonRetryableErrors) {
            logger?.info(retryRecoveredChannelsLine(retry.channelsSynced))
        } else {
            if (hasNonRetryableErrors) logger?.warning(CHANNELS_NON_RETRYABLE_NOT_CLEAN)
            logger?.warning(channelsStillFailingAfterRetryLine(retry.errors.map { it.index }))
        }
        return merged
    }
}
