package one.meshcore.shared.chat

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Retry timing for [ChatSendQueueService]. Mirrors the Swift `ChatSendQueueConfig`. */
data class ChatSendQueueConfig(
    /** Max wait for a transport-open trigger before silently re-attempting the send. */
    val transportWaitTimeout: Duration = 30.seconds,
    /** Channel-drain attempts before spending a BLE round-trip to disambiguate transient NOT_FOUND. */
    val disambiguateAfterAttempts: Int = 3,
    /** Backstop cap on consecutive `channelExists` throws before the channel drain treats NOT_FOUND as terminal. */
    val maxConsecutiveFetchChannelFailures: Int = 16,
)
