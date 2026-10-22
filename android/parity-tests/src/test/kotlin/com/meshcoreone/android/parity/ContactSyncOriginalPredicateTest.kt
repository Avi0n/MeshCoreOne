// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/ContactServiceSyncTests.swift@db14559b39d32322b06477c6ae676112f583db50
// Adaptation: production Room/coordinator/App adapter; injected scheduler replaces wall-clock waits.
package com.meshcoreone.android.parity

import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.app.container.ContactSyncCoordinatorAdapter
import com.meshcoreone.android.app.container.OriginalCase
import com.meshcoreone.android.app.container.RoomProcessTest
import com.meshcoreone.android.app.container.SharedPreferencesContactFlags
import com.meshcoreone.android.core.contracts.domain.ContactServiceProtocol
import com.meshcoreone.android.core.contracts.domain.ContactSyncResult
import com.meshcoreone.android.core.model.ContactFrame
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.VContactIdentity
import com.meshcoreone.android.core.model.uppercaseHexString
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactFlags
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.core.protocol.model.MeshContact
import com.meshcoreone.android.core.protocol.session.ContactFetchResult
import com.meshcoreone.android.core.protocol.session.ContactSessionOps
import com.meshcoreone.android.core.services.contacts.ContactService
import com.meshcoreone.android.core.services.contacts.ContactServiceError
import com.meshcoreone.android.core.services.sync.SyncAdvertContactSyncOutcome
import com.meshcoreone.android.core.services.sync.SyncClock
import com.meshcoreone.android.core.services.sync.SyncCoordinator
import com.meshcoreone.android.core.services.sync.performAdvertContactSync
import com.meshcoreone.android.core.services.sync.setAdvertContactSyncWaitTimeoutOverride
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ContactSyncOriginalPredicateTest : RoomProcessTest() {
    private val radio = RadioId(UUID.randomUUID())
    private val services = mutableListOf<ContactService>()

    @After fun finishContactEvents() = services.forEach { it.finishEvents() }

    private fun key(byte: Int) = Bytes(ByteArray(32) { byte.toByte() })

    private fun frame(key: Bytes, name: String) = ContactFrame(
        key, ContactType.CHAT, 0u, 0u, Bytes.EMPTY, name, 0u, 0.0, 0.0, 0u,
    )

    private fun contact(byte: Int, name: String, modified: Long = 0): MeshContact {
        val key = key(byte)
        return MeshContact(
            key.uppercaseHexString(), key, ContactType.CHAT, ContactFlags(0u), 0u, Bytes.EMPTY,
            name, Instant.EPOCH, 0.0, 0.0, Instant.ofEpochSecond(modified),
        )
    }

    private suspend fun seedDevice(lastSync: UInt = 0u) {
        store.saveDevice(DeviceDTO(
            id = radio.value, radioId = radio, publicKey = key(1), nodeName = "TestDevice",
            firmwareVersion = 8u, firmwareVersionString = "v1.0.0", manufacturerName = "TestMfg",
            buildDate = "01 Jan 2025", maxContacts = 100u, maxChannels = 8u, multiAcks = 0u,
            lastContactSync = lastSync, isActive = true,
        ))
    }

    private fun service(session: StubSession, coordinator: SyncCoordinator? = null) = ContactService(
        session, store, store, coordinator?.let(::ContactSyncCoordinatorAdapter), null,
        SharedPreferencesContactFlags(ApplicationProvider.getApplicationContext()),
    ).also { services += it }

    private fun TestScope.coordinator(): SyncCoordinator {
        val clock = object : SyncClock {
            override fun now(): Instant = Instant.ofEpochSecond(1_704_067_200)
            override fun elapsed(): Duration = testScheduler.currentTime.milliseconds
            override suspend fun sleep(duration: Duration) = delay(duration)
        }
        return SyncCoordinator(backgroundScope, clock)
    }

    @OriginalCase("ContactServiceSyncTests::Full sync persists all device contacts and prunes locals not on device()")
    @Test fun originalFullSyncCommitsAliceBobPrunesStaleAndReturnsExactWatermark() = runTest {
        seedDevice()
        store.saveContact(radio, frame(key(0xDD), "Stale"))
        val session = StubSession(listOf(contact(0xAA, "Alice", 1_700_000_000), contact(0xBB, "Bob")))

        val result = service(session).syncContacts(radio, null)

        assertEquals(2L, result.contactsReceived)
        assertFalse(result.isIncremental)
        assertEquals(1_700_000_000u, result.lastSyncTimestamp)
        assertEquals(setOf("Alice", "Bob"), store.fetchContacts(radio).map { it.name }.toSet())
        assertNull(store.fetchContact(radio, key(0xDD)))
        assertEquals(listOf<Instant?>(null), session.invocations)
    }

    @OriginalCase("ContactServiceSyncTests::Full sync prunes true orphans but keeps the ZephCore V-contact()")
    @Test fun originalFullSyncPrunesDDOrphanButRetainsSelfDerivedVContact() = runTest {
        seedDevice()
        val vKey = assertNotNull(VContactIdentity.publicKey(key(1)))
        store.saveContact(radio, frame(vKey, "vTestDevice"))
        store.saveContact(radio, frame(key(0xDD), "Orphan"))

        service(StubSession(listOf(contact(0xAA, "Alice")))).syncContacts(radio, null)

        assertNull(store.fetchContact(radio, key(0xDD)))
        assertNotNull(store.fetchContact(radio, vKey))
        assertEquals(setOf("Alice", "vTestDevice"), store.fetchContacts(radio).map { it.name }.toSet())
    }

    @OriginalCase("ContactServiceSyncTests::Incremental sync upserts without pruning unseen locals()")
    @Test fun originalSince100IncrementalSyncCommitsAliceAndPreservesExistingDD() = runTest {
        seedDevice()
        store.saveContact(radio, frame(key(0xDD), "Existing"))
        val session = StubSession(listOf(contact(0xAA, "Alice")))

        val result = service(session).syncContacts(radio, Instant.ofEpochSecond(100))

        assertEquals(1L, result.contactsReceived)
        assertTrue(result.isIncremental)
        assertEquals(setOf("Alice", "Existing"), store.fetchContacts(radio).map { it.name }.toSet())
        assertEquals(listOf(Instant.ofEpochSecond(100)), session.invocations)
    }

    @OriginalCase("ContactServiceSyncTests::Manual refresh waits for an in-flight advert delta sync()")
    @Test fun originalManualRefreshMakesNoFetchDuring200MillisAdvertClaimThenFetchesOnce() = runTest {
        seedDevice(1_704_067_200u)
        val coordinator = coordinator()
        val gated = GatedAdvertService()
        val session = StubSession(listOf(contact(0xAA, "Alice")))
        val service = service(session, coordinator)
        val advert = async { coordinator.performAdvertContactSync(false, radio, store, gated) }
        gated.started.await()
        val refresh = async(start = CoroutineStart.UNDISPATCHED) { service.syncContactsForRefresh(radio) }
        try {
            advanceTimeBy(200)
            runCurrent()
            assertFalse(refresh.isCompleted)
            assertTrue(session.invocations.isEmpty(), "Manual refresh must not fetch while the advert claim is held")

            gated.release.complete(Unit)
            assertEquals(SyncAdvertContactSyncOutcome.SYNCED, advert.await())
            refresh.await()
            assertEquals(1, session.invocations.size)
            assertEquals(setOf("Alice"), store.fetchContacts(radio).map { it.name }.toSet())
        } finally {
            gated.release.complete(Unit)
        }
    }

    @OriginalCase("ContactServiceSyncTests::Manual refresh claim is atomic so a racing advert delta returns busy()")
    @Test fun originalHeldManualFetchMakesRacingAdvertBusyWithoutEnteringItsBody() = runTest {
        seedDevice(1_704_067_200u)
        val coordinator = coordinator()
        val firstGate = GatedAdvertService()
        val session = StubSession(listOf(contact(0xAA, "Alice")), hold = true)
        val service = service(session, coordinator)
        val firstAdvert = async { coordinator.performAdvertContactSync(false, radio, store, firstGate) }
        firstGate.started.await()
        val refresh = async(start = CoroutineStart.UNDISPATCHED) { service.syncContactsForRefresh(radio) }
        try {
            firstGate.release.complete(Unit)
            assertEquals(SyncAdvertContactSyncOutcome.SYNCED, firstAdvert.await())
            session.started.await()
            assertTrue(session.isHeld)
            val secondGate = GatedAdvertService()
            val secondAdvert = coordinator.performAdvertContactSync(false, radio, store, secondGate)
            assertEquals(SyncAdvertContactSyncOutcome.BUSY, secondAdvert)
            assertFalse(secondGate.started.isCompleted, "A busy advert must not enter its sync body")

            session.release.complete(Unit)
            refresh.await()
            assertEquals(1, session.invocations.size)
            assertEquals(setOf("Alice"), store.fetchContacts(radio).map { it.name }.toSet())
        } finally {
            firstGate.release.complete(Unit)
            session.release.complete(Unit)
        }
    }

    @OriginalCase("ContactServiceSyncTests::Manual refresh surfaces syncInterrupted when the advert claim wait times out()")
    @Test fun original40MillisClaimTimeoutSurfacesTypedSyncInterruptedWithoutFetching() = runTest {
        seedDevice(1_704_067_200u)
        val coordinator = coordinator()
        coordinator.setAdvertContactSyncWaitTimeoutOverride(40.milliseconds)
        val gated = GatedAdvertService()
        val session = StubSession(listOf(contact(0xAA, "Alice")))
        val service = service(session, coordinator)
        val advert = async { coordinator.performAdvertContactSync(false, radio, store, gated) }
        gated.started.await()
        val refresh = async(start = CoroutineStart.UNDISPATCHED) {
            assertFailsWith<ContactServiceError.SyncInterrupted> { service.syncContactsForRefresh(radio) }
        }
        try {
            runCurrent()
            advanceTimeBy(39)
            runCurrent()
            assertFalse(refresh.isCompleted, "The original 40ms bound must not expire at 39ms")
            advanceTimeBy(1)
            runCurrent()
            assertTrue(refresh.isCompleted, "The claim wait must surface at the original 40ms bound")
            refresh.await()
            assertTrue(session.invocations.isEmpty(), "A timed-out refresh must not fetch contacts")

            gated.release.complete(Unit)
            assertEquals(SyncAdvertContactSyncOutcome.SYNCED, advert.await())
        } finally {
            gated.release.complete(Unit)
        }
    }

    private class GatedAdvertService : ContactServiceProtocol {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        override suspend fun syncContacts(radioId: RadioId, since: Instant?): ContactSyncResult {
            started.complete(Unit)
            release.await()
            return ContactSyncResult(0, 0u, true)
        }
    }

    private class StubSession(private val contacts: List<MeshContact>, private val hold: Boolean = false) : ContactSessionOps {
        val invocations = mutableListOf<Instant?>()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val isHeld: Boolean get() = hold && started.isCompleted && !release.isCompleted
        override suspend fun getContacts(since: Instant?): List<MeshContact> = getContactsReportingTotal(since).contacts
        override suspend fun getContactsReportingTotal(since: Instant?): ContactFetchResult {
            invocations += since
            started.complete(Unit)
            if (hold) release.await()
            return ContactFetchResult(contacts, contacts.size.toLong())
        }
        override suspend fun getContact(publicKey: Bytes): Nothing = unexpected()
        override suspend fun addContact(contact: MeshContact): Nothing = unexpected()
        override suspend fun removeContact(publicKey: Bytes): Nothing = unexpected()
        override suspend fun resetPath(publicKey: Bytes): Nothing = unexpected()
        override suspend fun sendPathDiscovery(destination: Bytes): Nothing = unexpected()
        override suspend fun shareContact(publicKey: Bytes): Nothing = unexpected()
        override suspend fun exportContact(publicKey: Bytes?): Nothing = unexpected()
        override suspend fun importContact(cardData: Bytes): Nothing = unexpected()
        override suspend fun changeContactFlags(contact: MeshContact, flags: ContactFlags): Nothing = unexpected()
        private fun unexpected(): Nothing = throw AssertionError("Unexpected non-fetch contact-session operation")
    }
}
