package one.meshcore.shared.chat

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class LiveMessageStatusEventsTest {
    @Test
    fun aMissingServiceCompletesWithoutEmitting() = runTest {
        assertEquals(emptyList(), liveMessageStatusEvents { null }.toList())
    }

    @Test
    fun collectionReadsTheServiceAndEmitsALaterFailure() = runTest {
        val service = testMessageService(FakeChatSession(), InMemoryMessageStore(), backgroundScope)
        val id = ChatFixtures.freshUuid()
        val events = mutableListOf<MessageStatusEvent>()
        val job = launch {
            liveMessageStatusEvents { service }.collect { event: MessageStatusEvent -> events += event }
        }
        advanceUntilIdle()

        service.notifyMessageFailed(id)
        advanceUntilIdle()

        assertEquals(listOf<MessageStatusEvent>(MessageStatusEvent.Failed(id)), events)
        job.cancel()
    }

    @Test
    fun aLaterCollectionReadsTheProviderAgain() = runTest {
        val service = testMessageService(FakeChatSession(), InMemoryMessageStore(), backgroundScope)
        var current: MessageService? = null
        assertEquals(emptyList(), liveMessageStatusEvents { current }.toList())

        current = service
        val id = ChatFixtures.freshUuid()
        val events = mutableListOf<MessageStatusEvent>()
        val job = launch {
            liveMessageStatusEvents { current }.collect { event: MessageStatusEvent -> events += event }
        }
        advanceUntilIdle()
        service.notifyMessageFailed(id)
        advanceUntilIdle()

        assertEquals(listOf<MessageStatusEvent>(MessageStatusEvent.Failed(id)), events)
        job.cancel()
    }

    @Test
    fun stoppingTheCollectorReleasesTheSubscription() = runTest {
        val service = testMessageService(FakeChatSession(), InMemoryMessageStore(), backgroundScope)
        val stopped = launch {
            liveMessageStatusEvents { service }.collect { _: MessageStatusEvent -> }
        }
        advanceUntilIdle()
        stopped.cancel()
        advanceUntilIdle()

        val id = ChatFixtures.freshUuid()
        val events = mutableListOf<MessageStatusEvent>()
        val next = launch {
            liveMessageStatusEvents { service }.collect { event: MessageStatusEvent -> events += event }
        }
        advanceUntilIdle()
        service.notifyMessageFailed(id)
        advanceUntilIdle()

        assertEquals(listOf<MessageStatusEvent>(MessageStatusEvent.Failed(id)), events)
        next.cancel()
    }
}
