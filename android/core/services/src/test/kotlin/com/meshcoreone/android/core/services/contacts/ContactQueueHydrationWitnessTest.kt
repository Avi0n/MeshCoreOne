// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/ChatSendQueueServiceTests.swift@db14559b39d32322b06477c6ae676112f583db50
// WP-209 CM-04 independent contact-lookup witness; original-family ownership remains WP-208.
package com.meshcoreone.android.core.services.contacts

import com.meshcoreone.android.core.contracts.domain.MessagingChannelQuery
import com.meshcoreone.android.core.contracts.domain.OutgoingChannelReactionIndexer
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreProtocol
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.DirectMessageEnvelope
import com.meshcoreone.android.core.model.PendingSendDTO
import com.meshcoreone.android.core.model.PendingSendKind
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.session.MeshCoreSession
import com.meshcoreone.android.core.protocol.transport.mock.MockTransport
import com.meshcoreone.android.core.services.messaging.ChatSendQueueService
import com.meshcoreone.android.core.services.messaging.MessageService
import com.meshcoreone.android.core.services.messaging.RecordingStore
import com.meshcoreone.android.core.services.messaging.TestMessagingClock
import com.meshcoreone.android.core.services.messaging.TestSignals
import com.meshcoreone.android.core.services.messaging.token
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ContactQueueHydrationWitnessTest {
    @Test
    fun missingContactIsLookedUpBeforeTheHydratedEnvelopeIsDeleted() = runTest {
        val radio = RadioId(UUID.randomUUID())
        val envelope = DirectMessageEnvelope(UUID.randomUUID(), UUID.randomUUID())
        val rowID = UUID.randomUUID()
        val clock = TestMessagingClock(testScheduler)
        val row = PendingSendDTO.fromEnvelope(envelope, radio, rowID, clock.wallClock.instant())
        assertEquals(
            PendingSendDTO(rowID, radio, envelope.messageID, PendingSendKind.DM, envelope.contactID,
                null, false, "", 0u, null, 0, clock.wallClock.instant(), 0),
            row,
        )
        val store = RecordingStore()
        assertEquals(1L, store.insertPendingSendAssigningSequence(row))
        val persisted = row.copy(sequence = 1)
        assertEquals(listOf(persisted), store.fetchPendingSends(radio))
        assertTrue(store.contacts.isEmpty())
        assertTrue(store.messages.isEmpty())
        assertTrue(store.devices.isEmpty())
        val contactKey = EntityKey(radio, envelope.contactID)
        assertNull(store.fetchContact(contactKey))
        store.calls.clear()

        val lookupEntered = CompletableDeferred<Unit>()
        val allowLookup = CompletableDeferred<Unit>()
        val lookupCompleted = CompletableDeferred<Unit>()
        val lookupKeys = mutableListOf<EntityKey>()
        val observedStore = object : PersistenceStoreProtocol by store {
            override suspend fun fetchContact(key: EntityKey): ContactDTO? {
                lookupKeys += key
                lookupEntered.complete(Unit)
                allowLookup.await()
                return store.fetchContact(key).also {
                    assertNull(it, "the drain must observe the contact as absent")
                    lookupCompleted.complete(Unit)
                }
            }
        }
        val job = SupervisorJob(coroutineContext[Job])
        val scope = CoroutineScope(coroutineContext + job)
        val token = token(radio)
        val signals = TestSignals(token)
        val transport = MockTransport()
        val session = MeshCoreSession(transport, clock = clock, coroutineContext = scope.coroutineContext)
        val messages = MessageService(token, Bytes(ByteArray(32)), session, observedStore, signals, scope, clock = clock)
        val queue = ChatSendQueueService(token, observedStore, messages,
            MessagingChannelQuery { error("missing-contact DM must not query channels") },
            OutgoingChannelReactionIndexer { _, _, _, _, _ -> error("missing-contact DM must not index reactions") },
            signals, scope, clock = clock)
        try {
            assertNull(session.currentSelfInfo)
            queue.hydrate()
            runCurrent()

            assertTrue(lookupEntered.isCompleted, "the real drain must reach the missing-contact lookup")
            assertEquals(listOf(contactKey), lookupKeys)
            assertEquals(listOf("fetchPendingSends"), store.calls)
            assertEquals(listOf(persisted), store.pending.values.toList(), "the fixture must still exist at lookup entry")
            assertEquals(1, queue.queuedEnvelopeCount)
            assertNull(store.contacts[contactKey])

            allowLookup.complete(Unit)
            queue.awaitDrainCompletion()

            assertTrue(lookupCompleted.isCompleted)
            assertEquals(
                listOf("fetchPendingSends", "fetchContact", "updateMessageStatusUnlessDelivered", "deletePendingSendsForMessage"),
                store.calls,
            )
            assertTrue(store.fetchPendingSends(radio).isEmpty())
            assertEquals(0, queue.queuedEnvelopeCount)
            assertTrue(transport.sentData.isEmpty())
            assertFalse(transport.isConnected())
        } finally {
            allowLookup.complete(Unit)
            try { assertTrue(queue.shutdown().isComplete) } finally {
                try { assertTrue(messages.close().isComplete) } finally {
                    try { session.stop() } finally { job.cancelAndJoin() }
                }
            }
        }
    }
}
