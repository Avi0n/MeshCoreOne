package one.meshcore.shared.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Named domain constants for the chat layer. Values and units are frozen against the iOS source so a
 * cross-platform diff can compare them by name. Times are in seconds
 * unless the name says otherwise.
 */
object ChatConstants {
    /** Same-sender cluster reorder window: consecutive same-sender rows this close re-sort by send time. */
    const val SAME_SENDER_REORDER_WINDOW_SECONDS: Double = 5.0

    /** Send-time gap that breaks timestamp/sender-name grouping. */
    const val MESSAGE_GROUPING_GAP_SECONDS: Long = 300

    /**
     * Wait after the message-actions sheet starts dismissing before raising the composer.
     * A focus request issued while the sheet is still animating is lost. iOS
     * `MessageActionsPresentation.dismissalDelay`.
     */
    const val MESSAGE_ACTIONS_DISMISSAL_MS: Long = 300

    /** A sender timestamp more than this far in the future (5 minutes) is invalid and corrected. */
    const val TIMESTAMP_TOLERANCE_FUTURE_SECONDS: Double = 300.0

    /** A sender timestamp more than this far in the past (6 months) is invalid and corrected. */
    const val TIMESTAMP_TOLERANCE_PAST_SECONDS: Double = 15_552_000.0

    /** DM text byte limit (UTF-8), matching the firmware buffer. */
    const val MAX_DIRECT_MESSAGE_LENGTH_BYTES: Int = 150

    /** Firmware `char[32]` name field minus the null terminator. */
    const val MAX_USABLE_NAME_BYTES: Int = 31

    /** UTF-8 length of the `": "` between a channel sender name and the user text. */
    const val CHANNEL_SENDER_PREFIX_SEPARATOR_UTF8_COUNT: Int = 2

    /**
     * Composed `"name: text"` UTF-8 max. Last size that stays in 9 AES blocks so Region + 1 hop
     * still fits [RX_LOG_HEARD_REPEAT_FRAME_BUDGET].
     */
    const val MAX_CHANNEL_MESSAGE_TOTAL_LENGTH_BYTES: Int = 139

    /**
     * Heard-repeat RX-log budget. A Region 1-hop GRP_TXT must fit this even though live frames
     * are [MAX_FRAME_SIZE].
     */
    const val RX_LOG_HEARD_REPEAT_FRAME_BUDGET: Int = 172
    const val RX_LOG_PUSH_OVERHEAD_BYTES: Int = 3
    const val PACKET_HEADER_BYTES: Int = 2
    const val TRANSPORT_CODE_BYTES: Int = 4
    const val MIN_HEARD_REPEAT_PATH_BYTES: Int = 1
    const val GROUP_CHANNEL_HASH_BYTES: Int = 1
    const val GROUP_PLAINTEXT_HEADER_BYTES: Int = 5
    const val CIPHER_MAC_SIZE: Int = 2
    const val CIPHER_BLOCK_SIZE: Int = 16
    const val MAX_FRAME_SIZE: Int = 176

    /** Total DM send attempts in the app-layer retry loop (4 direct + 1 flood). */
    const val MAX_ATTEMPTS: Int = 5

    /** Attempts allowed after switching to flood routing. */
    const val MAX_FLOOD_ATTEMPTS: Int = 1

    /** Direct attempts before the loop resets the path to flood. */
    const val FLOOD_AFTER: Int = 4

    /** Floor for the device-suggested ACK timeout, in seconds. */
    const val MIN_TIMEOUT_SECONDS: Double = 0.0

    /** Give-up floor / post-loop grace for a DM awaiting its end-to-end ACK, in seconds. */
    const val ACK_GIVE_UP_WINDOW_SECONDS: Double = 30.0

    /** ACK expiry check period and the pre-send timeout floor, in seconds. */
    const val ACK_CHECK_INTERVAL_SECONDS: Double = 5.0

    /** Multiplier applied to the firmware's suggested timeout to derive the ACK wait. */
    const val ACK_TIMEOUT_MULTIPLIER: Double = 1.2

    /** Public key length in bytes, mirroring the iOS `ProtocolLimits.publicKeySize`. */
    const val PUBLIC_KEY_SIZE_BYTES: Int = 32

    /** Sender public-key prefix carried on the DM wire, in bytes. */
    const val PUBLIC_KEY_PREFIX_BYTES: Int = 6

    /** Encoded `path_len` byte meaning "flood routing", mirroring the firmware `floodPathSentinel`. */
    const val FLOOD_PATH_SENTINEL: Int = 0xFF

    /** In-loop pool-backoff retries before re-throwing the transient device error. */
    const val POOL_BACKOFF_ATTEMPT_CAP: Int = 3

    /** First pool-backoff retry delay, in seconds. */
    const val POOL_BACKOFF_BASE_DELAY_SECONDS: Double = 0.5

    /** Exponential growth factor applied to the pool-backoff delay per attempt. */
    const val POOL_BACKOFF_EXPONENT_BASE: Double = 2.0

    /** Lower bound of the pool-backoff multiplicative jitter. */
    const val POOL_BACKOFF_JITTER_MIN: Double = 0.8

    /** Upper bound of the pool-backoff multiplicative jitter. */
    const val POOL_BACKOFF_JITTER_MAX: Double = 1.2

    /** Cap user text so `"name: " + text` fits [MAX_CHANNEL_MESSAGE_TOTAL_LENGTH_BYTES]. */
    fun maxChannelMessageLength(nodeNameByteCount: Int): Int =
        (MAX_CHANNEL_MESSAGE_TOTAL_LENGTH_BYTES - nodeNameByteCount - CHANNEL_SENDER_PREFIX_SEPARATOR_UTF8_COUNT)
            .coerceAtLeast(0)

    /**
     * Same cap from a live node name. A missing name uses [MAX_USABLE_NAME_BYTES] so send cannot
     * overflow once firmware prepends the real name.
     */
    fun maxChannelMessageLength(nodeName: String?): Int {
        val nameBytes = nodeName?.encodeToByteArray()?.size ?: MAX_USABLE_NAME_BYTES
        return maxChannelMessageLength(nameBytes)
    }

    /** Companion 0x88 frame length after AES padding, path, and transport codes. */
    fun groupTextRxLogFrameByteCount(
        composedUTF8Count: Int,
        includesTransportCodes: Boolean,
        pathByteCount: Int,
    ): Int {
        val plaintext = GROUP_PLAINTEXT_HEADER_BYTES + composedUTF8Count
        val padded = ((plaintext + CIPHER_BLOCK_SIZE - 1) / CIPHER_BLOCK_SIZE) * CIPHER_BLOCK_SIZE
        val payload = GROUP_CHANNEL_HASH_BYTES + CIPHER_MAC_SIZE + padded
        val raw = PACKET_HEADER_BYTES +
            (if (includesTransportCodes) TRANSPORT_CODE_BYTES else 0) +
            pathByteCount +
            payload
        return RX_LOG_PUSH_OVERHEAD_BYTES + raw
    }
}

/**
 * Runs [block] after the actions sheet has finished dismissing. iOS
 * `MessageActionsPresentation.dismissalDelay` for Send DM, Block Sender, and Path Map.
 */
fun CoroutineScope.launchAfterMessageActionsDismiss(block: suspend () -> Unit): Job = launch {
    delay(ChatConstants.MESSAGE_ACTIONS_DISMISSAL_MS)
    block()
}
