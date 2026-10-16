package one.meshcore.shared.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ChatSendQueueRetirementSignalTest {
    @Test
    fun aReentrantRetirementSubscriberCannotStartTheDirectDrain() = verify(channel = false)

    @Test
    fun aReentrantRetirementSubscriberCannotStartTheChannelDrain() = verify(channel = true)

    private fun verify(channel: Boolean) = runTest {
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val fixture = ChatSendQueueRetiredDrainFixture(scope, channel)
        fixture.initialize(one.meshcore.shared.model.MessageStatus.PENDING)
        val message = fixture.original
        val direct = DirectMessageEnvelope(message.id, ChatFixtures.CONTACT)
        val broadcast = ChannelMessageEnvelope(message.id, CHANNEL_INDEX, messageText = message.text)
        val pending = if (channel) {
            broadcast.toPendingSendDto(PendingSendIds().next(), message.radioID, 1, TEST_NOW_SECONDS, 0)
        } else {
            direct.toPendingSendDto(PendingSendIds().next(), message.radioID, 1, TEST_NOW_SECONDS, 0)
        }
        fixture.store.insertPendingSendAssigningSequence(pending)
        var subscriberRan = false
        fixture.queue.retirement.invokeOnCompletion {
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                subscriberRan = true
                if (channel) fixture.queue.channelQueue.enqueue(broadcast) else fixture.queue.signalDMEnqueued(direct)
            }
        }
        try {
            fixture.queue.retire()
            assertTrue(subscriberRan)
            assertEquals(0, fixture.sessionCalls, "retirement observers must find both inner queues already retired")
            assertEquals(listOf(pending), fixture.store.fetchPendingSends(message.radioID))
        } finally {
            fixture.release.complete(Unit)
            fixture.queue.shutdown()
            fixture.events.cancel()
        }
    }

    private companion object {
        const val CHANNEL_INDEX = 1
    }
}
