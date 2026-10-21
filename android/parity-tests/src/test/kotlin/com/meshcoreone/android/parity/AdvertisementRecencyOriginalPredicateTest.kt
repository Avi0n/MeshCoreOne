// PortedFrom: MC1Services/Tests/MC1ServicesTests/AdvertisementServiceTests.swift@db14559b39d32322b06477c6ae676112f583db50
// Adaptation: real Room/public wire session; one injected phone clock, fixed before advert ingestion.
package com.meshcoreone.android.parity

import com.meshcoreone.android.app.container.FirmwareRadio
import com.meshcoreone.android.app.container.OriginalCase
import com.meshcoreone.android.app.container.RoomProcessTest
import com.meshcoreone.android.core.data.repository.RoomPersistenceStore
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.ContactFrame
import com.meshcoreone.android.core.model.DebugLogLevel
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.DiscoveredNodeDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.EventFilter
import com.meshcoreone.android.core.protocol.event.MeshEvent
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.core.protocol.session.MeshCoreSession
import com.meshcoreone.android.core.services.contacts.AdvertContactSyncOutcome
import com.meshcoreone.android.core.services.contacts.AdvertisementClock
import com.meshcoreone.android.core.services.contacts.AdvertisementEvent
import com.meshcoreone.android.core.services.contacts.AdvertisementLogSink
import com.meshcoreone.android.core.services.contacts.AdvertisementService
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AdvertisementRecencyOriginalPredicateTest : RoomProcessTest() {
    @OriginalCase("AdvertisementServiceTests::known contact advert bumps lastHeard without getContact()")
    @Test fun originalKnownAdvertCommitsContactAndDiscoverRecencyWithoutAnyContactQuery() = runTest {
        val frame = frame(
            Bytes.fromHex("b1b2b3b4b5b6b7b8b9babbbcbdbebfc0c1c2c3c4c5c6c7c8c9cacbcccdcecfd0"),
            "Known", 1_700_000_000u, 1_700_000_100u,
        )
        withAdvertService(frame, known = true) { f ->
            val before = assertNotNull(f.expectedContact)
            val nodeBefore = assertNotNull(f.expectedNode)
            assertEquals(0u, before.lastHeardTimestamp)
            assertTrue(nodeBefore.lastHeard < PHONE_NOW.minusSeconds(1))
            f.service.setDeltaSyncHandler { fullRefetch ->
                f.handlerFlags += fullRefetch
                AdvertContactSyncOutcome.SYNCED
            }
            val beforeAdvert = PHONE_NOW.minusSeconds(1)
            f.transport.push(Bytes.fromHex("80b1b2b3b4b5b6b7b8b9babbbcbdbebfc0c1c2c3c4c5c6c7c8c9cacbcccdcecfd0"))
            awaitPredicate("known advert phone stamp") {
                (f.persistence.fetchContact(f.radio, frame.publicKey)?.lastHeardTimestamp ?: 0u) > 0u
            }

            val contact = assertNotNull(f.persistence.fetchContact(f.radio, frame.publicKey))
            assertTrue((contact.lastHeardTimestamp ?: 0u) >= beforeAdvert.epochSecond.toUInt())
            assertEquals(before.copy(lastHeardTimestamp = 1_704_067_200u), contact)
            val node = assertNotNull(f.persistence.fetchDiscoveredNodes(f.radio).firstOrNull { it.publicKey == frame.publicKey })
            assertTrue(node.lastHeard >= beforeAdvert)
            assertEquals(nodeBefore.copy(lastHeard = PHONE_NOW), node)
            awaitPredicate("known advert handler and both public updates") {
                f.handlerFlags.isNotEmpty() && f.serviceEvents.size == 2
            }
            assertTrue(f.handlerFlags.size >= 1)
            assertEquals(listOf(false), f.handlerFlags)
            assertEquals(listOf<AdvertisementEvent>(AdvertisementEvent.ContactUpdated, AdvertisementEvent.ContactUpdated), f.serviceEvents)
            assertEquals(listOf(APP_START), f.transport.sent.toList(), "Known adverts must not issue getContact or GET_CONTACTS")
            f.expectedContact = contact
            f.expectedNode = node
        }
    }

    @OriginalCase("AdvertisementServiceTests::unknown advert contact stamped lastHeard survives stale-node prune()")
    @Test fun originalUnknownAdvertStampsTheRealDeltaInsertBeforeStaleNodePruning() = runTest {
        val frame = frame(
            Bytes.fromHex("5c5d5e5f606162636465666768696a6b6c6d6e6f707172737475767778797a7b"),
            "JustHeard", 1_000_000u, 1_000_000u,
        )
        withAdvertService(frame, known = false) { f ->
            var inserted: ContactDTO? = null
            f.service.setDeltaSyncHandler { fullRefetch ->
                f.handlerFlags += fullRefetch
                val saved = f.persistence.saveContact(f.radio, frame)
                assertTrue(saved.isNew)
                inserted = assertNotNull(f.persistence.fetchContact(f.radio, frame.publicKey))
                assertEquals(0u, inserted?.lastHeardTimestamp, "The actual delta insert starts unstamped")
                AdvertContactSyncOutcome.SYNCED
            }
            assertNull(f.persistence.fetchContact(f.radio, frame.publicKey))
            val beforeAdvert = PHONE_NOW.minusSeconds(1)
            f.transport.push(Bytes.fromHex("805c5d5e5f606162636465666768696a6b6c6d6e6f707172737475767778797a7b"))
            awaitPredicate("post-delta stamp must set lastHeardTimestamp after insert") {
                (f.persistence.fetchContact(f.radio, frame.publicKey)?.lastHeardTimestamp ?: 0u) > 0u
            }

            val contact = assertNotNull(f.persistence.fetchContact(f.radio, frame.publicKey))
            val cutoff = PHONE_NOW.minusSeconds(30L * 86_400L).epochSecond.toUInt()
            assertEquals(1_701_475_200u, cutoff)
            assertEquals(1_000_000u, contact.lastModified)
            assertTrue(contact.lastModified < cutoff)
            assertTrue((contact.lastHeardTimestamp ?: 0u) >= beforeAdvert.epochSecond.toUInt())
            assertTrue(contact.recencyTimestamp >= beforeAdvert.epochSecond.toUInt())
            assertFalse(contact.matchesStaleNodePrune(cutoff))
            assertTrue(Instant.ofEpochSecond(1_000_000).epochSecond.toDouble() < cutoff.toDouble())
            assertEquals(assertNotNull(inserted).copy(lastHeardTimestamp = 1_704_067_200u), contact)
            assertEquals(frame, contact.toContactFrame())
            awaitPredicate("unknown advert reconcile and final public update") { f.serviceEvents.size == 2 }
            assertEquals(listOf(false), f.handlerFlags)
            assertEquals(
                listOf<AdvertisementEvent>(
                    AdvertisementEvent.NewContactDiscovered("JustHeard", contact.id, ContactType.CHAT),
                    AdvertisementEvent.ContactUpdated,
                ),
                f.serviceEvents,
            )
            val node = f.persistence.fetchDiscoveredNodes(f.radio).single()
            assertEquals(
                DiscoveredNodeDTO(
                    node.id, f.radio, frame.publicKey, "JustHeard", ContactType.CHAT.rawValue, PHONE_NOW,
                    1_000_000u, 0.0, 0.0, 0u, Bytes.EMPTY, null, null,
                ),
                node,
            )
            f.expectedContact = contact
            f.expectedNode = node
        }
    }

    private suspend fun TestScope.withAdvertService(
        frame: ContactFrame,
        known: Boolean,
        body: suspend TestScope.(AdvertFixture) -> Unit,
    ) {
        val phoneClock = PhoneClock()
        val persistence = RoomPersistenceStore(db, backgroundScope, phoneClock)
        val radio = RadioId(UUID.randomUUID())
        val foreignRadio = RadioId(UUID.randomUUID())
        val device = DeviceDTO(
            id = radio.value, radioId = radio, publicKey = Bytes(ByteArray(32) { 1 }), nodeName = "TestRadio",
            firmwareVersion = 9u, firmwareVersionString = "v1.13.0", manufacturerName = "TestMfg",
            buildDate = "01 Jan 2025", lastConnected = PHONE_NOW, isActive = true,
        )
        val owner = Job(backgroundScope.coroutineContext.job)
        val scope = CoroutineScope(backgroundScope.coroutineContext + owner)
        val transport = FirmwareRadio(device.publicKey, device.nodeName)
        val session = MeshCoreSession(transport, clock = publicSessionClock(), coroutineContext = backgroundScope.coroutineContext)
        val clock = object : AdvertisementClock {
            override fun now(): Duration = testScheduler.currentTime.milliseconds
            override suspend fun sleepUntil(deadline: Duration) {
                if (deadline > now()) delay(deadline - now())
            }
            override fun wallNow(): Instant = phoneClock.instant()
        }
        val failures = mutableListOf<String>()
        val service = AdvertisementService(
            session, session, persistence, scope,
            advertSyncDebounce = Duration.ZERO, advertSyncMinInterval = Duration.ZERO,
            advertSyncBusyBackoff = Duration.ZERO, clock = clock,
            logSink = AdvertisementLogSink { category, level, message ->
                if (level == DebugLogLevel.ERROR) failures += "$category: $message"
            },
        )
        val f = AdvertFixture(radio, persistence, transport, service)
        val protocolStream = session.events(EventFilter { it is MeshEvent.Advertisement })
        val serviceStream = service.events()
        val protocolListener = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { protocolStream.toList(f.protocolEvents) }
        val serviceListener = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { serviceStream.toList(f.serviceEvents) }
        var primary: Throwable? = null
        suspend fun retainFailure(action: suspend () -> Unit) {
            try {
                action()
            } catch (failure: Throwable) {
                val previous = primary
                if (previous == null) primary = failure
                else if (previous !== failure) previous.addSuppressed(failure)
            }
        }
        var foreign: ContactDTO? = null
        var foreignNode: DiscoveredNodeDTO? = null
        retainFailure {
            persistence.saveDevice(device)
            persistence.saveDevice(device.copy(id = foreignRadio.value, radioId = foreignRadio, nodeName = "ForeignRadio", isActive = false))
            persistence.saveContact(foreignRadio, frame.copy(name = "ForeignPeer"))
            persistence.upsertDiscoveredNode(foreignRadio, frame.copy(name = "ForeignPeer"))
            foreign = assertNotNull(persistence.fetchContact(foreignRadio, frame.publicKey))
            foreignNode = persistence.fetchDiscoveredNodes(foreignRadio).single()
            if (known) {
                persistence.saveContact(radio, frame)
                f.expectedContact = assertNotNull(persistence.fetchContact(radio, frame.publicKey))
                f.expectedNode = persistence.upsertDiscoveredNode(radio, frame).node
            }
            phoneClock.current = PHONE_NOW
            session.start()
            assertEquals(listOf(APP_START), transport.sent.toList())
            service.startEventMonitoring(radio)
            runCurrent()
            body(f)
        }
        retainFailure { service.stopEventMonitoring() }
        retainFailure { owner.cancelAndJoin() }
        retainFailure { service.finishEvents() }
        retainFailure {
            serviceListener.join()
            assertTrue(serviceListener.isCompleted)
        }
        retainFailure {
            assertEquals(listOfNotNull(f.expectedContact), persistence.fetchContacts(radio))
            assertEquals(listOfNotNull(f.expectedContact), store.fetchContacts(radio), "The process-owned repository reads the same committed rows")
            assertEquals(listOfNotNull(f.expectedNode), persistence.fetchDiscoveredNodes(radio))
            assertEquals(listOf(assertNotNull(foreign)), persistence.fetchContacts(foreignRadio))
            assertEquals(listOf(assertNotNull(foreignNode)), persistence.fetchDiscoveredNodes(foreignRadio))
            assertEquals(listOf(device), persistence.fetchDevices().filter { it.radioId == radio })
            assertEquals(listOf(device.copy(id = foreignRadio.value, radioId = foreignRadio, nodeName = "ForeignRadio", isActive = false)),
                persistence.fetchDevices().filter { it.radioId == foreignRadio })
            assertEquals(listOf(APP_START), transport.sent.toList())
            assertTrue(failures.isEmpty(), "No service failure may hide behind a recency predicate: $failures")
        }
        retainFailure { session.stop() }
        retainFailure {
            protocolListener.join()
            assertTrue(protocolListener.isCompleted)
            assertEquals(listOf<MeshEvent>(MeshEvent.Advertisement(frame.publicKey)), f.protocolEvents)
            assertEquals(1, transport.connects)
            assertEquals(1, transport.closes)
            assertEquals(0, transport.readers)
        }
        retainFailure { persistence.close() }
        primary?.let { throw it }
    }

    private suspend fun TestScope.awaitPredicate(label: String, predicate: suspend () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            runCurrent()
            if (predicate()) return
            Thread.sleep(2)
        }
        assertTrue(predicate(), "Timed out awaiting $label")
    }

    private class AdvertFixture(
        val radio: RadioId,
        val persistence: RoomPersistenceStore,
        val transport: FirmwareRadio,
        val service: AdvertisementService,
        val handlerFlags: MutableList<Boolean> = mutableListOf(),
        val protocolEvents: MutableList<MeshEvent> = mutableListOf(),
        val serviceEvents: MutableList<AdvertisementEvent> = mutableListOf(),
        var expectedContact: ContactDTO? = null,
        var expectedNode: DiscoveredNodeDTO? = null,
    )

    private fun frame(key: Bytes, name: String, advert: UInt, modified: UInt) =
        ContactFrame(key, ContactType.CHAT, 0u, 0u, Bytes.EMPTY, name, advert, 0.0, 0.0, modified)

    private class PhoneClock : Clock() {
        var current: Instant = PHONE_NOW.minusSeconds(60)
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(current, zone)
        override fun instant(): Instant = current
    }

    private companion object {
        val PHONE_NOW: Instant = Instant.ofEpochSecond(1_704_067_200)
        val APP_START: Bytes = Bytes.fromHex("01032020202020204d65736843")
    }
}
