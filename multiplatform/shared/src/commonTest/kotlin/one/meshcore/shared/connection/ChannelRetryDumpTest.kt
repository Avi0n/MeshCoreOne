package one.meshcore.shared.connection

import kotlin.test.Test
import kotlin.test.assertEquals

class ChannelRetryDumpTest {
    @Test
    fun channelOnlyRetryLinesMatchIos() {
        assertEquals(
            "Channel-only retry 1/2 for 3 channel(s)",
            channelOnlyRetryAttemptLine(1, 2, 3),
        )
        assertEquals(CHANNEL_ONLY_RETRY_RECOVERED, "Channel-only retry recovered all pending channels")
        assertEquals(
            CHANNEL_ONLY_RETRY_NON_RETRYABLE,
            "Channel-only retry stopped with non-retryable channel errors",
        )
        assertEquals(
            "Channel-only retry exhausted with 2 retryable channel(s) still pending",
            channelOnlyRetryExhaustedLine(2),
        )
        assertEquals(
            "[Sync] State → .syncing(.channels) (channel-only retry)",
            CHANNEL_ONLY_RETRY_SYNCING,
        )
        assertEquals(
            "Channel-only retry failed: link drop",
            channelOnlyRetryFailedLine("link drop"),
        )
    }
}
