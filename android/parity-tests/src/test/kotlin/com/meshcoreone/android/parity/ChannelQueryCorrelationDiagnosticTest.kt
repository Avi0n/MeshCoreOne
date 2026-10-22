// AndroidOnly: WP-501 Real-consumer diagnostic, not acceptance of the blocked source per-envelope counter original.
package com.meshcoreone.android.parity

import com.meshcoreone.android.core.contracts.domain.errors.MessageServiceError
import com.meshcoreone.android.core.contracts.domain.errors.MessageServiceException
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.services.contacts.ChannelServiceError
import com.meshcoreone.android.core.services.messaging.ChatSendQueueConfig
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
class ChannelQueryCorrelationDiagnosticTest {
    @Test fun blockedCounterOriginalExposesTheRealQueryCorrelationBoundaryWithoutClaimingParity() = runTest {
        withChannelQueueFixture(7,
            MessageServiceConfig(poolBackoff = PoolBackoffConfig(attemptCap = 2, baseDelay = 0.01)),
            queueConfig = ChatSendQueueConfig(maxConsecutiveFetchChannelFailures = 2), expectQueryIssue = true) { f ->
            f.persistB()
            f.startDrain()
            advanceTimeBy(1_000)
            runCurrent()

            f.assertRows(f.pendingA.copy(attemptCount = 8), f.pendingB)
            f.assertMessages(f.messageA, f.messageB)
            assertEquals(2, f.queue.queuedEnvelopeCount)
            assertEquals(3, f.sends(3).size)
            assertEquals(listOf(Bytes.of(31, 0)), f.sends(31))
            val queryFailure = f.failures("channelQuery", f.messageA.id).single()
            assertTrue("Expected the real ChannelService query wrapper; got ${queryFailure.stackTraceToString()}",
                queryFailure is ChannelServiceError.SessionError)
            assertChannelQueryUnresolved((queryFailure as ChannelServiceError.SessionError).error, "channel:0")
            assertSame(queryFailure, f.failures("park", f.messageA.id).single())

            f.queue.transportDidOpen()
            runCurrent()

            f.assertRows(f.pendingA.copy(attemptCount = 9), f.pendingB)
            f.assertMessages(f.messageA, f.messageB)
            assertEquals(3, f.sends(3).size)
            assertEquals(1, f.sends(31).size)
            assertEquals(1, f.queries.size)
            val parks = f.failures("park", f.messageA.id)
            assertEquals(2, parks.size)
            val second = parks[1]
            assertTrue("Expected the second park's MessageServiceException; got ${second.stackTraceToString()}",
                second is MessageServiceException)
            val failure = (second as MessageServiceException).error
            assertTrue("Expected the second park's MessageService SessionError; got ${second.stackTraceToString()}",
                failure is MessageServiceError.SessionError)
            assertChannelQueryUnresolved((failure as MessageServiceError.SessionError).underlying, "error")
            assertTrue(f.failures("terminal", f.messageA.id).isEmpty())
            assertTrue(f.failures("terminal", f.messageB.id).isEmpty())
        }
    }
}
