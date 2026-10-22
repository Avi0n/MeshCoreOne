// PortedFrom: MC1Services/Tests/MC1ServicesTests/AdvertisementServiceTests.swift@db14559b39d32322b06477c6ae676112f583db50
// Adaptation: real Room/public wire session; fixed phone clock and bounded Room-I/O drain.
package com.meshcoreone.android.parity

import com.meshcoreone.android.app.container.FirmwareRadio
import com.meshcoreone.android.app.container.OriginalCase
import com.meshcoreone.android.app.container.RoomProcessTest
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.ContactFrame
import com.meshcoreone.android.core.model.DebugLogLevel
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.EventFilter
import com.meshcoreone.android.core.protocol.event.MeshEvent
import com.meshcoreone.android.core.protocol.event.PathInfo
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.core.protocol.session.MeshCoreSession
import com.meshcoreone.android.core.services.contacts.AdvertContactSyncOutcome
import com.meshcoreone.android.core.services.contacts.AdvertisementClock
import com.meshcoreone.android.core.services.contacts.AdvertisementEvent
import com.meshcoreone.android.core.services.contacts.AdvertisementLogSink
import com.meshcoreone.android.core.services.contacts.AdvertisementService
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
class AdvertisementPathRecencyOriginalPredicateTest : RoomProcessTest() {
    @OriginalCase("AdvertisementServiceTests::pathResponse stamps lastHeard and preserves radio lastModified()")
    @Test fun originalDirectPathResponseCommitsPhoneRecencyWithoutChangingTheRadioWatermark() = runTest {
        withPathService(
            Bytes.fromHex("a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebfc0c1c2c3c4"),
            "PathPeer",
        ) { f ->
            val path = PathInfo(Bytes.fromHex("a5a6a7a8a9aa"), 0u, Bytes.EMPTY, 0u, Bytes.EMPTY)
            assertEquals(0u, f.before.lastHeardTimestamp)

            f.transport.push(Bytes.fromHex("8d00a5a6a7a8a9aa0000"))
            awaitPredicate(f, "the real path response to commit recency and reach its public event") {
                f.serviceEvents == listOf(AdvertisementEvent.PathDiscoveryResponse(path))
            }

            reportPathRecencyPhase("room-contact-check|START")
            val updated = assertNotNull(store.fetchContact(f.radio, f.before.publicKey))
            assertEquals(1_700_000_100u, updated.lastModified)
            assertTrue((updated.lastHeardTimestamp ?: 0u) > 0u)
            assertEquals(f.before.copy(lastHeardTimestamp = 1_704_067_200u), updated)
            assertEquals(updated, store.fetchContactByPrefix(f.radio, path.publicKeyPrefix))
            assertEquals(listOf<MeshEvent>(MeshEvent.PathResponse(path)), f.protocolEvents)
            assertTrue(f.handlerFlags.isEmpty(), "A path response must not start a delta round")
            f.expected = updated
            reportPathRecencyPhase("room-contact-check|END")
        }
    }

    @OriginalCase("AdvertisementServiceTests::pathUpdate does not stamp lastHeard()")
    @Test fun originalPathUpdateCompletesBothRealDeltaRoundsWithoutStampingPhoneRecency() = runTest {
        withPathService(
            Bytes.fromHex("a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebfc0c1c2c3c4c5"),
            "PathUpdatePeer",
        ) { f ->
            f.service.setDeltaSyncHandler {
                f.handlerFlags += it
                AdvertContactSyncOutcome.SYNCED
            }
            f.transport.push(Bytes.fromHex("81a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebfc0c1c2c3c4c5"))
            awaitPredicate(f, "incremental and unchanged-watermark full-refetch rounds to finish") {
                f.handlerFlags == listOf(false, true) &&
                    f.serviceEvents == listOf(AdvertisementEvent.ContactUpdated, AdvertisementEvent.ContactUpdated)
            }

            reportPathRecencyPhase("room-contact-check|START")
            val updated = assertNotNull(store.fetchContact(f.radio, f.before.publicKey))
            assertEquals(0u, updated.lastHeardTimestamp ?: 0u)
            assertEquals(f.before, updated)
            assertEquals(listOf<MeshEvent>(MeshEvent.PathUpdate(f.before.publicKey)), f.protocolEvents)
            assertEquals(listOf(false, true), f.handlerFlags)
            reportPathRecencyPhase("room-contact-check|END")
        }
    }

