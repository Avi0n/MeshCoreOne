// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/ChatSendQueueServiceTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Tests/MC1ServicesTests/Helpers/MessageService+Testing.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.parity

import com.meshcoreone.android.core.contracts.domain.MessageStatusEvent
import com.meshcoreone.android.core.contracts.domain.errors.MessageServiceError
import com.meshcoreone.android.core.contracts.domain.errors.MessageServiceException
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.services.messaging.MessageServiceConfig
import com.meshcoreone.android.core.services.messaging.PoolBackoffConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DirectQueueFailureOriginalPredicateTest {
    @Test fun originalUnstartedSessionRetainsTheCompletePendingRowWhileActuallyParked() = runTest {
        withDirectQueueFixture(connected = false) { f ->
            assertEquals(RadioId(f.device.id), f.radio)
            assertNotEquals(f.device.radioId, f.radio)
            f.startDrain()
            advanceTimeBy(200)
            runCurrent()

            assertEquals(MessageServiceError.NotConnected,
                (f.failure("park") as MessageServiceException).error)
            f.assertParked()
            assertNull(f.session.currentSelfInfo)
            assertFalse(f.transport.isConnected())
            assertTrue(f.transport.sent.isEmpty())
        }
    }

    @Test fun originalTableFullExhaustsTwoPoolRetriesWithoutBroadcastingFailed() = runTest {
        val config = MessageServiceConfig(poolBackoff = PoolBackoffConfig(attemptCap = 2, baseDelay = 0.01))
        withDirectQueueFixture(connected = true, config = config, firmwareError = 3) { f ->
            val subscription = f.messages.statusEvents()
            f.startDrain()
            advanceTimeBy(300)
            runCurrent()

            val failure = (f.failure("park") as MessageServiceException).error
            assertTrue(failure is MessageServiceError.SessionError)
            val underlying = (failure as MessageServiceError.SessionError).underlying
            assertTrue(underlying is MeshCoreException.DeviceError)
            assertEquals(3.toUByte(), (underlying as MeshCoreException.DeviceError).code)
            assertEquals(3, f.directSends().size)
            f.assertParked()
            f.messages.finishStatusEvents()
            val events = subscription.events.toList()
            assertTrue(events.all { it.token == f.token })
            assertEquals(0, events.map { it.event }.filterIsInstance<MessageStatusEvent.Failed>().size)
            subscription.close()
        }
    }

    @Test fun originalIllegalArgumentBroadcastsExactlyOneFailureForTheOriginalMessageID() = runTest {
        withDirectQueueFixture(connected = true, firmwareError = 6) { f ->
            val subscription = f.messages.statusEvents()
            f.startDrain()
            f.queue.awaitDrainCompletion()

            val failure = (f.failure("terminal") as MessageServiceException).error
            assertTrue(failure is MessageServiceError.SessionError)
            val underlying = (failure as MessageServiceError.SessionError).underlying
            assertTrue(underlying is MeshCoreException.DeviceError)
            assertEquals(6.toUByte(), (underlying as MeshCoreException.DeviceError).code)
            assertEquals(1, f.directSends().size)
            assertEquals(f.message.copy(status = MessageStatus.FAILED), f.store.fetchMessage(f.key))
            assertTrue(f.store.fetchPendingSends(f.radio).isEmpty())
            assertFalse(f.store.hasPendingSend(f.key))
            assertEquals(0, f.queue.queuedEnvelopeCount)
            f.messages.finishStatusEvents()
            val events = subscription.events.toList()
            assertTrue(events.all { it.token == f.token })
            assertEquals(listOf(f.message.id),
                events.map { it.event }.filterIsInstance<MessageStatusEvent.Failed>().map { it.messageID })
            subscription.close()
        }
    }
}
