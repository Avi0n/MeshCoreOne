package one.meshcore.shared.channel

/** iOS `SyncCoordinator` `Retrying \(retryableIndices.count) failed channels`. */
fun retryingFailedChannelsCountLine(count: Int): String = "Retrying $count failed channels"

/** iOS `ChannelService.retryFailedChannels` start dump. */
fun retryingFailedChannelsLine(indices: List<Int>): String = "Retrying ${indices.size} failed channels: $indices"

/** iOS `Retry succeeded for channel \(index)`. */
fun retrySucceededForChannelLine(index: Int): String = "Retry succeeded for channel $index"

/** iOS `Retry failed for channel \(index): \(syncError.description)`. */
fun retryFailedForChannelLine(index: Int, description: String): String = "Retry failed for channel $index: $description"

/** iOS `Retry circuit breaker open: \(consecutiveTimeouts) consecutive timeouts, stopping retry`. */
fun retryCircuitBreakerOpenLine(consecutiveTimeouts: Int): String =
    "Retry circuit breaker open: $consecutiveTimeouts consecutive timeouts, stopping retry"

/** iOS `Retry batch persist failed:` prefix. */
const val RETRY_BATCH_PERSIST_FAILED_PREFIX = "Retry batch persist failed"

/** iOS `Channel retry failed:` prefix. */
const val CHANNEL_RETRY_FAILED_PREFIX = "Channel retry failed"

fun channelRetryFailedLine(detail: String): String = "$CHANNEL_RETRY_FAILED_PREFIX: $detail"

/** iOS `Retry recovered \(retryResult.channelsSynced) channels`. */
fun retryRecoveredChannelsLine(count: Int): String = "Retry recovered $count channels"

/** iOS `Channels have non-retryable errors, phase not clean`. */
const val CHANNELS_NON_RETRYABLE_NOT_CLEAN = "Channels have non-retryable errors, phase not clean"

/** iOS `Channels still failing after retry: \(retryResult.errors.map(\.index))`. */
fun channelsStillFailingAfterRetryLine(indices: List<Int>): String = "Channels still failing after retry: $indices"
