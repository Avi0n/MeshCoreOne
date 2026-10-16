package one.meshcore.shared.channel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Mirrors the `ChannelSyncError`/`ChannelSyncResult` cases in the iOS `ChannelServiceTests`: retry
 * eligibility, the separate send-timeout count, and the retryable-index filter.
 */
class ChannelSyncResultTest {
    @Test
    fun timeoutIsRetryable() {
        val error = ChannelSyncError(0, ChannelSyncErrorType.Timeout, "Timeout")
        assertTrue(error.isRetryable)
    }

    @Test
    fun sendTimeoutIsRetryableAndCountedSeparately() {
        val error = ChannelSyncError(0, ChannelSyncErrorType.SendTimeout, "Send timed out")
        val result = ChannelSyncResult(channelsSynced = 0, errors = listOf(error))

        assertTrue(error.isRetryable)
        assertEquals(0, result.requestTimeoutCount)
        assertEquals(1, result.sendTimeoutCount)
    }

    @Test
    fun circuitBreakerIsNotRetryable() {
        val error = ChannelSyncError(0, ChannelSyncErrorType.CircuitBreaker, "Circuit open")
        assertFalse(error.isRetryable)
    }

    @Test
    fun deviceErrorIsNotRetryable() {
        val error = ChannelSyncError(0, ChannelSyncErrorType.DeviceError(0x02), "Not found")
        assertFalse(error.isRetryable)
    }

    @Test
    fun databaseErrorIsNotRetryable() {
        val error = ChannelSyncError(0, ChannelSyncErrorType.DatabaseError, "Save failed")
        assertFalse(error.isRetryable)
    }

    @Test
    fun unknownIsNotRetryable() {
        val error = ChannelSyncError(0, ChannelSyncErrorType.Unknown, "Unknown error")
        assertFalse(error.isRetryable)
    }

    @Test
    fun resultIsCompleteWhenNoErrors() {
        val result = ChannelSyncResult(channelsSynced = 8)
        assertTrue(result.isComplete)
    }

    @Test
    fun resultIsNotCompleteWithErrors() {
        val error = ChannelSyncError(3, ChannelSyncErrorType.Timeout, "Timeout")
        val result = ChannelSyncResult(channelsSynced = 7, errors = listOf(error))
        assertFalse(result.isComplete)
    }

    @Test
    fun retryableIndicesFiltersCorrectly() {
        val errors = listOf(
            ChannelSyncError(1, ChannelSyncErrorType.Timeout, "Timeout"),
            ChannelSyncError(2, ChannelSyncErrorType.DeviceError(0x02), "Not found"),
            ChannelSyncError(5, ChannelSyncErrorType.Timeout, "Timeout"),
        )
        val result = ChannelSyncResult(channelsSynced = 5, errors = errors)
        assertEquals(listOf(1, 5), result.retryableIndices)
    }

    @Test
    fun retryableIndicesEmptyWhenNoRetryableErrors() {
        val errors = listOf(
            ChannelSyncError(2, ChannelSyncErrorType.DeviceError(0x02), "Not found"),
            ChannelSyncError(3, ChannelSyncErrorType.DatabaseError, "Save failed"),
        )
        val result = ChannelSyncResult(channelsSynced = 6, errors = errors)
        assertTrue(result.retryableIndices.isEmpty())
    }
}
