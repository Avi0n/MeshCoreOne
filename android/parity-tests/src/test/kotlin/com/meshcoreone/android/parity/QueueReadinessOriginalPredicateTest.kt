// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/ChatSendQueueServiceTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.parity

import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.contracts.domain.errors.MessageServiceError
import com.meshcoreone.android.core.contracts.domain.errors.MessageServiceException
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.services.messaging.MessageServiceConfig
import com.meshcoreone.android.core.services.messaging.PoolBackoffConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class QueueReadinessOriginalPredicateTest {
    private val config = MessageServiceConfig(poolBackoff = PoolBackoffConfig(attemptCap = 2, baseDelay = 0.01))

    @Test fun originalAlreadyReadyInitialStateWakesTheParkWithAnAlreadyFinishedStream() = runTest {
        withDirectQueueFixture(connected = true, config = config) { f ->
            f.startDrain()
            settlePark(f, 1)

            assertEquals(DeviceConnectionState.READY, f.connectionState)
            f.finishTransitions()
            f.queue.observeConnectionState()
            settlePark(f, 2)
            assertFalse(f.queue.isObservingConnection)
        }
    }

    @Test fun originalConnectedRemainsParkedForOneSecondUntilTheReadyEdge() = runTest {
        withDirectQueueFixture(connected = true, config = config) { f ->
            assertEquals(DeviceConnectionState.CONNECTED, f.connectionState)
            f.queue.observeConnectionState()
            f.startDrain()
            settlePark(f, 1)

            f.transition(DeviceConnectionState.CONNECTED)
            advanceTimeBy(1_000)
            runCurrent()
            assertPark(f, 1)
            assertTrue(f.queue.isObservingConnection)

            f.transition(DeviceConnectionState.READY)
            f.finishTransitions()
            settlePark(f, 2)
            assertFalse(f.queue.isObservingConnection)
        }
    }

    @Test fun originalDisconnectedConnectedSyncingReadyRampWakesExactlyOnce() = runTest {
        withDirectQueueFixture(connected = true, config = config) { f ->
            f.transition(DeviceConnectionState.DISCONNECTED)
            assertEquals(DeviceConnectionState.DISCONNECTED, f.connectionState)
            f.queue.observeConnectionState()
            f.startDrain()
            settlePark(f, 1)

            f.transition(DeviceConnectionState.DISCONNECTED)
            f.transition(DeviceConnectionState.CONNECTED)
            f.transition(DeviceConnectionState.SYNCING)
            f.transition(DeviceConnectionState.READY)
            f.finishTransitions()
            settlePark(f, 2)
            assertFalse(f.queue.isObservingConnection)

            advanceTimeBy(1_000)
            runCurrent()
            assertPark(f, 2)
        }
    }

    private suspend fun TestScope.settlePark(f: DirectQueueOriginalFixture, attemptCount: Long) {
        advanceTimeBy(300)
        runCurrent()
        assertPark(f, attemptCount)
    }

    private suspend fun assertPark(f: DirectQueueOriginalFixture, attemptCount: Long) {
        f.assertParked(attemptCount)
        val failures = f.failures("park")
        assertEquals(attemptCount.toInt(), failures.size)
        assertEquals(3 * attemptCount.toInt(), f.directSends().size)
        failures.forEach { cause ->
            assertTrue(cause is MessageServiceException)
            val failure = (cause as MessageServiceException).error
            assertTrue(failure is MessageServiceError.SessionError)
            val underlying = (failure as MessageServiceError.SessionError).underlying
            assertTrue(underlying is MeshCoreException.DeviceError)
            assertEquals(3.toUByte(), (underlying as MeshCoreException.DeviceError).code)
        }
    }
}
