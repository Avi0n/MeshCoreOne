// PortedFrom: MC1Services/Tests/MC1ServicesTests/AdvertisementServiceTests.swift@db14559b39d32322b06477c6ae676112f583db50
// Adaptation: real Room/public session; acknowledge actual monitor completion instead of settle sleeps.
package com.meshcoreone.android.parity

import com.meshcoreone.android.app.container.FirmwareRadio
import com.meshcoreone.android.app.container.OriginalCase
import com.meshcoreone.android.app.container.RoomProcessTest
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.data.repository.RepositoryIssueReporter
import com.meshcoreone.android.core.data.repository.RoomPersistenceStore
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.ContactFrame
import com.meshcoreone.android.core.model.DebugLogLevel
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.DiscoveredNodeDTO
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.EventFilter
import com.meshcoreone.android.core.protocol.event.MeshEvent
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.core.protocol.session.MeshCoreSession
import com.meshcoreone.android.core.protocol.session.SessionEventStreaming
import com.meshcoreone.android.core.services.contacts.AdvertContactSyncOutcome
import com.meshcoreone.android.core.services.contacts.AdvertisementClock
import com.meshcoreone.android.core.services.contacts.AdvertisementEvent
import com.meshcoreone.android.core.services.contacts.AdvertisementLogSink
import com.meshcoreone.android.core.services.contacts.AdvertisementService
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AdvertisementRollbackOriginalPredicateTest : RoomProcessTest() {
    @OriginalCase("AdvertisementServiceTests::contactDeleted during commit rolls back the resurrected contact()")
    @Test fun originalSuccessfulCommitRemovesTheRealResurrectedRowWithoutDiscoverOrNotification() = runTest {
        val key = Bytes.fromHex("4b4c4d4e4f505152535455565758595a5b5c5d5e5f606162636465666768696a")
        withRollbackService(key) { f ->
            f.expectedProtocol = listOf(MeshEvent.Advertisement(key), MeshEvent.ContactDeleted(key))
            f.installHeldCommit(frame(key, "Ghost"), AdvertContactSyncOutcome.SYNCED)
            f.transport.push(Bytes.fromHex("804b4c4d4e4f505152535455565758595a5b5c5d5e5f606162636465666768696a"))
            awaitPredicate("original handler waiting") { f.hold.waiting }
            val round = f.activeRound()
            f.transport.push(Bytes.fromHex("8f4b4c4d4e4f505152535455565758595a5b5c5d5e5f606162636465666768696a"))
            awaitPredicate("actual 0x8F handler returned before commit") { f.source.handled.size == 2 }
            f.hold.release()
            round.join()
            f.awaitIdle()

            val committed = f.committed.single()
            assertFreshCommit(f.radio, frame(key, "Ghost"), committed)
            assertNull(f.persistence.fetchContact(f.radio, key), "A real re-save after radio deletion must be rolled back")
            assertNull(f.persistence.fetchContact(EntityKey(f.radio, committed.id)))
            assertTrue(f.persistence.fetchDiscoveredNodes(f.radio).isEmpty(), "Rollback must leave no Discover row")
            assertTrue(f.events.count { it == AdvertisementEvent.ContactUpdated } >= 1)
            assertEquals(0, f.events.count { it is AdvertisementEvent.NewContactDiscovered })
            assertEquals(listOf<AdvertisementEvent>(AdvertisementEvent.ContactUpdated, AdvertisementEvent.ContactUpdated), f.events)
            f.assertNoRequeue(listOf(false))
        }
    }

    @OriginalCase("AdvertisementServiceTests::rollback keeps contact the radio deleted then re-added mid-round()")
    @Test fun originalReAdvertClearsTheDeleteAndKeepsTheReaddedRoomContactAndNewHistory() = runTest {
        val advertKey = Bytes.fromHex("939495969798999a9b9c9d9e9fa0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2")
        val deletedKey = Bytes.fromHex("9495969798999a9b9c9d9e9fa0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3")
        withRollbackService(deletedKey) { f ->
            f.expectedProtocol = listOf(MeshEvent.Advertisement(advertKey), MeshEvent.ContactDeleted(deletedKey), MeshEvent.Advertisement(deletedKey))
            val victim = f.persistence.saveContact(f.radio, frame(deletedKey, "SlotVictim")).id
            val oldMessage = message(f.radio, victim, "old slot history")
            f.persistence.saveMessage(oldMessage)
            f.installHeldCommit(frame(deletedKey, "ReAdded"), AdvertContactSyncOutcome.SYNCED)
            f.transport.push(Bytes.fromHex("80939495969798999a9b9c9d9e9fa0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2"))
            awaitPredicate("original handler waiting") { f.hold.waiting }
            f.transport.push(Bytes.fromHex("8f9495969798999a9b9c9d9e9fa0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3"))
            awaitPredicate("real delete and monitor completion") { f.source.handled.size == 2 }
            assertNull(f.persistence.fetchContact(f.radio, deletedKey))
            assertNull(f.persistence.fetchMessage(EntityKey(f.radio, oldMessage.id)), "The actual overwrite cascade deletes old history")
            assertTrue(f.persistence.fetchMessages(EntityKey(f.radio, victim), 50, 0).isEmpty())
            assertEquals(
                listOf<AdvertisementEvent>(
                    AdvertisementEvent.ContactDeletedCleanup(SnapshotList.of(victim)),
                    AdvertisementEvent.NodeStorageFullChanged(false),
                    AdvertisementEvent.ContactUpdated,
                ),
                f.events,
            )
            f.transport.push(Bytes.fromHex("809495969798999a9b9c9d9e9fa0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3"))
            awaitPredicate("actual re-advert handler returned before commit") { f.source.handled.size == 3 }
            assertTrue(f.hold.waiting)
            assertNull(f.persistence.fetchContact(f.radio, deletedKey))
            f.hold.release()
            f.awaitIdle()

            val contact = assertNotNull(f.persistence.fetchContact(f.radio, deletedKey), "A radio re-added contact must survive rollback")
            assertEquals("ReAdded", contact.name)
            assertNotEquals(victim, contact.id)
            assertFreshCommit(f.radio, frame(deletedKey, "ReAdded"), f.committed.first())
            assertEquals(f.committed.first().copy(lastHeardTimestamp = PHONE_STAMP), contact)
            assertTrue(f.committed.all { it.id == contact.id })
            val node = f.persistence.fetchDiscoveredNodes(f.radio).single()
            assertEquals(frame(deletedKey, "ReAdded"), contact.toContactFrame())
            assertEquals(
                DiscoveredNodeDTO(node.id, f.radio, deletedKey, "ReAdded", ContactType.CHAT.rawValue, PHONE_NOW,
                    1_700_000_000u, 0.0, 0.0, 0u, Bytes.EMPTY, null, null),
                node,
            )
            assertNull(f.persistence.fetchContact(f.radio, advertKey))
            assertEquals(
                listOf<AdvertisementEvent>(
                    AdvertisementEvent.ContactDeletedCleanup(SnapshotList.of(victim)),
                    AdvertisementEvent.NodeStorageFullChanged(false),
                    AdvertisementEvent.ContactUpdated,
                    AdvertisementEvent.ContactUpdated,
                    AdvertisementEvent.ContactUpdated,
                ),
                f.events,
            )
            // Only after the bare re-added row and reconcile proved revival; a DM cannot mask a stale tombstone.
            val newMessage = message(f.radio, contact.id, "new slot history")
            f.persistence.saveMessage(newMessage)
            f.assertNoRequeue(listOf(false, true))
            assertEquals(contact, f.persistence.fetchContact(f.radio, deletedKey))
            assertEquals(listOf(newMessage), f.persistence.fetchMessages(EntityKey(f.radio, contact.id), 50, 0))
            assertNull(f.persistence.fetchMessage(EntityKey(f.radio, oldMessage.id)))
            f.expectedContact = contact
            f.retainedMessages += newMessage
            f.deletedMessages += oldMessage
        }
    }

    @OriginalCase("AdvertisementServiceTests::contactDeleted during failed commit rolls back and does not re-queue()")
    @Test fun originalFailedCommitRollsBackItsRealEarlyWriteAndCannotRequeueTheDeletedKey() = runTest {
        val key = Bytes.fromHex("4c4d4e4f505152535455565758595a5b5c5d5e5f606162636465666768696a6b")
        withRollbackService(key) { f ->
            f.expectedProtocol = listOf(MeshEvent.Advertisement(key), MeshEvent.ContactDeleted(key))
            f.installHeldCommit(frame(key, "Ghost"), AdvertContactSyncOutcome.FAILED)
            f.transport.push(Bytes.fromHex("804c4d4e4f505152535455565758595a5b5c5d5e5f606162636465666768696a6b"))
            awaitPredicate("original handler waiting") { f.hold.waiting }
            val round = f.activeRound()
            f.transport.push(Bytes.fromHex("8f4c4d4e4f505152535455565758595a5b5c5d5e5f606162636465666768696a6b"))
            awaitPredicate("actual 0x8F handler returned before failed commit") { f.source.handled.size == 2 }
            f.hold.release()
            round.join()
            f.awaitIdle()

            val committed = f.committed.single()
            assertFreshCommit(f.radio, frame(key, "Ghost"), committed)
            assertNull(f.persistence.fetchContact(f.radio, key), "An early real write must roll back even when the round reports FAILED")
            assertNull(f.persistence.fetchContact(EntityKey(f.radio, committed.id)))
            assertTrue(f.persistence.fetchDiscoveredNodes(f.radio).isEmpty())
            assertEquals(listOf<AdvertisementEvent>(AdvertisementEvent.ContactUpdated), f.events)
            f.assertNoRequeue(listOf(false))
        }
    }

    @OriginalCase("AdvertisementServiceTests::rollback still runs when teardown cancels the sync mid-commit()")
    @Test fun originalCancelledTeardownStillRollsBackTheRealCommitLandingAfterStop() = runTest {
        val key = Bytes.fromHex("4d4e4f505152535455565758595a5b5c5d5e5f606162636465666768696a6b6c")
        withRollbackService(key) { f ->
            f.expectedProtocol = listOf(MeshEvent.Advertisement(key), MeshEvent.ContactDeleted(key))
            val known = f.persistence.saveContact(f.radio, frame(key, "Known")).id
            val oldMessage = message(f.radio, known, "history before teardown")
            f.persistence.saveMessage(oldMessage)
            f.installHeldCommit(frame(key, "Ghost"), AdvertContactSyncOutcome.SYNCED, commitAcrossCancellation = true)
            f.transport.push(Bytes.fromHex("804d4e4f505152535455565758595a5b5c5d5e5f606162636465666768696a6b6c"))
            awaitPredicate("original handler waiting") { f.hold.waiting }
            val nodeBeforeDelete = f.persistence.fetchDiscoveredNodes(f.radio).single()
            val round = f.activeRound()
            var completionCause: Throwable? = null
            round.invokeOnCompletion { completionCause = it }
            f.transport.push(Bytes.fromHex("8f4d4e4f505152535455565758595a5b5c5d5e5f606162636465666768696a6b6c"))
            awaitPredicate("known row really deleted before stop") { f.source.handled.size == 2 }
            assertNull(f.persistence.fetchContact(f.radio, key))
            assertNull(f.persistence.fetchMessage(EntityKey(f.radio, oldMessage.id)))

            f.service.stopEventMonitoring()
            assertTrue(round.isCancelled, "The genuine production round must observe teardown cancellation")
            assertFalse(round.isCompleted, "The original held commit has not landed yet")
            assertTrue(f.committed.isEmpty())
            f.hold.release()
            round.join()
            assertIs<CancellationException>(completionCause)
            assertTrue(round.isCompleted)
            val committed = f.committed.single()
            assertFreshCommit(f.radio, frame(key, "Ghost"), committed)
            assertNotEquals(known, committed.id)
            assertNull(f.persistence.fetchContact(f.radio, key), "A genuine commit landing after stop must still be rolled back")
            assertNull(f.persistence.fetchContact(EntityKey(f.radio, committed.id)))
            assertNull(f.persistence.fetchMessage(EntityKey(f.radio, oldMessage.id)))
            assertEquals(listOf(nodeBeforeDelete), f.persistence.fetchDiscoveredNodes(f.radio))
            runCurrent()
            assertEquals(
                listOf<AdvertisementEvent>(
                    AdvertisementEvent.ContactUpdated,
                    AdvertisementEvent.ContactDeletedCleanup(SnapshotList.of(known)),
                    AdvertisementEvent.NodeStorageFullChanged(false),
                    AdvertisementEvent.ContactUpdated,
                    AdvertisementEvent.ContactUpdated,
                ),
                f.events,
            )
            assertEquals(listOf(false), f.handlerFlags)
            f.deletedMessages += oldMessage
        }
    }

    private suspend fun TestScope.withRollbackService(key: Bytes, body: suspend TestScope.(Fixture) -> Unit) {
        val failures = mutableListOf<String>()
        val persistence = RoomPersistenceStore(db, backgroundScope, Clock.fixed(PHONE_NOW, ZoneOffset.UTC),
            RepositoryIssueReporter { operation, failure -> failures += "$operation: ${failure.error}" })
        val radio = RadioId(UUID.randomUUID())
        val foreignRadio = RadioId(UUID.randomUUID())
        val device = DeviceDTO(
            id = radio.value, radioId = radio, publicKey = Bytes(ByteArray(32) { 1 }), nodeName = "TestRadio",
            firmwareVersion = 9u, firmwareVersionString = "v1.13.0", manufacturerName = "TestMfg",
            buildDate = "01 Jan 2025", lastConnected = PHONE_NOW, isActive = true,
        )
        val owner = Job(backgroundScope.coroutineContext.job)
        val transport = FirmwareRadio(device.publicKey, device.nodeName)
        val session = MeshCoreSession(transport, clock = publicSessionClock(), coroutineContext = backgroundScope.coroutineContext)
        val source = ObservedEvents(session)
        val clock = object : AdvertisementClock {
            override fun now(): Duration = testScheduler.currentTime.milliseconds
            override suspend fun sleepUntil(deadline: Duration) { if (deadline > now()) delay(deadline - now()) }
            override fun wallNow(): Instant = PHONE_NOW
        }
        val service = AdvertisementService(
            session, source, persistence, CoroutineScope(backgroundScope.coroutineContext + owner),
            advertSyncDebounce = Duration.ZERO, advertSyncMinInterval = Duration.ZERO,
            advertSyncBusyBackoff = Duration.ZERO, clock = clock,
            logSink = AdvertisementLogSink { category, level, text -> if (level == DebugLogLevel.ERROR) failures += "$category: $text" },
        )
        val f = Fixture(this, radio, persistence, transport, source, service, owner)
        val stream = service.events()
        val listener = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { stream.toList(f.events) }
        var primary: Throwable? = null
        suspend fun retainFailure(action: suspend () -> Unit) {
            try { action() } catch (failure: Throwable) {
                val previous = primary
                if (previous == null) primary = failure else if (previous !== failure) previous.addSuppressed(failure)
            }
        }
        var foreign: ContactDTO? = null
        var survivor: ContactDTO? = null
        var foreignMessage: MessageDTO? = null
        var survivorMessage: MessageDTO? = null
        retainFailure {
            persistence.saveDevice(device)
            persistence.saveDevice(device.copy(id = foreignRadio.value, radioId = foreignRadio, nodeName = "ForeignRadio", isActive = false))
            persistence.saveContact(foreignRadio, frame(key, "ForeignPeer"))
            foreign = assertNotNull(persistence.fetchContact(foreignRadio, key))
            foreignMessage = message(foreignRadio, assertNotNull(foreign).id, "foreign history")
            persistence.saveMessage(assertNotNull(foreignMessage))
            val survivorKey = Bytes.fromHex("d0d1d2d3d4d5d6d7d8d9dadbdcdddedfe0e1e2e3e4e5e6e7e8e9eaebecedeeef")
            persistence.saveContact(radio, frame(survivorKey, "Unrelated"))
            survivor = assertNotNull(persistence.fetchContact(radio, survivorKey))
            survivorMessage = message(radio, assertNotNull(survivor).id, "unrelated history")
            persistence.saveMessage(assertNotNull(survivorMessage))
            session.start()
            service.startEventMonitoring(radio)
            runCurrent()
            f.monitors = owner.children.toSet()
            assertEquals(1, f.monitors.size)
            body(f)
        }
        retainFailure { service.stopEventMonitoring() }
        retainFailure { f.hold.release(); owner.cancelAndJoin() }
        retainFailure { service.finishEvents(); listener.join(); assertTrue(listener.isCompleted) }
        retainFailure {
            assertEquals(listOfNotNull(survivor, f.expectedContact).toSet(), persistence.fetchContacts(radio).toSet())
            assertEquals(persistence.fetchContacts(radio).toSet(), store.fetchContacts(radio).toSet())
            assertEquals(listOf(assertNotNull(foreign)), persistence.fetchContacts(foreignRadio))
            for (message in listOf(assertNotNull(survivorMessage), assertNotNull(foreignMessage)) + f.retainedMessages) {
                assertEquals(message, persistence.fetchMessage(EntityKey(message.radioId, message.id)))
                assertEquals(listOf(message), persistence.fetchMessages(EntityKey(message.radioId, assertNotNull(message.contactID)), 50, 0))
            }
            for (message in f.deletedMessages) assertNull(persistence.fetchMessage(EntityKey(message.radioId, message.id)))
            assertEquals(listOf(device), persistence.fetchDevices().filter { it.radioId == radio })
            assertEquals(listOf(device.copy(id = foreignRadio.value, radioId = foreignRadio, nodeName = "ForeignRadio", isActive = false)),
                persistence.fetchDevices().filter { it.radioId == foreignRadio })
            assertEquals(listOf(APP_START), transport.sent.toList(), "All radio traffic remains the original public startup; no fabricated GET_CONTACTS")
            assertTrue(f.expectedProtocol.isNotEmpty())
            assertEquals(f.expectedProtocol, source.handled, "Exact decoded objects/order acknowledged only after the real service handles them")
            assertTrue(failures.isEmpty(), "Real repository/service errors must not masquerade as rollback success: $failures")
        }
        retainFailure { session.stop() }
        retainFailure { assertEquals(1, transport.connects); assertEquals(1, transport.closes); assertEquals(0, transport.readers) }
        retainFailure { persistence.close() }
        primary?.let { throw it }
    }

    private class Fixture(
        val test: TestScope,
        val radio: RadioId,
        val persistence: RoomPersistenceStore,
        val transport: FirmwareRadio,
        val source: ObservedEvents,
        val service: AdvertisementService,
        val owner: Job,
    ) {
        val hold = Hold()
        var monitors: Set<Job> = emptySet()
        val events = mutableListOf<AdvertisementEvent>()
        val handlerFlags = mutableListOf<Boolean>()
        val committed = mutableListOf<ContactDTO>()
        val retainedMessages = mutableListOf<MessageDTO>()
        val deletedMessages = mutableListOf<MessageDTO>()
        var expectedContact: ContactDTO? = null
        var expectedProtocol: List<MeshEvent> = emptyList()

        fun installHeldCommit(frame: ContactFrame, outcome: AdvertContactSyncOutcome, commitAcrossCancellation: Boolean = false) {
            service.setDeltaSyncHandler { fullRefetch ->
                handlerFlags += fullRefetch
                suspend fun commit(): AdvertContactSyncOutcome {
                    hold.await()
                    persistence.saveContact(radio, frame)
                    committed += assertNotNull(persistence.fetchContact(radio, frame.publicKey))
                    return outcome
                }
                // Swift's already-entered ModelActor write is not aborted by Task cancellation.
                // Keep the real Room write, while the actual production round remains cancelled.
                if (commitAcrossCancellation) withContext(NonCancellable) { commit() } else commit()
            }
        }

        fun activeRound(): Job = owner.children.filter { it !in monitors }.single()

        suspend fun awaitIdle() {
            test.awaitPredicate("all actual production rounds completed") { owner.children.none { it !in monitors } }
            test.runCurrent()
        }

        suspend fun assertNoRequeue(expectedFlags: List<Boolean>) {
            awaitIdle()
            assertEquals(expectedFlags, handlerFlags)
            service.handleReturnToForeground()
            awaitIdle()
            assertEquals(expectedFlags, handlerFlags, "Actual foreground re-arm must find no radio-deleted pending work")
        }
    }

    private class ObservedEvents(private val session: SessionEventStreaming) : SessionEventStreaming by session {
        val handled = mutableListOf<MeshEvent>()
        override fun events(filter: EventFilter): Flow<MeshEvent> {
            val events = session.events(filter)
            return flow {
                events.collect { event ->
                    emit(event)
                    handled += event
                }
            }
        }
    }

    private class Hold {
        private val released = CompletableDeferred<Unit>()
        var waiting = false
            private set
        suspend fun await() {
            waiting = true
            withContext(NonCancellable) { released.await() }
            waiting = false
        }
        fun release() { released.complete(Unit) }
    }

    private companion object {
        val PHONE_NOW: Instant = Instant.ofEpochSecond(1_704_067_200)
        val PHONE_STAMP: UInt = 1_704_067_200u
        val APP_START: Bytes = Bytes.fromHex("01032020202020204d65736843")

        fun frame(key: Bytes, name: String) =
            ContactFrame(key, ContactType.CHAT, 0u, 0u, Bytes.EMPTY, name, 1_700_000_000u, 0.0, 0.0, 1_700_000_100u)

        fun message(radio: RadioId, contact: UUID, text: String) =
            MessageDTO(radioId = radio, contactID = contact, text = text, timestamp = 1_700_000_400u,
                createdAt = PHONE_NOW, sortDate = PHONE_NOW, direction = MessageDirection.INCOMING, status = MessageStatus.DELIVERED)

        fun assertFreshCommit(radio: RadioId, frame: ContactFrame, contact: ContactDTO) {
            assertEquals(
                ContactDTO(id = contact.id, radioId = radio, publicKey = frame.publicKey, name = frame.name,
                    typeRawValue = ContactType.CHAT.rawValue, flags = 0u, outPathLength = 0u, outPath = Bytes.EMPTY,
                    lastAdvertTimestamp = 1_700_000_000u, latitude = 0.0, longitude = 0.0,
                    lastModified = 1_700_000_100u, lastHeardTimestamp = 0u),
                contact,
            )
        }

        suspend fun TestScope.awaitPredicate(label: String, predicate: suspend () -> Boolean) {
            val deadline = System.nanoTime() + 5_000_000_000L
            while (System.nanoTime() < deadline) {
                runCurrent()
                if (predicate()) return
                Thread.sleep(2)
            }
            assertTrue(predicate(), "Timed out awaiting $label")
        }
    }
}
