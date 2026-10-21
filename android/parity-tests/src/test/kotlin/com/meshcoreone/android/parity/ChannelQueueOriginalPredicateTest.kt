// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/ChatSendQueueServiceTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.parity

import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import com.meshcoreone.android.core.services.messaging.MessageServiceConfig
import com.meshcoreone.android.core.services.messaging.PoolBackoffConfig
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ChannelQueueOriginalPredicateTest {
    @Test fun originalBelowThresholdRetainsTheCompleteFirstRowWithoutAChannelQuery() = runTest {
        withChannelQueueFixture(0, MessageServiceConfig(
            poolBackoff = PoolBackoffConfig(attemptCap = 2, baseDelay = 0.01))) { f ->
            f.startDrain()
            advanceTimeBy(300)
            runCurrent()

            f.assertRows(f.pendingA.copy(attemptCount = 1))
            f.assertMessages(f.messageA, f.messageB)
            assertEquals(1, f.queue.queuedEnvelopeCount)
            assertEquals(3, f.sends(3).size)
            assertTrue(f.sends(31).isEmpty())
            assertTrue(f.queries.isEmpty())
            assertOriginalChannelDeviceError2(f.failures("park", f.messageA.id).single())
        }
    }

    @Test fun originalDefaultThirtySecondWaitReattemptsOnlyAtTheActualParkDeadline() = runTest {
        withChannelQueueFixture(0) { f ->
            f.startDrain()
            advanceTimeBy(3_600)
            runCurrent()
            f.assertRows(f.pendingA.copy(attemptCount = 1))
            f.assertMessages(f.messageA, f.messageB)
            assertEquals(4, f.sends(3).size)
            val parkedAt = f.timeOf("park", f.messageA.id)
            val remaining = parkedAt + 30_000 - testScheduler.currentTime
            assertTrue(remaining > 1)

            advanceTimeBy(remaining - 1)
            runCurrent()
            f.assertRows(f.pendingA.copy(attemptCount = 1))
            assertEquals(4, f.sends(3).size)

            advanceTimeBy(1)
            runCurrent()
            f.assertRows(f.pendingA.copy(attemptCount = 2))
            assertEquals(5, f.sends(3).size)

            advanceTimeBy(3_600)
            runCurrent()
            f.assertRows(f.pendingA.copy(attemptCount = 2))
            f.assertMessages(f.messageA, f.messageB)
            assertEquals(8, f.sends(3).size)
            assertTrue(f.queries.isEmpty())
            assertTrue(f.sends(31).isEmpty())
            assertEquals(1, f.queue.queuedEnvelopeCount)
            val parks = f.failures("park", f.messageA.id)
            assertEquals(2, parks.size)
            parks.forEach(::assertOriginalChannelDeviceError2)
        }
    }

    @Test fun originalDisabledGoneChannelIntentUsesARealUnconfiguredChannelResponse() = runTest {
        withChannelQueueFixture(7, gone = true) { f ->
            f.startDrain()
            f.queue.awaitDrainCompletion()

            f.assertRows()
            f.assertMessages(f.messageA.copy(status = MessageStatus.FAILED), f.messageB)
            assertEquals(0, f.queue.queuedEnvelopeCount)
            assertEquals(4, f.sends(3).size)
            assertEquals(listOf(Bytes.of(31, 0)), f.sends(31))
            assertEquals(listOf(0.toUByte()), f.queries)
            assertEquals(listOf<ChannelInfo?>(null), f.queryResults)
            assertOriginalChannelDeviceError2(f.failures("terminal", f.messageA.id).single())
        }
    }
}
