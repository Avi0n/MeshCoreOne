// AndroidOnly: WP-208 Real-session queue replacement and process-owned query-failure regressions.
package com.meshcoreone.android.core.services.messaging

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import com.meshcoreone.android.core.protocol.model.CommandCode
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.TestFactory

@OptIn(ExperimentalCoroutinesApi::class)
class ChatSendQueueProgressTest {
    @TestFactory fun recovery() = listOf(
        native("two genuine query timeouts across graphs fail only A, not same-channel B") {
            val progress = ChatSendQueueProgress()
            val first = connection()
            val a = first.message(MessageStatus.PENDING, 0u)
            val b = first.message(MessageStatus.PENDING, 0u, text = "same channel, different envelope")
            first.pending(a, 2)
            first.store.upsertPendingSend(first.pending(b, 2).copy(sequence = 2))
            val q1 = drain(first, progress)
            assertEquals(1, first.sends(CommandCode.GET_CHANNEL).size)
            advanceTimeBy(1_999); runCurrent()
            assertTrue(queryFailures(first, a.id).isEmpty())
            assertEquals(MessageStatus.FAILED, first.stored(a.id).status)
            advanceTimeBy(1); runCurrent()
            assertEquals(MessageStatus.PENDING, first.stored(a.id).status)
            assertIs<MeshCoreException.Timeout>(queryFailures(first, a.id).single().cause)
            q1.transportDidOpen(); runCurrent()
            assertEquals(1, first.sends(CommandCode.GET_CHANNEL).size)
            assertEquals(1, queryFailures(first, a.id).size)
            assertEquals(4L, first.store.fetchPendingSendsForMessage(EntityKey(RADIO, a.id)).single().attemptCount)
            first.close()

            val second = connection(first.store, 2)
            val events = second.service.statusEvents()
            val q2 = drain(second, progress)
            advanceTimeBy(2_000); runCurrent()
            assertIs<MeshCoreException.Timeout>(queryFailures(second, a.id).single().cause)
            assertFalse(second.store.hasPendingSend(EntityKey(RADIO, a.id)))
            assertEquals(MessageStatus.FAILED, second.stored(a.id).status)
            assertTrue(second.store.hasPendingSend(EntityKey(RADIO, b.id)))
            assertEquals(MessageStatus.PENDING, second.stored(b.id).status)
            assertEquals(3L, second.store.fetchPendingSendsForMessage(EntityKey(RADIO, b.id)).single().attemptCount)
            assertEquals(1, q2.queuedEnvelopeCount)
            assertEquals(listOf(MessageStatusEvent.Failed(a.id)), statuses(second.service, events))
            q2.transportDidOpen(); runCurrent()
            assertEquals(1, second.sends(CommandCode.GET_CHANNEL).size)
            assertTrue(queryFailures(second, b.id).isEmpty())
            assertTrue(second.store.hasPendingSend(EntityKey(RADIO, b.id)))
        },
        native("default sixteen-query cap survives sixteen real replacement sessions") {
            val progress = ChatSendQueueProgress()
            val store = RecordingStore()
            var message: MessageDTO? = null
            repeat(16) { index ->
                val h = connection(store, index + 1L)
                val m = message ?: h.message(MessageStatus.PENDING, 0u).also {
                    message = it
                    h.pending(it, 2)
                }
                val q = drain(h, progress, ChatSendQueueConfig())
                advanceTimeBy(1_999); runCurrent()
                assertTrue(queryFailures(h, m.id).isEmpty())
                assertTrue(store.hasPendingSend(EntityKey(RADIO, m.id)))
                advanceTimeBy(1); runCurrent()
                assertIs<MeshCoreException.Timeout>(queryFailures(h, m.id).single().cause)
                assertEquals(1, h.sends(CommandCode.GET_CHANNEL).size)
                if (index < 15) {
                    assertEquals(MessageStatus.PENDING, h.stored(m.id).status)
                    assertTrue(store.hasPendingSend(EntityKey(RADIO, m.id)))
                } else {
                    q.awaitDrainCompletion()
                    assertEquals(MessageStatus.FAILED, h.stored(m.id).status)
                    assertFalse(store.hasPendingSend(EntityKey(RADIO, m.id)))
                }
                h.close()
                assertEquals(0, h.signals.subscriberCount)
            }
        },
        native("real non-null query success resets the consecutive failure budget") {
            val progress = ChatSendQueueProgress()
            val first = connection()
            val m = first.message(MessageStatus.PENDING, 0u); first.pending(m, 2)
            failQuery(first, progress); first.close()
            val second = connection(first.store, 2)
            second.transport.channelReplies[0u] = ChannelInfo(0u, "existing", Bytes(ByteArray(16) { 1 }))
            drain(second, progress)
            assertEquals(1, second.sends(CommandCode.GET_CHANNEL).size)
            assertTrue(queryFailures(second, m.id).isEmpty())
            assertEquals(MessageStatus.PENDING, second.stored(m.id).status)
            second.close()
            val third = connection(first.store, 3)
            failQuery(third, progress)
            assertEquals(MessageStatus.PENDING, third.stored(m.id).status)
            assertTrue(third.store.hasPendingSend(EntityKey(RADIO, m.id)))
            third.close()
            val fourth = connection(first.store, 4)
            failQuery(fourth, progress)
            assertEquals(MessageStatus.FAILED, fourth.stored(m.id).status)
            assertFalse(fourth.store.hasPendingSend(EntityKey(RADIO, m.id)))
        },
        native("real null query terminal cleanup clears only the completed envelope") {
            val progress = ChatSendQueueProgress()
            val first = connection()
            val m = first.message(MessageStatus.PENDING, 0u)
            val row = first.pending(m, 2)
            failQuery(first, progress); first.close()
            val second = connection(first.store, 2)
            second.transport.channelReplies[0u] = ChannelInfo(0u, "", Bytes(ByteArray(16)))
            val q = drain(second, progress, query = MessagingChannelQuery { index ->
                val info = second.session.getChannel(index)
                if (info.name.isEmpty() && info.secret.all { it == 0.toUByte() }) null else info
            })
            q.awaitDrainCompletion()
            assertEquals(MessageStatus.FAILED, second.stored(m.id).status)
            assertFalse(second.store.hasPendingSend(EntityKey(RADIO, m.id)))
            second.close()
            first.store.saveMessage(m)
            first.store.upsertPendingSend(row)
            val third = connection(first.store, 3)
            failQuery(third, progress)
            assertEquals(MessageStatus.PENDING, third.stored(m.id).status)
            assertTrue(third.store.hasPendingSend(EntityKey(RADIO, m.id)))
        },
        native("successful channel send clears retained progress after pending deletion") {
            val progress = ChatSendQueueProgress()
            val first = connection()
            val m = first.message(MessageStatus.PENDING, 0u); val row = first.pending(m, 2)
            failQuery(first, progress); first.close()
            val second = connection(first.store, 2)
            second.transport.channelError = null
            val q = drain(second, progress); q.awaitDrainCompletion()
            assertTrue(second.sends(CommandCode.GET_CHANNEL).isEmpty())
            assertFalse(second.store.hasPendingSend(EntityKey(RADIO, m.id)))
            second.close()
            first.store.saveMessage(m); first.store.upsertPendingSend(row)
            val third = connection(first.store, 3)
            failQuery(third, progress)
            assertEquals(MessageStatus.PENDING, third.stored(m.id).status)
        },
        native("cancelled real query spends no budget and preserves prior failures") {
            val progress = ChatSendQueueProgress()
            val first = connection()
            val m = first.message(MessageStatus.PENDING, 0u); first.pending(m, 2)
            drain(first, progress)
            first.close()
            assertTrue(queryFailures(first, m.id).isEmpty())
            val second = connection(first.store, 2)
            failQuery(second, progress)
            assertEquals(MessageStatus.PENDING, second.stored(m.id).status)
            second.close()
            val third = connection(first.store, 3)
            drain(third, progress); third.close()
            assertTrue(queryFailures(third, m.id).isEmpty())
            val fourth = connection(first.store, 4)
            failQuery(fourth, progress)
            assertEquals(MessageStatus.FAILED, fourth.stored(m.id).status)
            assertFalse(fourth.store.hasPendingSend(EntityKey(RADIO, m.id)))
        },
        native("terminal deletion failure retains exhaustion until real cleanup succeeds") {
            val progress = ChatSendQueueProgress()
            val first = connection()
            val m = first.message(MessageStatus.PENDING, 0u); first.pending(m, 2)
            failQuery(first, progress); first.close()
            val second = connection(first.store, 2)
            second.store.faults["deletePendingSendsForMessage"] = storageFailure()
            failQuery(second, progress)
            assertTrue(second.store.hasPendingSend(EntityKey(RADIO, m.id)))
            assertTrue(second.diagnostics.filterIsInstance<MessagingDiagnostic.Failure>().any {
                it.operation == "terminalPersistence.${m.id}"
            })
            second.close()
            second.store.faults.clear()
            val third = connection(first.store, 3)
            failQuery(third, progress)
            assertEquals(MessageStatus.FAILED, third.stored(m.id).status)
            assertFalse(third.store.hasPendingSend(EntityKey(RADIO, m.id)))
        },
        native("preflight persistence failure does not spend the query budget") {
            val progress = ChatSendQueueProgress()
            val first = connection()
            val m = first.message(MessageStatus.PENDING, 0u); first.pending(m, 2)
            first.store.faults["incrementPendingSendAttemptCount"] = storageFailure()
            drain(first, progress)
            assertTrue(first.sends(CommandCode.SEND_CHANNEL_MESSAGE).isEmpty())
            assertTrue(first.sends(CommandCode.GET_CHANNEL).isEmpty())
            assertTrue(queryFailures(first, m.id).isEmpty())
            first.close(); first.store.faults.clear()
            val second = connection(first.store, 2)
            failQuery(second, progress)
            assertEquals(MessageStatus.PENDING, second.stored(m.id).status)
            assertTrue(second.store.hasPendingSend(EntityKey(RADIO, m.id)))
        },
        native("replacement pending identity starts fresh even for the same message UUID") {
            val progress = ChatSendQueueProgress()
            val first = connection()
            val m = first.message(MessageStatus.PENDING, 0u); val row = first.pending(m, 2)
            failQuery(first, progress); first.close()
            first.store.deletePendingSendsForMessage(EntityKey(RADIO, m.id))
            first.store.upsertPendingSend(row.copy(id = UUID.randomUUID()))
            val second = connection(first.store, 2)
            failQuery(second, progress)
            assertEquals(MessageStatus.PENDING, second.stored(m.id).status)
            assertTrue(second.store.hasPendingSend(EntityKey(RADIO, m.id)))
            second.close()
            val third = connection(first.store, 3)
            failQuery(third, progress)
            assertFalse(third.store.hasPendingSend(EntityKey(RADIO, m.id)))
        },
        native("failed hydration does not erase the retained query budget") {
            val progress = ChatSendQueueProgress()
            val first = connection()
            val m = first.message(MessageStatus.PENDING, 0u); first.pending(m, 2)
            failQuery(first, progress); first.close()
            val second = connection(first.store, 2)
            second.store.faults["fetchPendingSends"] = storageFailure()
            assertFailsWith<com.meshcoreone.android.core.contracts.domain.errors.ChatSendQueueServiceException> {
                drain(second, progress)
            }
            assertTrue(second.sends(CommandCode.GET_CHANNEL).isEmpty())
            second.close(); second.store.faults.clear()
            val third = connection(first.store, 3)
            failQuery(third, progress)
            assertEquals(MessageStatus.FAILED, third.stored(m.id).status)
            assertFalse(third.store.hasPendingSend(EntityKey(RADIO, m.id)))
        },
        native("genuine query storage failure remains typed and spends one query failure") {
            val progress = ChatSendQueueProgress()
            val first = connection()
            val m = first.message(MessageStatus.PENDING, 0u); first.pending(m, 2)
            failQuery(first, progress); first.close()
            val second = connection(first.store, 2)
            val failure = storageFailure()
            val q = drain(second, progress, query = MessagingChannelQuery { throw failure })
            q.awaitDrainCompletion()
            assertSame(failure, queryFailures(second, m.id).single().cause)
            assertEquals(MessageStatus.FAILED, second.stored(m.id).status)
            assertFalse(second.store.hasPendingSend(EntityKey(RADIO, m.id)))
        },
        native("successful hydration prunes externally deleted pending progress") {
            val progress = ChatSendQueueProgress()
            val first = connection()
            val m = first.message(MessageStatus.PENDING, 0u); val row = first.pending(m, 2)
            failQuery(first, progress); first.close()
            first.store.deletePendingSendsForMessage(EntityKey(RADIO, m.id))
            val second = connection(first.store, 2)
            val q = drain(second, progress); q.awaitDrainCompletion(); second.close()
            first.store.saveMessage(m); first.store.upsertPendingSend(row)
            val third = connection(first.store, 3)
            failQuery(third, progress)
            assertEquals(MessageStatus.PENDING, third.stored(m.id).status)
        },
        native("same message and pending UUIDs on another radio have independent budgets") {
            val progress = ChatSendQueueProgress()
            val first = connection()
            val m = first.message(MessageStatus.PENDING, 0u); val row = first.pending(m, 2)
            failQuery(first, progress); first.close()
            first.store.saveMessage(m.copy(radioId = PEER_RADIO))
            first.store.upsertPendingSend(row.copy(radioId = PEER_RADIO))
            val peer = connection(first.store, 2, PEER_RADIO)
            failQuery(peer, progress)
            assertEquals(MessageStatus.PENDING, peer.store.fetchMessage(EntityKey(PEER_RADIO, m.id))?.status)
            assertTrue(peer.store.hasPendingSend(EntityKey(PEER_RADIO, m.id)))
            peer.close()
            val third = connection(first.store, 3)
            failQuery(third, progress)
            assertFalse(third.store.hasPendingSend(EntityKey(RADIO, m.id)))
            assertTrue(third.store.hasPendingSend(EntityKey(PEER_RADIO, m.id)))
        },
        native("stale attach and retired lease cannot mutate successor progress") {
            val progress = ChatSendQueueProgress()
            val messageID = UUID.randomUUID(); val pendingID = UUID.randomUUID()
            val first = progress.attach(token())
            assertEquals(1L, first.increment(messageID, pendingID))
            val second = progress.attach(token(generation = 2))
            assertFailsWith<CancellationException> { progress.attach(token()) }
            assertFailsWith<CancellationException> { first.clear(messageID) }
            assertFailsWith<CancellationException> { first.increment(messageID, pendingID) }
            first.retire()
            assertEquals(2L, second.increment(messageID, pendingID))
            second.retire()
            assertFailsWith<CancellationException> { second.clear(messageID) }
        },
    ) + listOf(false, true).map { throws ->
        native("late retired query ${if (throws) "failure" else "success"} cannot change successor progress") {
            val progress = ChatSendQueueProgress()
            val first = connection()
            val m = first.message(MessageStatus.PENDING, 0u); first.pending(m, 2)
            val reply = CompletableDeferred<Unit>()
            try {
                drain(first, progress, query = MessagingChannelQuery {
                    withContext(NonCancellable) {
                        reply.await()
                        if (throws) throw MeshCoreException.Timeout()
                        ChannelInfo(0u, "late", Bytes(ByteArray(16) { 1 }))
                    }
                })
                first.signals.set(token(generation = 2), DeviceConnectionState.CONNECTED)
                runCurrent()
                val second = connection(first.store, 2)
                drain(second, progress)
                if (throws) {
                    reply.complete(Unit); runCurrent()
                }
                advanceTimeBy(2_000); runCurrent()
                assertEquals(MessageStatus.PENDING, second.stored(m.id).status)
                assertEquals(1, queryFailures(second, m.id).size)
                if (!throws) {
                    reply.complete(Unit); runCurrent()
                }
                assertTrue(queryFailures(first, m.id).isEmpty())
                first.close(); second.close()
                val third = connection(first.store, 3)
                failQuery(third, progress)
                assertEquals(MessageStatus.FAILED, third.stored(m.id).status)
                assertFalse(third.store.hasPendingSend(EntityKey(RADIO, m.id)))
            } finally { reply.complete(Unit) }
        }
    }