    private suspend fun TestScope.withPathService(
        key: Bytes,
        name: String,
        body: suspend TestScope.(PathFixture) -> Unit,
    ) {
        reportPathRecencyPhase("room-seed|START")
        val radio = RadioId(UUID.randomUUID())
        val foreignRadio = RadioId(UUID.randomUUID())
        val device = DeviceDTO(
            id = radio.value, radioId = radio, publicKey = Bytes(ByteArray(32) { 1 }), nodeName = "TestRadio",
            firmwareVersion = 9u, firmwareVersionString = "v1.13.0", manufacturerName = "TestMfg",
            buildDate = "01 Jan 2025", lastConnected = PHONE_NOW, isActive = true,
        )
        store.saveDevice(device)
        store.saveDevice(device.copy(id = foreignRadio.value, radioId = foreignRadio, nodeName = "ForeignRadio", isActive = false))
        val devicesBefore = store.fetchDevices()
        val frame = ContactFrame(key, ContactType.CHAT, 0u, 0u, Bytes.EMPTY, name, 1_700_000_000u, 0.0, 0.0, 1_700_000_100u)
        store.saveContact(radio, frame)
        store.saveContact(foreignRadio, frame.copy(name = "ForeignPeer"))
        val before = assertNotNull(store.fetchContact(radio, key))
        val foreign = assertNotNull(store.fetchContact(foreignRadio, key))
        assertEquals(frame, before.toContactFrame())
        assertEquals(0u, before.lastHeardTimestamp)
        reportPathRecencyPhase("room-seed|END")

        val owner = Job(backgroundScope.coroutineContext.job)
        val scope = CoroutineScope(backgroundScope.coroutineContext + owner)
        val transport = FirmwareRadio(device.publicKey, device.nodeName)
        val session = MeshCoreSession(transport, clock = publicSessionClock(), coroutineContext = backgroundScope.coroutineContext)
        val clock = object : AdvertisementClock {
            override fun now(): Duration = testScheduler.currentTime.milliseconds
            override suspend fun sleepUntil(deadline: Duration) {
                if (deadline > now()) delay(deadline - now())
            }
            override fun wallNow(): Instant = PHONE_NOW
        }
        val diagnostics = mutableListOf<Diagnostic>()
        val service = AdvertisementService(
            session, session, store, scope,
            advertSyncDebounce = Duration.ZERO, advertSyncMinInterval = Duration.ZERO,
            advertSyncBusyBackoff = Duration.ZERO, clock = clock,
            logSink = AdvertisementLogSink { category, level, message -> diagnostics += Diagnostic(category, level, message) },
        )
        val f = PathFixture(radio, before, transport, service, diagnostics)
        val protocolStream = session.events(EventFilter { it is MeshEvent.PathResponse || it is MeshEvent.PathUpdate })
        val serviceStream = service.events()
        val protocolListener = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            protocolStream.toList(f.protocolEvents)
        }
        val serviceListener = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            serviceStream.toList(f.serviceEvents)
        }
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
        retainFailure {
            reportPathRecencyPhase("session-start|START")
            session.start()
            reportPathRecencyPhase("session-start|END")
            assertEquals(
                listOf(Bytes.fromHex("01032020202020204d65736843")), transport.sent.toList(),
                "Only the independently specified APP_START is expected",
            )
            reportPathRecencyPhase("service-monitor-start|START")
            service.startEventMonitoring(radio)
            reportPathRecencyPhase("service-monitor-start|END")
            reportPathRecencyPhase("service-initial-drain|START")
            runCurrent()
            reportPathRecencyPhase("service-initial-drain|END")
            reportPathRecencyPhase("body|START")
            body(f)
            reportPathRecencyPhase("body|END")
        }
        retainFailure {
            reportPathRecencyPhase("service-monitor-stop|START")
            service.stopEventMonitoring()
            reportPathRecencyPhase("service-monitor-stop|END")
        }
        retainFailure {
            reportPathRecencyPhase("owner-cancel-join|START")
            owner.cancelAndJoin()
            reportPathRecencyPhase("owner-cancel-join|END")
        }
        retainFailure {
            reportPathRecencyPhase("service-events-finish|START")
            service.finishEvents()
            reportPathRecencyPhase("service-events-finish|END")
        }
        retainFailure {
            reportPathRecencyPhase("service-listener-join|START")
            serviceListener.join()
            assertTrue(serviceListener.isCompleted, "The public service stream must finish")
            reportPathRecencyPhase("service-listener-join|END")
        }
        retainFailure {
            reportPathRecencyPhase("room-final-check|START")
            assertEquals(listOf(f.expected), store.fetchContacts(radio))
            assertEquals(listOf(foreign), store.fetchContacts(foreignRadio), "Same-key foreign-radio row must survive unchanged")
            assertEquals(devicesBefore, store.fetchDevices())
            assertEquals(listOf(Bytes.fromHex("01032020202020204d65736843")), transport.sent.toList())
            assertEquals(emptyList(), diagnostics.filter { it.level == DebugLogLevel.ERROR }, "Service failures must not hide behind event waits")
            reportPathRecencyPhase("room-final-check|END")
        }
        retainFailure {
            reportPathRecencyPhase("session-stop|START")
            session.stop()
            reportPathRecencyPhase("session-stop|END")
        }
        retainFailure {
            reportPathRecencyPhase("protocol-listener-join|START")
            protocolListener.join()
            assertTrue(protocolListener.isCompleted, "Session stop must finish its public event stream")
            assertEquals(1, transport.connects)
            assertEquals(1, transport.closes)
            assertEquals(0, transport.readers)
            reportPathRecencyPhase("protocol-listener-join|END")
        }
        primary?.let { throw it }
    }

    private fun TestScope.awaitPredicate(f: PathFixture, label: String, predicate: () -> Boolean) {
        reportPathRecencyPhase("predicate|START")
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            runCurrent()
            if (predicate()) {
                reportPathRecencyPhase("predicate|END")
                return
            }
            Thread.sleep(2)
        }
        assertTrue(
            predicate(),
            "Timed out awaiting $label; protocol=${f.protocolEvents}; service=${f.serviceEvents}; " +
                "rounds=${f.handlerFlags}; diagnostics=${f.diagnostics}",
        )
        reportPathRecencyPhase("predicate|END")
    }

    private class PathFixture(
        val radio: RadioId,
        val before: ContactDTO,
        val transport: FirmwareRadio,
        val service: AdvertisementService,
        val diagnostics: MutableList<Diagnostic>,
        val protocolEvents: MutableList<MeshEvent> = mutableListOf(),
        val serviceEvents: MutableList<AdvertisementEvent> = mutableListOf(),
        val handlerFlags: MutableList<Boolean> = mutableListOf(),
        var expected: ContactDTO = before,
    )

    private data class Diagnostic(val category: String, val level: DebugLogLevel, val message: String)

    private companion object {
        val PHONE_NOW: Instant = Instant.ofEpochSecond(1_704_067_200)
    }
}
