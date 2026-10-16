package one.meshcore.shared

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class EventBroadcasterTest {
    @Test
    fun subscribersEachReceiveEventsYieldedAfterSubscribe() = runTest {
        val broadcaster = EventBroadcaster<String>()
        val first = broadcaster.subscribe()
        val second = broadcaster.subscribe()
        broadcaster.yield("heard")
        assertEquals("heard", first.receive())
        assertEquals("heard", second.receive())
        broadcaster.finish()
        assertFailsWith<ClosedReceiveChannelException> {
            first.receive()
        }
    }

    @Test
    fun flowsRegisterBeforeCollectionAndDrainBufferedEventsAfterFinish() = runTest {
        val broadcaster = EventBroadcaster<Int>()
        val first = broadcaster.events()
        val second = broadcaster.events()
        val expected = (0 until BURST_SIZE).toList()
        expected.forEach { broadcaster.yield(it) }
        broadcaster.finish()

        withTimeout(1.seconds) {
            assertEquals(expected, first.toList())
            assertEquals(expected, second.toList())
        }
    }

    @Test
    fun canceledSubscriberDoesNotInterruptDeliveryToActiveSubscriber() = runTest {
        val broadcaster = EventBroadcaster<Int>()
        val canceled = broadcaster.subscribe()
        val active = broadcaster.events()
        canceled.cancel()
        val expected = (0 until BURST_SIZE).toList()
        expected.forEach { broadcaster.yield(it) }
        broadcaster.finish()

        withTimeout(1.seconds) {
            assertEquals(expected, active.toList())
        }
    }

    @Test
    fun eventsFlowCompletesWhenTheBroadcasterFinishes() = runTest {
        val broadcaster = EventBroadcaster<String>()
        val heard = mutableListOf<String>()
        val job = launch {
            broadcaster.events().collect { heard += it }
        }
        advanceUntilIdle()
        broadcaster.yield("heard")
        advanceUntilIdle()
        assertEquals(listOf("heard"), heard)
        broadcaster.finish()
        job.join()
    }

    private companion object {
        const val BURST_SIZE = 128
    }
}