    private suspend fun TestScope.connection(
        store: RecordingStore = RecordingStore(), generation: Long = 1, radio: RadioId = RADIO,
    ) = Harness(this, MessageServiceConfig(poolBackoff = PoolBackoffConfig(attemptCap = 0)),
        store, generation, radio = radio).also {
        it.start()
        it.transport.channelError = 2u
    }

    private suspend fun TestScope.drain(
        h: Harness, progress: ChatSendQueueProgress,
        config: ChatSendQueueConfig = ChatSendQueueConfig(maxConsecutiveFetchChannelFailures = 2),
        query: MessagingChannelQuery = MessagingChannelQuery { h.session.getChannel(it) },
    ): ChatSendQueueService {
        val queue = h.queue(config, query, progress = progress)
        h.signals.set(h.token, DeviceConnectionState.CONNECTED)
        queue.hydrate(); runCurrent()
        h.signals.set(h.token, DeviceConnectionState.READY); runCurrent()
        return queue
    }

    private suspend fun TestScope.failQuery(h: Harness, progress: ChatSendQueueProgress) {
        drain(h, progress)
        advanceTimeBy(2_000); runCurrent()
    }

    private fun queryFailures(h: Harness, messageID: UUID) =
        h.diagnostics.filterIsInstance<MessagingDiagnostic.Failure>().filter { it.operation == "channelQuery.$messageID" }
}
