package one.meshcore.shared.channel

import kotlin.test.Test
import kotlin.test.assertEquals

class ChannelServiceRetryDumpTest {
    @Test
    fun retryLinesMatchIos() {
        assertEquals("Retrying 2 failed channels", retryingFailedChannelsCountLine(2))
        assertEquals(
            "Retrying 2 failed channels: [1, 3]",
            retryingFailedChannelsLine(listOf(1, 3)),
        )
        assertEquals("Retry succeeded for channel 1", retrySucceededForChannelLine(1))
        assertEquals(
            "Retry failed for channel 3: send timeout",
            retryFailedForChannelLine(3, "send timeout"),
        )
        assertEquals(
            "Retry circuit breaker open: 2 consecutive timeouts, stopping retry",
            retryCircuitBreakerOpenLine(2),
        )
        assertEquals("Retry batch persist failed", RETRY_BATCH_PERSIST_FAILED_PREFIX)
        assertEquals("Channel retry failed: link drop", channelRetryFailedLine("link drop"))
        assertEquals("Retry recovered 2 channels", retryRecoveredChannelsLine(2))
        assertEquals(
            "Channels have non-retryable errors, phase not clean",
            CHANNELS_NON_RETRYABLE_NOT_CLEAN,
        )
        assertEquals(
            "Channels still failing after retry: [1, 4]",
            channelsStillFailingAfterRetryLine(listOf(1, 4)),
        )
    }
}
