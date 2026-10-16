package one.meshcore.shared.connection

/** iOS `Channel-only retry recovered all pending channels`. */
const val CHANNEL_ONLY_RETRY_RECOVERED = "Channel-only retry recovered all pending channels"

/** iOS `Channel-only retry stopped with non-retryable channel errors`. */
const val CHANNEL_ONLY_RETRY_NON_RETRYABLE =
    "Channel-only retry stopped with non-retryable channel errors"

/** iOS `[Sync] State → .syncing(.channels) (channel-only retry)`. */
const val CHANNEL_ONLY_RETRY_SYNCING = "[Sync] State → .syncing(.channels) (channel-only retry)"

fun channelOnlyRetryAttemptLine(attempt: Int, maxAttempts: Int, pendingCount: Int): String =
    "Channel-only retry $attempt/$maxAttempts for $pendingCount channel(s)"

fun channelOnlyRetryExhaustedLine(pendingCount: Int): String =
    "Channel-only retry exhausted with $pendingCount retryable channel(s) still pending"

/** iOS `Channel-only retry failed: \(error.localizedDescription)`. */
fun channelOnlyRetryFailedLine(detail: String): String = "Channel-only retry failed: $detail"
