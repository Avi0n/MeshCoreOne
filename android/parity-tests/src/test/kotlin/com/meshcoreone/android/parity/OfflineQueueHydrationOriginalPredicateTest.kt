// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/ChatSendQueueServiceTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.parity

import com.meshcoreone.android.app.container.RoomProcessTest
import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.model.DirectMessageEnvelope
import com.meshcoreone.android.core.model.PendingSendDTO
import com.meshcoreone.android.core.model.PendingSendKind
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ConnectionState
import com.meshcoreone.android.core.protocol.session.MeshCoreSession
import com.meshcoreone.android.core.protocol.transport.mock.MockTransport
import com.meshcoreone.android.core.services.messaging.ChatSendQueueService
import com.meshcoreone.android.core.services.messaging.MessageService
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class OfflineQueueHydrationOriginalPredicateTest : RoomProcessTest() {
    @Test fun originalPersistedMissingContactEnvelopeReallyDrainsFromRoom() = runTest {
        val radio = RadioId(UUID.randomUUID())
        val pending = pending(radio)
        assertEquals(1L, store.insertPendingSendAssigningSequence(pending))
        assertEquals(listOf(pending.copy(sequence = 1)), store.fetchPendingSends(radio))

        withOfflineQueue(radio) { queue ->
            queue.hydrate()
            queue.awaitDrainCompletion()

            assertTrue(store.fetchPendingSends(radio).isEmpty())
            assertEquals(0, queue.queuedEnvelopeCount)
        }
    }

    @Test fun originalTwoOwnRadioEnvelopesDrainAndTheExactForeignEnvelopeSurvives() = runTest {
        val radioA = RadioId(UUID.randomUUID())
        val radioB = RadioId(UUID.randomUUID())
        val first = pending(radioA)
        val second = pending(radioA)
        val foreign = pending(radioB)
        assertEquals(1L, store.insertPendingSendAssigningSequence(first))
        assertEquals(2L, store.insertPendingSendAssigningSequence(second))
        assertEquals(1L, store.insertPendingSendAssigningSequence(foreign))
        assertEquals(listOf(first.copy(sequence = 1), second.copy(sequence = 2)), store.fetchPendingSends(radioA))
        assertEquals(listOf(foreign.copy(sequence = 1)), store.fetchPendingSends(radioB))

        withOfflineQueue(radioA) { queue ->
            queue.hydrate()
            queue.awaitDrainCompletion()

            assertTrue(store.fetchPendingSends(radioA).isEmpty())
            val remaining = store.fetchPendingSends(radioB)
            assertEquals(1, remaining.size)
            assertEquals(foreign.messageID, remaining.single().messageID)
            assertEquals(foreign.copy(sequence = 1), remaining.single())
        }
    }

    @Test fun originalHydrationFetchPreservesTheThreeActualEnvelopeMessageIDsInOrder() = runTest {
        val radio = RadioId(UUID.randomUUID())
        val first = pending(radio)
        val second = pending(radio)
        val third = pending(radio)
        assertEquals(1L, store.insertPendingSendAssigningSequence(first))
        assertEquals(2L, store.insertPendingSendAssigningSequence(second))
        assertEquals(3L, store.insertPendingSendAssigningSequence(third))

        val rows = store.fetchPendingSends(radio)

        assertEquals(listOf(first.messageID, second.messageID, third.messageID), rows.map { it.messageID })
        assertEquals(listOf(first.copy(sequence = 1), second.copy(sequence = 2), third.copy(sequence = 3)), rows)
    }

    @Test fun originalSecondHydrateDoesNotEnqueueTheNewlyPersistedEnvelope() = runTest {
        val radio = RadioId(UUID.randomUUID())
        val first = pending(radio)
        assertEquals(1L, store.insertPendingSendAssigningSequence(first))
        assertEquals(listOf(first.copy(sequence = 1)), store.fetchPendingSends(radio))

        withOfflineQueue(radio) { queue ->
            queue.hydrate()
            queue.awaitDrainCompletion()
            assertTrue(store.fetchPendingSends(radio).isEmpty())

            val second = pending(radio)
            assertEquals(1L, store.insertPendingSendAssigningSequence(second))
            assertEquals(listOf(second.copy(sequence = 1)), store.fetchPendingSends(radio))

            queue.hydrate()
            queue.awaitDrainCompletion()

            val remaining = store.fetchPendingSends(radio)
            assertEquals(1, remaining.size)
            assertEquals(second.copy(sequence = 1), remaining.single())
        }
    }

    private fun pending(radio: RadioId): PendingSendDTO {
        val envelope = DirectMessageEnvelope(UUID.randomUUID(), UUID.randomUUID())
        val id = UUID.randomUUID()
        val now = Instant.ofEpochSecond(1_704_067_200)
        val dto = PendingSendDTO.fromEnvelope(envelope, radio, id = id, enqueuedAt = now)
        assertEquals(
            PendingSendDTO(id, radio, envelope.messageID, PendingSendKind.DM, envelope.contactID,
                null, false, "", 0u, null, 0, now, 0),
            dto,
        )
        return dto
    }

    private suspend fun TestScope.withOfflineQueue(radio: RadioId, body: suspend (ChatSendQueueService) -> Unit) {
        val job = SupervisorJob(coroutineContext[Job])
        val scope = CoroutineScope(coroutineContext + job)
        val token = SessionToken(ProcessEpoch(UUID.randomUUID()), Generation(1), radio)
        val signals = object : ConnectionSignals {
            override val snapshot = MutableStateFlow(ConnectionSnapshot(DeviceConnectionState.READY,
                ConnectionState.Disconnected, null, ConnectionIntent.WantsConnection(), token, null))
            override suspend fun subscribeTransitions(): ConnectionSubscription {
                val changes = Channel<ConnectionSnapshot>(Channel.UNLIMITED)
                return object : ConnectionSubscription {
                    override val initial = snapshot.value
                    override val transitions = changes.receiveAsFlow()
                    override fun close() { changes.close() }
                }
            }
        }
        val transport = MockTransport()
        val session = MeshCoreSession(transport, coroutineContext = scope.coroutineContext)
        val messages = MessageService(token, Bytes(ByteArray(32)), session, store, signals, scope)
        val queue = ChatSendQueueService(token, store, messages,
            MessagingChannelQuery { error("DM-only originals must not query a channel") },
            OutgoingChannelReactionIndexer { _, _, _, _, _ -> error("DM-only originals must not index a channel") },
            signals, scope)
        try {
            assertNull(session.currentSelfInfo)
            body(queue)
            assertTrue(transport.sentData.isEmpty())
            assertFalse(transport.isConnected())
        } finally {
            try { assertTrue(queue.shutdown().isComplete) } finally {
                try { assertTrue(messages.close().isComplete) } finally {
                    try { session.stop() } finally { job.cancelAndJoin() }
                }
            }
        }
    }
}
