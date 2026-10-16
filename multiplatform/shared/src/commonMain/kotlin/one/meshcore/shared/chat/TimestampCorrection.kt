package one.meshcore.shared.chat

/** Result of [correctTimestampIfNeeded]: the timestamp to persist and whether a correction was applied. */
data class TimestampCorrection(val correctedTimestamp: Long, val wasCorrected: Boolean)

/**
 * Corrects invalid timestamps from senders with broken clocks. A sender timestamp is invalid if it is
 * more than [ChatConstants.TIMESTAMP_TOLERANCE_FUTURE_SECONDS] in the future or more than
 * [ChatConstants.TIMESTAMP_TOLERANCE_PAST_SECONDS] in the past relative to [receiveTimeEpochSeconds];
 * an invalid value is replaced with the receive time. The caller preserves the original wire value
 * separately (for RX-log correlation and reaction hashing). Mirrors Swift
 * `SyncCoordinator.correctTimestampIfNeeded`; the arithmetic is in seconds (Double) so a receive time
 * near the Unix epoch cannot underflow.
 */
fun correctTimestampIfNeeded(timestamp: Long, receiveTimeEpochSeconds: Double): TimestampCorrection {
    val timestampSeconds = timestamp.toDouble()
    val tooFarInFuture = timestampSeconds > receiveTimeEpochSeconds + ChatConstants.TIMESTAMP_TOLERANCE_FUTURE_SECONDS
    val tooFarInPast = timestampSeconds < receiveTimeEpochSeconds - ChatConstants.TIMESTAMP_TOLERANCE_PAST_SECONDS

    return if (tooFarInFuture || tooFarInPast) {
        TimestampCorrection(receiveTimeEpochSeconds.toLong(), true)
    } else {
        TimestampCorrection(timestamp, false)
    }
}
