package one.meshcore.shared.connection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ChannelSlotRetryOnceTest {
    @Test
    fun anAdvertWaitTimeoutReturnsNullWithoutReadingSlots() = runTest {
        var reads = 0
        val warnings = mutableListOf<String>()

        val pending = readChannelRetrySlots(
            waitForAdvertIdle = { false },
            readSlots = {
                reads += 1
                listOf(1)
            },
            logWarning = { warnings += it },
        )

        assertNull(pending)
        assertEquals(0, reads)
        assertEquals(listOf(CHANNEL_ONLY_RETRY_ADVERT_WAIT_TIMED_OUT), warnings)
    }

    @Test
    fun aSuccessfulReadReturnsTheRetryableSlots() = runTest {
        val warnings = mutableListOf<String>()

        val pending = readChannelRetrySlots(
            waitForAdvertIdle = { true },
            readSlots = { listOf(2, 4) },
            logWarning = { warnings += it },
        )

        assertEquals(listOf(2, 4), pending)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun aReadFailureLogsAndReturnsNull() = runTest {
        val warnings = mutableListOf<String>()

        val pending = readChannelRetrySlots(
            waitForAdvertIdle = { true },
            readSlots = { error("slot unread") },
            logWarning = { warnings += it },
        )

        assertNull(pending)
        assertEquals(listOf(channelOnlyRetryFailedLine("slot unread")), warnings)
    }

    @Test
    fun aCancelledReadRethrowsWithoutAWarning() = runTest {
        val warnings = mutableListOf<String>()
        val cancelled = CancellationException("stopped")

        val thrown = assertFailsWith<CancellationException> {
            readChannelRetrySlots(
                waitForAdvertIdle = { true },
                readSlots = { throw cancelled },
                logWarning = { warnings += it },
            )
        }

        assertSame(cancelled, thrown)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun anErrorLogsAndReturnsNull() = runTest {
        val warnings = mutableListOf<String>()

        val pending = readChannelRetrySlots(
            waitForAdvertIdle = { true },
            readSlots = { throw ChannelSlotRetryProbeError() },
            logWarning = { warnings += it },
        )

        assertNull(pending)
        assertEquals(listOf(channelOnlyRetryFailedLine("probe")), warnings)
    }
}

private class ChannelSlotRetryProbeError : Error("probe")
