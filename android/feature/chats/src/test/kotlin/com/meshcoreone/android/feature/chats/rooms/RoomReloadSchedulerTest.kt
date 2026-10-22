// PortedFrom: MC1/Views/RemoteNodes/Rooms/RoomConversationViewModel.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.chats.rooms

import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.RemoteNodeSessionDTO
import com.meshcoreone.android.core.model.RoomMessageDTO
import com.meshcoreone.android.core.model.RoomPermissionLevel
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.feature.chats.list.support.Fixtures
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test

class RoomReloadSchedulerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val session = Fixtures.room(isConnected = true).copy(permissionLevel = RoomPermissionLevel.READ_WRITE)
    private val events = MutableSharedFlow<RoomConversationEvent>()
    private val debounce = Channel<Unit>(Channel.UNLIMITED)
    private var debounceCalls = 0
    private var fetchCalls = 0
    private var readCalls = 0
    private val failures = mutableListOf<Throwable>()
    private var stored = emptyList<RoomMessageDTO>()
    private var beforeFetchReturn: suspend (Int) -> Unit = {}
    private var fetchJob: Job? = null
    private var beforePostReturn: suspend () -> Unit = {}
    private val holder = RoomConversationStateHolder(
        session,
        object : RoomConversationDependencies {
            override val events = this@RoomReloadSchedulerTest.events
            override val diagnostics = RoomConversationDiagnostics { _, failure -> failures += failure }
            override val data = object : RoomConversationDataSource {
                override suspend fun fetchMessages(session: RemoteNodeSessionDTO): List<RoomMessageDTO> {
                    fetchJob = currentCoroutineContext()[Job]
                    val snapshot = stored.toList()
                    beforeFetchReturn(++fetchCalls)
                    return snapshot
                }
                override suspend fun refreshSession(session: RemoteNodeSessionDTO) = session
                override suspend fun contactFor(session: RemoteNodeSessionDTO): ContactDTO? = null
                override suspend fun markAsRead(session: RemoteNodeSessionDTO) { readCalls++ }
                override suspend fun markFailedSendsSeen(session: RemoteNodeSessionDTO) = Unit
            }
            override val service = object : RoomConversationService {
                override suspend fun join(contact: ContactDTO, password: String?, rememberPassword: Boolean) = session
                override suspend fun reconnect(session: RemoteNodeSessionDTO) = session
                override suspend fun post(session: RemoteNodeSessionDTO, text: String): RoomMessageDTO {
                    beforePostReturn()
                    return message(900u, self = true, text = text).also { stored = stored + it }
                }
                override suspend fun retry(session: RemoteNodeSessionDTO, messageId: UUID): RoomMessageDTO =
                    stored.first { it.id == messageId }.copy(statusRawValue = MessageStatus.DELIVERED.rawValue)
                        .also { updated -> stored = stored.map { if (it.id == messageId) updated else it } }
            }
        },
        scope,
        awaitReloadDebounce = { debounceCalls++; debounce.receive() },
    )

    @After fun close() = scope.cancel()

    @Test fun `one pending debounce coalesces mixed received and changed events`() = runBlocking {
        val original = message(100u)
        stored = listOf(original)
        holder.start()
        holder.start()
        holder.load()
        val incoming = message(200u)
        stored += incoming

        events.emit(RoomConversationEvent.MessageReceived(session.id, incoming))
        repeat(5) { events.emit(RoomConversationEvent.MessageChanged(session.id, original.id)) }
        events.emit(RoomConversationEvent.MessageReceived(session.id, incoming))
        assertEquals(1, debounceCalls)
        assertEquals(1, fetchCalls)
        debounce.send(Unit)

        assertEquals(2, fetchCalls)
        assertEquals(listOf(original, incoming), holder.state.value.messages)
        assertFalse(holder.state.value.isLoading)
    }

    @Test fun `event during suspended fetch schedules successor and cannot be overwritten by stale rows`() = runBlocking {
        val original = message(100u)
        stored = listOf(original)
        holder.start()
        holder.load()
        val releaseFetch = CompletableDeferred<Unit>()
        beforeFetchReturn = { if (it == 2) releaseFetch.await() }
        events.emit(RoomConversationEvent.MessageChanged(session.id, original.id))
        debounce.send(Unit)
        assertEquals(2, fetchCalls)

        val incoming = message(50u)
        val delivered = original.copy(statusRawValue = MessageStatus.DELIVERED.rawValue)
        stored = listOf(delivered, incoming)
        events.emit(RoomConversationEvent.MessageReceived(session.id, incoming))
        events.emit(RoomConversationEvent.MessageChanged(session.id, original.id))
        assertEquals(2, debounceCalls)
        assertEquals(listOf(incoming.id, original.id), holder.state.value.messages.map { it.id })
        releaseFetch.complete(Unit)
        assertEquals(listOf(incoming.id, original.id), holder.state.value.rows.map { it.message.id })
        assertEquals(1, readCalls, "An invalidated snapshot must not perform read/notification side effects")

        // Completion of the old fetch must not clear the successor's pending debounce slot.
        events.emit(RoomConversationEvent.MessageChanged(session.id, original.id))
        assertEquals(2, debounceCalls)
        debounce.send(Unit)
        assertEquals(3, fetchCalls)
        assertEquals(listOf(incoming, delivered), holder.state.value.messages)
        assertEquals(2, readCalls)
        assertFalse(holder.state.value.isLoading)
    }

    @Test fun `successor already waiting for mutex wins over older suspended fetch`() = runBlocking {
        val original = message(100u)
        stored = listOf(original)
        holder.start()
        holder.load()
        val release = CompletableDeferred<Unit>()
        beforeFetchReturn = { if (it == 2) release.await() }
        events.emit(RoomConversationEvent.MessageChanged(session.id, original.id))
        debounce.send(Unit)
        val incoming = message(200u)
        stored += incoming
        events.emit(RoomConversationEvent.MessageReceived(session.id, incoming))
        debounce.send(Unit)
        assertEquals(2, fetchCalls)

        release.complete(Unit)

        assertEquals(3, fetchCalls)
        assertEquals(listOf(original, incoming), holder.state.value.messages)
        assertEquals(2, readCalls)
        assertFalse(holder.state.value.isLoading)
    }

    @Test fun `failed reload exposes error and next event can reload`() = runBlocking {
        val original = message(100u)
        stored = listOf(original)
        holder.start()
        holder.load()
        val failure = IllegalStateException("fetch failed")
        beforeFetchReturn = { if (it == 2) throw failure }
        events.emit(RoomConversationEvent.MessageChanged(session.id, original.id))
        debounce.send(Unit)
        assertSame(failure, holder.state.value.failure)
        assertEquals(listOf(failure), failures)

        events.emit(RoomConversationEvent.MessageChanged(session.id, original.id))
        debounce.send(Unit)

        assertEquals(3, fetchCalls)
        assertNull(holder.state.value.failure)
        assertFalse(holder.state.value.isLoading)
    }

    @Test fun `cancelled fetch does not occupy successor slot or report failure`() = runBlocking {
        val original = message(100u)
        stored = listOf(original)
        holder.start()
        holder.load()
        beforeFetchReturn = { if (it == 2) CompletableDeferred<Unit>().await() }
        events.emit(RoomConversationEvent.MessageChanged(session.id, original.id))
        debounce.send(Unit)
        fetchJob!!.cancel()
        assertFalse(holder.state.value.isLoading)
        assertTrue(failures.isEmpty())

        events.emit(RoomConversationEvent.MessageChanged(session.id, original.id))
        debounce.send(Unit)
        assertEquals(3, fetchCalls)
        assertFalse(holder.state.value.isLoading)
    }

    @Test fun `screen cancellation before debounce never fetches or marks read`() = runBlocking {
        holder.start()
        events.emit(RoomConversationEvent.MessageReceived(session.id, message(100u)))
        scope.cancel()
        debounce.send(Unit)
        assertEquals(0, fetchCalls)
        assertEquals(0, readCalls)
        assertTrue(failures.isEmpty())
    }

    @Test fun `unrelated session and unknown status IDs never schedule a fetch`() = runBlocking {
        holder.start()
        events.emit(RoomConversationEvent.MessageReceived(UUID.randomUUID(), message(100u)))
        events.emit(RoomConversationEvent.MessageChanged(session.id, UUID.randomUUID()))
        assertEquals(0, debounceCalls)
        assertEquals(0, fetchCalls)
    }

    @Test fun `successful retry survives an older fetch and follow-up keeps delivered state`() = runBlocking {
        val failed = message(100u, self = true)
        stored = listOf(failed)
        holder.start()
        holder.load()
        val release = CompletableDeferred<Unit>()
        beforeFetchReturn = { if (it == 2) release.await() }
        events.emit(RoomConversationEvent.MessageChanged(session.id, failed.id))
        debounce.send(Unit)
        holder.retry(failed.id)
        release.complete(Unit)
        assertEquals(MessageStatus.DELIVERED, holder.state.value.messages.single().status)
        debounce.send(Unit)
        assertEquals(3, fetchCalls)
        assertEquals(MessageStatus.DELIVERED, holder.state.value.messages.single().status)
    }

    @Test fun `send again posts exact original text once without consuming an unrelated draft`() = runBlocking {
        val original = message(100u, self = true, text = "原文 👩‍🚀 e\u0301")
        holder.updateDraft("keep draft")
        val release = CompletableDeferred<Unit>()
        beforePostReturn = { release.await() }
        val first = scope.launch { assertTrue(holder.sendAgain(original)) }
        assertFalse(holder.sendAgain(original))
        release.complete(Unit)
        first.join()
        assertEquals(listOf(original.text), stored.map { it.text })
        assertEquals("keep draft", holder.state.value.draft)
        assertFalse(holder.sendAgain(original.copy(isFromSelf = false)))
        assertFalse(holder.sendAgain(original.copy(sessionID = UUID.randomUUID())))
    }

    @Test fun `typing during a suspended send is not discarded on success or cancellation`() = runBlocking {
        holder.updateDraft("submitted")
        val release = CompletableDeferred<Unit>()
        beforePostReturn = { release.await() }
        val send = scope.launch { assertTrue(holder.send()) }
        holder.updateDraft("new draft")
        release.complete(Unit)
        send.join()
        assertEquals("new draft", holder.state.value.draft)
        beforePostReturn = { throw CancellationException("cancelled") }
        try {
            holder.send()
            throw AssertionError("Expected cancellation")
        } catch (_: CancellationException) {
            assertEquals("new draft", holder.state.value.draft)
            assertFalse(holder.state.value.isSending)
            assertNull(holder.state.value.failure)
        }
    }

    private fun message(timestamp: UInt, self: Boolean = false, text: String = "message") = RoomMessageDTO(
        sessionID = session.id,
        authorKeyPrefix = Bytes.of(1),
        authorName = "Alice",
        text = text,
        timestamp = timestamp,
        createdAt = Instant.ofEpochSecond(timestamp.toLong()),
        isFromSelf = self,
        statusRawValue = MessageStatus.FAILED.rawValue,
    )
}
