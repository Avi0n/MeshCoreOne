// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/ChatSendQueueServiceTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/ChatSendQueueServiceAttemptCountTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.messaging

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.contracts.domain.errors.*
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import com.meshcoreone.android.core.protocol.model.CommandCode
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.TestFactory

@OptIn(ExperimentalCoroutinesApi::class)
class SourceChatQueueTest {
    @TestFactory fun queues() = listOf(
        original("ChatSendQueueServiceTests", "hydrate replays persisted PendingSend rows and drains them") {
            val h = Harness(this); val dto = PendingSendDTO.fromEnvelope(DirectMessageEnvelope(UUID.randomUUID(), UUID.randomUUID()), RADIO)
            h.store.insertPendingSendAssigningSequence(dto); val q = h.queue()
            q.hydrate(); runCurrent(); q.awaitDrainCompletion()
            assertTrue(h.store.fetchPendingSends(RADIO).isEmpty()); q.shutdown(); h.close()
        },
        original("ChatSendQueueServiceTests", "transient send error preserves the persisted row while parked on the trigger") {
            val h = failingDM(this); val m = h.message(MessageStatus.PENDING); h.pending(m); val q = h.queue()
            startDrain(h, q)
            val before = h.store.fetchPendingSends(RADIO).single()
            val deletions = h.store.calls.count { it == "deletePendingSendsForMessage" }
            advanceTimeBy(200); runCurrent()
            assertEquals(before, h.store.fetchPendingSends(RADIO).single())
            assertEquals(deletions, h.store.calls.count { it == "deletePendingSendsForMessage" })
            assertEquals(1, h.sends(CommandCode.SEND_MESSAGE).size)
            assertEquals(1, h.store.fetchPendingSends(RADIO).size); assertEquals(MessageStatus.PENDING, h.stored(m.id).status)
            q.shutdown(); h.close()
        },
        original("ChatSendQueueServiceTests", "hydrate processes only the service's own radio rows") {
            val h = Harness(this)
            repeat(2) { h.store.insertPendingSendAssigningSequence(PendingSendDTO.fromEnvelope(DirectMessageEnvelope(UUID.randomUUID(), UUID.randomUUID()), RADIO)) }
            val foreign = PendingSendDTO.fromEnvelope(DirectMessageEnvelope(UUID.randomUUID(), UUID.randomUUID()), PEER_RADIO)
            h.store.insertPendingSendAssigningSequence(foreign); val q = h.queue(); q.hydrate(); runCurrent(); q.awaitDrainCompletion()
            assertTrue(h.store.fetchPendingSends(RADIO).isEmpty()); assertEquals(foreign.messageID, h.store.fetchPendingSends(PEER_RADIO).single().messageID)
            q.shutdown(); h.close()
        },
        original("ChatSendQueueServiceTests", "hydrate fetch returns rows in sequence order") {
            val h = Harness(this); h.start(); h.transport.acknowledge = true
            for ((sequence, text) in listOf(3L to "three", 1L to "one", 2L to "two")) {
                val m = h.message(MessageStatus.PENDING).copy(text = text); h.store.saveMessage(m)
                val row = h.pending(m); h.store.upsertPendingSend(row.copy(sequence = sequence))
            }
            assertEquals(listOf(1L, 2L, 3L), h.store.fetchPendingSends(RADIO).map { it.sequence })
            val q = h.queue(); q.hydrate(); runCurrent(); q.awaitDrainCompletion()
            assertEquals(listOf("one", "two", "three"), h.sends(CommandCode.SEND_MESSAGE).map { it.slice(13, it.size).toByteArray().toString(Charsets.UTF_8) })
            q.shutdown(); h.close()
        },
        original("ChatSendQueueServiceTests", "isTransientDirectMessageError unwraps sessionError on deviceError(3)") {
            assertTrue(ChatSendQueueService.isTransientDirectMessageError(MessageServiceException(MessageServiceError.SessionError(MeshCoreException.DeviceError(3u)))))
        },
        original("ChatSendQueueServiceTests", "isTransientChannelMessageError unwraps sessionError on deviceError(2)") {
            assertTrue(ChatSendQueueService.isTransientChannelMessageError(MessageServiceException(MessageServiceError.SessionError(MeshCoreException.DeviceError(2u)))))
        },
        original("ChatSendQueueServiceTests", "isTransientDirectMessageError treats deviceError(2) as terminal") {
            assertFalse(ChatSendQueueService.isTransientDirectMessageError(MessageServiceException(MessageServiceError.SessionError(MeshCoreException.DeviceError(2u)))))
        },
        original("ChatSendQueueServiceTests", "isTransientChannelMessageError treats deviceError(3) as terminal") {
            assertFalse(ChatSendQueueService.isTransientChannelMessageError(MessageServiceException(MessageServiceError.SessionError(MeshCoreException.DeviceError(3u)))))
        },
        original("ChatSendQueueServiceTests", "isChannelMessageNotFound matches raw MeshCoreError.deviceError(2)") {
            assertTrue(ChatSendQueueService.isChannelMessageNotFound(MeshCoreException.DeviceError(2u)))
        },
        original("ChatSendQueueServiceTests", "isChannelMessageNotFound unwraps MessageServiceError.sessionError(deviceError(2))") {
            assertTrue(ChatSendQueueService.isChannelMessageNotFound(MessageServiceException(MessageServiceError.SessionError(MeshCoreException.DeviceError(2u)))))
        },
        original("ChatSendQueueServiceTests", "isChannelMessageNotFound rejects non-NOT_FOUND device errors") {
            assertFalse(ChatSendQueueService.isChannelMessageNotFound(MeshCoreException.DeviceError(3u)))
            assertFalse(ChatSendQueueService.isChannelMessageNotFound(MessageServiceException(MessageServiceError.SessionError(MeshCoreException.DeviceError(3u)))))
            assertFalse(ChatSendQueueService.isChannelMessageNotFound(MeshCoreException.Timeout()))
        },
        original("ChatSendQueueServiceTests", "channel drain treats deviceError(2) as terminal when fetchChannel confirms the channel is gone") {
            val h = failingChannel(this); val m = h.message(MessageStatus.PENDING, 0u); h.pending(m, 7, true)
            h.transport.channelReplies[0u] = ChannelInfo(0u, "", Bytes(ByteArray(16)))
            val q = h.queue(query = MessagingChannelQuery { index ->
                val info = h.session.getChannel(index)
                if (info.name.isEmpty() && info.secret.all { it == 0.toUByte() }) null else info
            })
            startDrain(h, q); withTimeout(10_000) { q.awaitDrainCompletion() }
            assertEquals(MessageStatus.FAILED, h.stored(m.id).status); assertTrue(h.store.fetchPendingSends(RADIO).isEmpty())
            assertEquals(1, h.sends(CommandCode.GET_CHANNEL).size); q.shutdown(); h.close()
        },
        original("ChatSendQueueServiceTests", "channel drain parks deviceError(2) below disambiguateAfterAttempts") {
            val h = failingChannel(this); val m = h.message(MessageStatus.PENDING, 0u); h.pending(m)
            val q = h.queue(query = MessagingChannelQuery { fail("query below threshold") }); startDrain(h, q)
            assertEquals(1L, h.store.fetchPendingSends(RADIO).single().attemptCount)
            assertEquals(MessageStatus.PENDING, h.stored(m.id).status); q.shutdown(); h.close()
        },
        original("ChatSendQueueServiceTests", "Channel fetchChannel failure counter is per-envelope and does not cascade") {
            val h = failingChannel(this); val a = h.message(MessageStatus.PENDING, 0u); val b = h.message(MessageStatus.PENDING, 0u).copy(text = "second")
            h.store.saveMessage(b); h.pending(a, 7, true)
            val rowB = h.pending(b, 2); h.store.upsertPendingSend(rowB.copy(sequence = 2))
            val q = h.queue(ChatSendQueueConfig(maxConsecutiveFetchChannelFailures = 2),
                MessagingChannelQuery { throw MeshCoreException.Timeout() })
            startDrain(h, q); q.transportDidOpen(); runCurrent()
            assertFalse(h.store.hasPendingSend(EntityKey(RADIO, a.id))); assertEquals(MessageStatus.FAILED, h.stored(a.id).status)
            assertTrue(h.store.hasPendingSend(EntityKey(RADIO, b.id))); assertEquals(3L, h.store.fetchPendingSends(RADIO).single().attemptCount)
            assertEquals(MessageStatus.PENDING, h.stored(b.id).status); q.shutdown(); h.close()
        },
        original("ChatSendQueueServiceTests", "drain bounded wait re-attempts after transportWaitTimeout expires") {
            val h = failingChannel(this); val m = h.message(MessageStatus.PENDING, 0u); h.pending(m)
            val q = h.queue(); startDrain(h, q); assertEquals(1L, h.store.fetchPendingSends(RADIO).single().attemptCount)
            advanceTimeBy(29_999); runCurrent(); assertEquals(1L, h.store.fetchPendingSends(RADIO).single().attemptCount)
            advanceTimeBy(2); runCurrent()
            assertEquals(2L, h.store.fetchPendingSends(RADIO).single().attemptCount)
            assertEquals(MessageStatus.PENDING, h.stored(m.id).status); q.shutdown(); h.close()
        },
        original("ChatSendQueueServiceTests", "hydrate runs once per service instance") {
            val h = Harness(this); val q = h.queue()
            h.store.insertPendingSendAssigningSequence(PendingSendDTO.fromEnvelope(DirectMessageEnvelope(UUID.randomUUID(), UUID.randomUUID()), RADIO))
            q.hydrate(); runCurrent(); q.awaitDrainCompletion(); assertTrue(h.store.fetchPendingSends(RADIO).isEmpty())
            val reads = h.store.calls.count { it == "fetchPendingSends" }
            val kept = PendingSendDTO.fromEnvelope(DirectMessageEnvelope(UUID.randomUUID(), UUID.randomUUID()), RADIO)
            h.store.insertPendingSendAssigningSequence(kept); q.hydrate(); runCurrent()
            q.awaitDrainCompletion()
            assertEquals(reads, h.store.calls.count { it == "fetchPendingSends" })
            assertEquals(kept.messageID, h.store.fetchPendingSends(RADIO).single().messageID); q.shutdown(); h.close()
        },
        original("ChatSendQueueServiceTests", "ChatSendQueueServiceError.notConnected description is non-empty") {
            val error = ChatSendQueueServiceException(ChatSendQueueServiceError.NotConnected)
            assertTrue(assertNotNull(error.message).isNotEmpty()); assertEquals(ChatSendQueueServiceError.NotConnected, error.error)
        },
        original("ChatSendQueueServiceTests", "Transient DM send error must not broadcast .failed") {
            val h = Harness(this, MessageServiceConfig(poolBackoff = PoolBackoffConfig(attemptCap = 2, baseDelay = 0.01)))
            h.start(); h.transport.directError = 3u
            val m = h.message(MessageStatus.PENDING); h.pending(m); val events = h.service.statusEvents()
            val observed = mutableListOf<MessageStatusEvent>()
            val consumer = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                events.events.collect {
                    assertEquals(h.token, it.token)
                    assertFalse(it.event is MessageStatusEvent.Failed)
                    observed += it.event
                }
            }
            val q = h.queue(); startDrain(h, q)
            advanceTimeBy(300); runCurrent()
            assertEquals(3, h.sends(CommandCode.SEND_MESSAGE).size)
            assertFalse(observed.any { it is MessageStatusEvent.Failed })
            assertEquals(1L, h.store.fetchPendingSends(RADIO).single().attemptCount)
            assertEquals(MessageStatus.PENDING, h.stored(m.id).status)
            consumer.cancelAndJoin(); q.shutdown(); h.close()
        },
        original("ChatSendQueueServiceTests", "Terminal DM send error broadcasts .failed exactly once") {
            val h = failingDM(this); h.transport.directError = 5u
            val m = h.message(MessageStatus.PENDING); h.pending(m); val events = h.service.statusEvents()
            val q = h.queue(); startDrain(h, q); q.awaitDrainCompletion()
            assertEquals(listOf(MessageStatusEvent.Failed(m.id)), statuses(h.service, events))
            assertFalse(h.store.hasPendingSend(EntityKey(RADIO, m.id))); q.shutdown(); h.close()
        },
        original("ChatSendQueueServiceTests", "observeConnectionState fires the trigger for an already-ready initial state") {
            val h = failingDM(this); val m = h.message(MessageStatus.PENDING); h.pending(m); val q = h.queue()
            startDrain(h, q); q.observeConnectionState(); runCurrent()
            assertEquals(2L, h.store.fetchPendingSends(RADIO).single().attemptCount); q.shutdown(); h.close()
        },
        original("ChatSendQueueServiceTests", "observeConnectionState keeps a drain parked through .connected and wakes it on .ready") {
            val h = failingDM(this); val m = h.message(MessageStatus.PENDING); h.pending(m); val q = h.queue()
            startDrain(h, q); h.signals.set(h.token, DeviceConnectionState.CONNECTED); runCurrent()
            advanceTimeBy(1000); runCurrent()
            assertEquals(1, h.sends(CommandCode.SEND_MESSAGE).size)
            assertEquals(1L, h.store.fetchPendingSends(RADIO).single().attemptCount)
            h.signals.set(h.token, DeviceConnectionState.READY); runCurrent()
            assertEquals(2L, h.store.fetchPendingSends(RADIO).single().attemptCount); q.shutdown(); h.close()
        },
        original("ChatSendQueueServiceTests", "connection-state ramp fires the trigger exactly once") {
            val h = failingDM(this); val m = h.message(MessageStatus.PENDING); h.pending(m); val q = h.queue()
            startDrain(h, q)
            h.signals.set(null, DeviceConnectionState.DISCONNECTED); h.signals.set(h.token, DeviceConnectionState.CONNECTED)
            h.signals.set(h.token, DeviceConnectionState.SYNCING); h.signals.set(h.token, DeviceConnectionState.READY)
            runCurrent(); assertEquals(2L, h.store.fetchPendingSends(RADIO).single().attemptCount)
            advanceTimeBy(1000); runCurrent(); assertEquals(2L, h.store.fetchPendingSends(RADIO).single().attemptCount)
            q.shutdown(); h.close()
        },
        original("ChatSendQueueServiceAttemptCountTests", "fresh send: row with attemptCount=0 first drain uses fresh timestamp") {
            val h = failingDM(this); val m = h.message(MessageStatus.PENDING); h.pending(m, 0)
            val q = h.queue(); startDrain(h, q)
            assertEquals(1L, h.store.fetchPendingSends(RADIO).single().attemptCount); assertNotEquals(m.timestamp, h.stored(m.id).timestamp)
            q.shutdown(); h.close()
        },
        original("ChatSendQueueServiceAttemptCountTests", "process restart: row with attemptCount=1 rehydrates with preserveTimestamp=true") {
            val h = failingDM(this); val m = h.message(MessageStatus.PENDING); h.pending(m, 1)
            val q = h.queue(); startDrain(h, q)
            assertEquals(2L, h.store.fetchPendingSends(RADIO).single().attemptCount); assertEquals(m.timestamp, h.stored(m.id).timestamp)
            assertEquals(m.timestamp, h.sends(CommandCode.SEND_MESSAGE).single().readUInt32LE(3)); q.shutdown(); h.close()
        },
        original("ChatSendQueueServiceAttemptCountTests", "bump failure parks envelope, preserves row, does not call sendPendingDirectMessage") {
            val h = failingDM(this); val m = h.message(MessageStatus.PENDING); h.pending(m)
            h.store.faults["incrementPendingSendAttemptCount"] = storageFailure(); val q = h.queue(); startDrain(h, q)
            assertEquals(0L, h.store.fetchPendingSends(RADIO).single().attemptCount)
            assertEquals(m.timestamp, h.stored(m.id).timestamp); assertEquals(MessageStatus.PENDING, h.stored(m.id).status)
            assertTrue(h.sends(CommandCode.SEND_MESSAGE).isEmpty()); q.shutdown(); h.close()
        },
    )
    private suspend fun failingDM(test: TestScope): Harness = Harness(test,
        MessageServiceConfig(poolBackoff = PoolBackoffConfig(attemptCap = 0))).also { it.start(); it.transport.directError = 3u }
    private suspend fun failingChannel(test: TestScope): Harness = Harness(test,
        MessageServiceConfig(poolBackoff = PoolBackoffConfig(attemptCap = 0))).also { it.start(); it.transport.channelError = 2u }
    private suspend fun startDrain(h: Harness, queue: ChatSendQueueService) {
        h.signals.set(h.token, DeviceConnectionState.CONNECTED)
        queue.hydrate(); h.test.runCurrent()
        h.signals.set(h.token, DeviceConnectionState.READY); h.test.runCurrent()
    }
}
