package one.meshcore.shared.chat.ui

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.chat.ChatConversationID
import one.meshcore.shared.contact.ContactServiceEvent
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.remotenode.testUuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ChatThreadRootStateTest {
    @Test
    fun fallbackStateAndSlotGeneration() {
        val fallback = MutableStateFlow("en")
        val live = MutableStateFlow("es")
        assertEquals(fallback, stateOrFallback(state = null, fallback = fallback))
        assertEquals(live, stateOrFallback(state = live, fallback = fallback))
        val id = ChatConversationID.channel(testUuid(1), 2)
        assertEquals(0, slotGenerationFor(emptyMap(), id))
        assertEquals(4, slotGenerationFor(mapOf(id to 4), id))
    }

    @Test
    fun envNameAndMapPreviews() {
        assertEquals("", chatEnvUserName(null))
        assertEquals("Ada", chatEnvUserName("Ada"))
        assertFalse(chatEnvShowsMapPreviews(enabled = true, suppressed = true))
        assertTrue(chatEnvShowsMapPreviews(enabled = true, suppressed = false))
        assertFalse(chatEnvShowsMapPreviews(enabled = false, suppressed = false))
    }

    @Test
    fun refreshSkipsAZeroSignalAndAMissingContact() = runBlocking {
        val assigned = mutableListOf<String>()
        refreshLiveContact(id = null, signal = 1, load = { "Ada" }, assign = { assigned.add(it) })
        refreshLiveContact(id = testUuid(1), signal = 0, load = { "Ada" }, assign = { assigned.add(it) })
        refreshLiveContact<String>(id = testUuid(1), signal = 2, load = { null }, assign = { assigned.add(it) })
        refreshLiveContact(id = testUuid(1), signal = 2, load = { "Ada" }, assign = { assigned.add(it) })
        assertEquals(listOf("Ada"), assigned.toList())
    }

    @Test
    fun clearedContactsOnlyForwardClears() = runBlocking {
        val cleared = mutableListOf<Uuid>()
        collectClearedContacts(events = null, onCleared = { cleared += it })
        val events = Channel<ContactServiceEvent>(Channel.UNLIMITED)
        events.trySend(ContactServiceEvent.NodeDeleted)
        events.trySend(ContactServiceEvent.MessagesCleared(testUuid(3)))
        events.close()
        collectClearedContacts(events = events, onCleared = { cleared += it })
        assertEquals(listOf(testUuid(3)), cleared.toList())
    }

    @Test
    fun canceledClearedContactCollectorClosesItsSubscription() = runTest {
        val events = Channel<ContactServiceEvent>(Channel.UNLIMITED)
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            collectClearedContacts(events) {}
        }
        job.cancelAndJoin()
        try {
            assertTrue(events.trySend(ContactServiceEvent.NodeDeleted).isClosed)
        } finally {
            events.cancel()
        }
    }

    @Test
    fun failedClearedContactCallbackClosesSubscriptionAndRethrowsSameFailure() = runTest {
        val events = Channel<ContactServiceEvent>(Channel.UNLIMITED)
        events.trySend(ContactServiceEvent.MessagesCleared(testUuid(3)))
        events.trySend(ContactServiceEvent.NodeDeleted)
        val failure = IllegalStateException("reload failed")
        val caught = assertFailsWith<IllegalStateException> {
            collectClearedContacts(events) { throw failure }
        }
        assertSame(failure, caught)
        try {
            assertTrue(events.tryReceive().isClosed)
            assertTrue(events.trySend(ContactServiceEvent.NodeDeleted).isClosed)
        } finally {
            events.cancel()
        }
    }

    @Test
    fun collectionStartsOnlyWhenAllowedAndRefreshesWhenLoaded() = runBlocking {
        val calls = mutableListOf<String>()
        startThreadCollection(
            canCollect = false,
            isLoaded = true,
            start = { calls += "start" },
            refresh = { calls += "refresh" },
        )
        startThreadCollection(
            canCollect = true,
            isLoaded = false,
            start = { calls += "start" },
            refresh = { calls += "refresh" },
        )
        startThreadCollection(
            canCollect = true,
            isLoaded = true,
            start = { calls += "start" },
            refresh = { calls += "refresh" },
        )
        assertEquals(listOf("start", "start", "refresh"), calls.toList())
    }

    @Test
    fun liveContactFollowsTheRouteThenReloads() = runBlocking {
        var current: String? = null
        val assigned = mutableListOf<String?>()
        collectLiveContact(
            LiveContactLoad(
                contact = "Ada",
                id = null,
                current = { current },
                load = { "loaded" },
                invalidations = flow { emit(Unit) },
                assign = {
                    current = it
                    assigned.add(it)
                },
            ),
        )
        assertTrue(assigned.isEmpty())
        collectLiveContact(
            LiveContactLoad(
                contact = "Ada",
                id = testUuid(1),
                current = { current },
                load = { "loaded" },
                invalidations = flow { emit(Unit) },
                assign = {
                    current = it
                    assigned.add(it)
                },
            ),
        )
        assertEquals(listOf("Ada", "loaded", "loaded"), assigned.toList())
    }
}
