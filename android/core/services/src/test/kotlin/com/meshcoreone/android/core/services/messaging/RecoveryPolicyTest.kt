// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/ChatSendQueueServiceTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/MessageServiceACKTests.swift@db14559b39d32322b06477c6ae676112f583db50
// Native adaptation: WP-208 maintainer-approved recoverable hydration and per-entry ACK persistence policy.
package com.meshcoreone.android.core.services.messaging

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.contracts.domain.errors.*
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.CommandCode
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.TestFactory

@OptIn(ExperimentalCoroutinesApi::class)
class RecoveryPolicyTest {
    @TestFactory fun recovery() = listOf(
        native("expiry retains only the failed entry and retries without duplicate failure events") {
            val h = Harness(this); h.start()
            val first = h.message(); val later = h.message()
            val tracking = h.tracking(first, age = 60)
            h.service.installPendingAck(tracking)
            h.service.installPendingAck(h.tracking(later, Bytes.of(5, 6, 7, 8), age = 60))
            val observed = mutableListOf<MessageStatusEvent>()
            observe(h, observed)
            val fault = storageFailure(); var writes = 0
            h.store.before = { if (it == "updateMessageStatusUnlessDelivered" && writes++ == 0) throw fault }
            h.service.checkExpiredAcks(); runCurrent()
            assertEquals(MessageStatus.SENT, h.stored(first.id).status)
            assertEquals(MessageStatus.FAILED, h.stored(later.id).status)
            assertSame(tracking, h.service.pendingAck(first.id))
            assertNull(h.service.pendingAck(later.id))
            assertEquals(listOf(MessageStatusEvent.Failed(later.id)), observed)
            assertTrue(h.diagnostics.any { it is MessagingDiagnostic.Failure &&
                it.operation == "ackExpiry.${first.id}" && it.cause === fault })
            h.store.before = null
            h.service.checkExpiredAcks(); h.service.checkExpiredAcks(); runCurrent()
            assertEquals(MessageStatus.FAILED, h.stored(first.id).status)
            assertEquals(0, h.service.pendingAckCount)
            assertEquals(listOf(MessageStatusEvent.Failed(later.id), MessageStatusEvent.Failed(first.id)), observed)
            assertTrue(h.service.close().isComplete); h.close()
        },
        native("fail all diagnoses each failed write continues later entries and retries retained tracking") {
            val h = Harness(this); h.start()
            val messages = List(3) { h.message() }
            val tracking = messages.mapIndexed { index, message ->
                h.tracking(message, Bytes.of(index + 1, 0, 0, 0)).also(h.service::installPendingAck)
            }
            val observed = mutableListOf<MessageStatusEvent>()
            observe(h, observed)
            val faults = listOf(storageFailure(), storageFailure()); var writes = 0
            h.store.before = {
                if (it == "updateMessageStatusUnlessDelivered") {
                    val index = writes++
                    if (index < faults.size) throw faults[index]
                }
            }
            val thrown = assertFailsWith<PersistenceStoreException> { h.service.failAllPendingMessages() }
            runCurrent()
            assertSame(faults[0], thrown); assertTrue(thrown.suppressed.any { it === faults[1] })
            assertEquals(3, writes)
            for (index in 0..1) {
                assertEquals(MessageStatus.SENT, h.stored(messages[index].id).status)
                assertSame(tracking[index], h.service.pendingAck(messages[index].id))
                assertTrue(h.diagnostics.any { it is MessagingDiagnostic.Failure &&
                    it.operation == "failPending.${messages[index].id}" && it.cause === faults[index] })
            }
            assertEquals(MessageStatus.FAILED, h.stored(messages[2].id).status)
            assertNull(h.service.pendingAck(messages[2].id))
            assertEquals(listOf(MessageStatusEvent.Failed(messages[2].id)), observed)
            h.store.before = null
            h.service.failAllPendingMessages(); h.service.failAllPendingMessages(); runCurrent()
            assertEquals(0, h.service.pendingAckCount)
            assertTrue(messages.all { h.stored(it.id).status == MessageStatus.FAILED })
            assertEquals(listOf(messages[2].id, messages[0].id, messages[1].id),
                observed.filterIsInstance<MessageStatusEvent.Failed>().map { it.messageID })
            assertTrue(h.service.close().isComplete); h.close()
        },
        native("unreconciled fail-all persistence errors survive in the teardown report") {
            val h = Harness(this); h.start()
            val message = h.message(); h.service.installPendingAck(h.tracking(message))
            val fault = storageFailure()
            h.store.faults["updateMessageStatusUnlessDelivered"] = fault
            assertSame(fault, assertFailsWith<PersistenceStoreException> { h.service.failAllPendingMessages() })
            assertEquals(MessageStatus.SENT, h.stored(message.id).status)
            assertEquals(1, h.service.pendingAckCount)
            val report = h.service.close()
            assertFalse(report.isComplete); assertTrue(report.issues.any { it.cause === fault })
            h.close()
        },
        native("cancelling fail all keeps uncommitted tracking and does not fail later entries") {
            val h = Harness(this); h.start()
            val first = h.message(); val later = h.message()
            h.service.installPendingAck(h.tracking(first))
            h.service.installPendingAck(h.tracking(later, Bytes.of(5, 6, 7, 8)))
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            h.store.before = {
                if (it == "updateMessageStatusUnlessDelivered") { entered.complete(Unit); release.await() }
            }
            val work = backgroundScope.async { h.service.failAllPendingMessages() }
            entered.await(); work.cancelAndJoin()
            assertTrue(work.isCancelled)
            assertEquals(2, h.service.pendingAckCount)
            assertEquals(MessageStatus.SENT, h.stored(first.id).status)
            assertEquals(MessageStatus.SENT, h.stored(later.id).status)
            assertEquals(1, h.store.calls.count { it == "updateMessageStatusUnlessDelivered" })
            assertTrue(h.diagnostics.none { it is MessagingDiagnostic.Failure })
            h.store.before = null
            h.service.failAllPendingMessages()
            assertEquals(0, h.service.pendingAckCount); h.close()
        },
        native("ownership replacement during fail all prevents later writes and stale notifications") {
            val h = Harness(this); h.start()
            val first = h.message(); val later = h.message()
            h.service.installPendingAck(h.tracking(first))
            h.service.installPendingAck(h.tracking(later, Bytes.of(5, 6, 7, 8)))
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            h.store.before = {
                if (it == "updateMessageStatusUnlessDelivered") { entered.complete(Unit); release.await() }
            }
            val observed = mutableListOf<MessageStatusEvent>()
            observe(h, observed)
            val work = backgroundScope.async {
                assertFailsWith<MessageServiceException> { h.service.failAllPendingMessages() }
            }
            entered.await()
            h.signals.set(token(generation = 2), DeviceConnectionState.READY)
            release.complete(Unit)
            assertEquals(MessageServiceError.NotConnected, work.await().error)
            runCurrent()
            assertEquals(MessageStatus.FAILED, h.stored(first.id).status)
            assertEquals(MessageStatus.SENT, h.stored(later.id).status)
            assertEquals(2, h.service.pendingAckCount)
            assertEquals(1, h.store.calls.count { it == "updateMessageStatusUnlessDelivered" })
            assertTrue(observed.isEmpty()); h.close()
        },
        native("a raw ACK with failed delivery persistence reconciles once without another send") {
            val h = Harness(this); h.start(); h.service.startEventMonitoring()
            val message = h.service.sendDirectMessage("accepted before ACK storage failure", h.contact)
            val tracking = assertNotNull(h.service.pendingAck(message.id))
            val observed = mutableListOf<MessageStatusEvent>()
            observe(h, observed)
            val fault = storageFailure(); h.store.faults["updateMessageAck"] = fault
            h.transport.pushAck(tracking.ackCodes.first(), 250u); runCurrent()
            val retained = assertNotNull(h.service.pendingAck(message.id))
            assertTrue(retained.isDelivered); assertEquals(250u, retained.acknowledgement?.tripTime)
            assertEquals(MessageStatus.SENT, h.stored(message.id).status)
            assertTrue(observed.none { it is MessageStatusEvent.StatusResolved || it is MessageStatusEvent.Failed })
            assertTrue(h.diagnostics.any { it is MessagingDiagnostic.Failure && it.cause === fault })
            h.service.failAllPendingMessages()
            assertSame(retained, h.service.pendingAck(message.id))
            assertEquals(MessageStatus.SENT, h.stored(message.id).status)
            h.store.faults.clear()
            h.service.checkExpiredAcks(); h.service.checkExpiredAcks(); runCurrent()
            assertEquals(MessageStatus.DELIVERED, h.stored(message.id).status)
            assertEquals(250u, h.stored(message.id).roundTripTime)
            assertEquals(0, h.service.pendingAckCount)
            assertEquals(listOf(MessageStatusEvent.StatusResolved(message.id, MessageStatus.DELIVERED, 250u)), observed)
            assertEquals(1, h.sends(CommandCode.SEND_MESSAGE).size)
            assertTrue(h.service.close().isComplete); h.close()
        },
        native("failed hydration retries the same durable envelope once and then latches successful hydration") {
            val h = Harness(this); h.start(); h.transport.acknowledge = true
            val message = h.message(MessageStatus.PENDING); val row = h.pending(message); val queue = h.queue()
            val fault = storageFailure(); h.store.faults["fetchPendingSends"] = fault
            val thrown = assertFailsWith<ChatSendQueueServiceException> { queue.hydrate() }
            assertSame(fault, assertIs<ChatSendQueueServiceError.PersistFailed>(thrown.error).underlying)
            assertEquals(row, h.store.pending.values.single())
            assertEquals(MessageStatus.PENDING, h.stored(message.id).status)
            assertTrue(h.sends(CommandCode.SEND_MESSAGE).isEmpty())
            h.store.faults.clear()
            queue.hydrate(); runCurrent(); queue.awaitDrainCompletion()
            assertEquals(MessageStatus.DELIVERED, h.stored(message.id).status)
            assertTrue(h.store.pending.isEmpty())
            queue.hydrate(); runCurrent(); queue.awaitDrainCompletion()
            assertEquals(2, h.store.calls.count { it == "fetchPendingSends" })
            assertEquals(1, h.sends(CommandCode.SEND_MESSAGE).size)
            assertTrue(queue.shutdown().isComplete); h.close()
        },
        native("cancelling one hydration observer does not cancel the shared load or duplicate its send") {
            val h = Harness(this); h.start(); h.transport.acknowledge = true
            val message = h.message(MessageStatus.PENDING); h.pending(message); val queue = h.queue()
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            h.store.before = { if (it == "fetchPendingSends") { entered.complete(Unit); release.await() } }
            val first = backgroundScope.async { queue.hydrate() }
            entered.await(); first.cancelAndJoin()
            val second = backgroundScope.async { queue.hydrate() }
            runCurrent(); assertFalse(second.isCompleted)
            assertEquals(1, h.store.calls.count { it == "fetchPendingSends" })
            release.complete(Unit); second.await(); runCurrent(); queue.awaitDrainCompletion()
            assertEquals(MessageStatus.DELIVERED, h.stored(message.id).status)
            assertTrue(h.store.pending.isEmpty())
            assertEquals(1, h.sends(CommandCode.SEND_MESSAGE).size)
            assertTrue(queue.shutdown().isComplete); h.close()
        },
    ) + listOf("ACK", "expiry").map { recovery ->
        native("fail-all storage failure resolves through $recovery with complete teardown") {
            val h = Harness(this); h.start(); h.service.startEventMonitoring()
            val message = h.service.sendDirectMessage("recover fail-all through $recovery", h.contact)
            val tracking = assertNotNull(h.service.pendingAck(message.id))
            val observed = mutableListOf<MessageStatusEvent>()
            observe(h, observed)
            val fault = storageFailure()
            h.store.faults["updateMessageStatusUnlessDelivered"] = fault
            assertSame(fault, assertFailsWith<PersistenceStoreException> { h.service.failAllPendingMessages() })
            assertSame(tracking, h.service.pendingAck(message.id))
            assertEquals(MessageStatus.SENT, h.stored(message.id).status)
            assertTrue(observed.isEmpty())
            assertTrue(h.diagnostics.any { it is MessagingDiagnostic.Failure &&
                it.operation == "failPending.${message.id}" && it.cause === fault })
            h.store.faults.clear()
            val expected = if (recovery == "ACK") {
                h.transport.pushAck(tracking.ackCodes.first(), 250u); runCurrent()
                MessageStatusEvent.StatusResolved(message.id, MessageStatus.DELIVERED, 250u)
            } else {
                h.clock.offset += 60_000
                h.service.checkExpiredAcks(); runCurrent()
                MessageStatusEvent.Failed(message.id)
            }
            assertEquals(if (recovery == "ACK") MessageStatus.DELIVERED else MessageStatus.FAILED,
                h.stored(message.id).status)
            assertEquals(0, h.service.pendingAckCount)
            assertEquals(listOf(expected), observed)
            h.service.failAllPendingMessages()
            assertEquals(1, h.sends(CommandCode.SEND_MESSAGE).size)
            assertTrue(h.service.close().isComplete); h.close()
        }
    }

    private fun TestScope.observe(h: Harness, observed: MutableList<MessageStatusEvent>): Job {
        val subscription = h.service.statusEvents()
        return backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            subscription.events.collect {
                assertEquals(h.token, it.token)
                observed += it.event
            }
        }
    }
}
