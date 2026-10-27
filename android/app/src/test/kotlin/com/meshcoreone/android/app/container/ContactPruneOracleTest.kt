// PortedFrom: MC1Services/Tests/MC1ServicesTests/SyncCoordinatorTests.swift@db14559b39d32322b06477c6ae676112f583db50
// Adaptation: WP-214 Real ContactService/Room pruning oracles and native error/cancellation safety checks.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.contracts.domain.AppStateProvider
import com.meshcoreone.android.core.contracts.domain.ChannelServiceProtocol
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.MessagePollingServiceProtocol
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.ContactFrame
import com.meshcoreone.android.core.model.DeliveryContext
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.VContactIdentity
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.event.ChannelMessage
import com.meshcoreone.android.core.protocol.event.ContactMessage
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
import com.meshcoreone.android.core.services.sync.SyncPhaseStatus
import com.meshcoreone.android.core.services.sync.SyncState
import com.meshcoreone.android.core.services.sync.performAdvertContactSync
import com.meshcoreone.android.core.services.sync.performFullSync
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ContactPruneOracleTest : RoomProcessTest() {
    private val radio = RadioId(UUID.randomUUID())
    private val services = mutableListOf<ContactService>()

    @After fun finishContactEvents() = services.forEach { it.finishEvents() }

    @OriginalCase("SyncCoordinatorTests::At-capacity connect forces full contact sync and prunes the missing non-favourite()")
    @Test fun atCapacityConnectPrunesEvictedContactAndItsRoomMessages() = runTest {
        seedDevice(maxContacts = 3u)
        val keptA = seedContact(0x11, "KeptA")
        seedContact(0x22, "KeptB")
        val evicted = seedContact(0x99, "Evicted")
        seedMessage(keptA, "keep A")
        seedMessage(evicted, "evicted message")
        val session = ContactStream(listOf(meshContact(0x11, "KeptA"), meshContact(0x22, "KeptB"), meshContact(0x33, "Newcomer")))

        connectSync(session)

        assertEquals(listOf<Instant?>(null), session.invocations)
        assertContactKeys(0x11, 0x22, 0x33)
        assertNull(store.fetchContact(radio, key(0x99)))
        assertEquals(listOf("keep A"), messages(keptA))
        assertTrue(messages(evicted).isEmpty(), "Actual Room contact deletion must remove its direct messages")
    }

    @OriginalCase("SyncCoordinatorTests::Below-capacity connect keeps incremental contact sync and does not prune()")
    @Test fun belowCapacityConnectPreservesUnseenContactAndRoomMessages() = runTest {
        seedDevice(maxContacts = 100u)
        seedContact(0x11, "Kept")
        val orphan = seedContact(0x99, "Orphan")
        seedMessage(orphan, "keep orphan")
        val session = ContactStream(listOf(meshContact(0x11, "Kept")))

        connectSync(session)

        assertEquals(listOf<Instant?>(EPOCH.minusSeconds(1)), session.invocations)
        assertContactKeys(0x11, 0x99)
        assertEquals(listOf("keep orphan"), messages(orphan))
    }

    @OriginalCase("SyncCoordinatorTests::Full sync prune preserves the local V-contact omitted from a complete stream()")
    @Test fun completeSnapshotPreservesVContactButDeletesRealOrphan() = runTest {
        seedDevice()
        val vKey = assertNotNull(VContactIdentity.publicKey(key(1)))
        val vContact = store.saveContact(radio, frame(vKey, "V-Contact")).id
        val orphan = seedContact(0x99, "Orphan")
        seedContact(0x11, "Real0")
        seedContact(0x22, "Real1")
        seedMessage(vContact, "keep virtual")
        seedMessage(orphan, "remove orphan")
        val session = ContactStream(listOf(meshContact(0x11, "Real0"), meshContact(0x22, "Real1")))

        service(session).syncContacts(radio, null)

        assertEquals(setOf(vKey, key(0x11), key(0x22)), store.fetchContacts(radio).map { it.publicKey }.toSet())
        assertEquals(listOf("keep virtual"), messages(vContact))
        assertTrue(messages(orphan).isEmpty())
    }

    @OriginalCase("SyncCoordinatorTests::Full sync prune removes a favourite absent from a complete device set()")
    @Test fun completeSnapshotDeletesAbsentFavouriteAndOnlyItsMessages() = runTest {
        seedDevice()
        val favourite = seedContact(0xAA, "Favourite", ContactFlags.FAVORITE.rawValue)
        assertTrue(assertNotNull(store.fetchContact(EntityKey(radio, favourite))).isFavorite)
        val kept = seedContact(0x11, "Kept0")
        seedContact(0x22, "Kept1")
        seedContact(0x33, "Kept2")
        seedMessage(favourite, "remove favourite")
        seedMessage(kept, "keep survivor")
        val session = ContactStream(listOf(meshContact(0x11, "Kept0"), meshContact(0x22, "Kept1"), meshContact(0x33, "Kept2")))

        service(session).syncContacts(radio, null)

        assertContactKeys(0x11, 0x22, 0x33)
        assertTrue(messages(favourite).isEmpty())
        assertEquals(listOf("keep survivor"), messages(kept))
    }

    @OriginalCase("SyncCoordinatorTests::Full sync prune removes orphans when the device genuinely shrank below half()")
    @Test fun completeOneOfFourSnapshotPrunesAllThreeStaleRowsAndMessages() = runTest {
        seedDevice()
        val survivor = seedContact(0x11, "Survivor")
        seedMessage(survivor, "keep survivor")
        val stale = listOf(0xAA, 0x22, 0x33).map { byte ->
            seedContact(byte, "Stale$byte").also { seedMessage(it, "remove $byte") }
        }
        val session = ContactStream(listOf(meshContact(0x11, "Survivor")))

        service(session).syncContacts(radio, null)

        assertContactKeys(0x11)
        assertEquals(listOf("keep survivor"), messages(survivor))
        stale.forEach { assertTrue(messages(it).isEmpty()) }
    }

    @OriginalCase("SyncCoordinatorTests::Full sync prune skips a truncated stream the ratio floor would have pruned()")
    @Test fun reportedFourReceivedThreePreservesFavouriteAndRoomMessages() = runTest {
        seedDevice()
        val favourite = seedContact(0xAA, "Favourite", ContactFlags.FAVORITE.rawValue)
        assertTrue(assertNotNull(store.fetchContact(EntityKey(radio, favourite))).isFavorite)
        seedMessage(favourite, "keep me")
        listOf(0x11, 0x22, 0x33).forEach { seedContact(it, "Other$it") }
        val session = ContactStream(listOf(0x11, 0x22, 0x33).map { meshContact(it, "Other$it") }, reportedTotal = 4)

        service(session).syncContacts(radio, null)

        assertEquals(listOf<Instant?>(null), session.invocations)
        assertContactKeys(0xAA, 0x11, 0x22, 0x33)
        assertEquals(listOf("keep me"), messages(favourite))
    }

    @OriginalCase("SyncCoordinatorTests::Full sync prune skips when the device sent no contact total()")
    @Test fun missingTotalPreservesUnseenContactAndMessages() = runTest {
        seedDevice()
        val orphan = seedContact(0x99, "Orphan")
        seedMessage(orphan, "keep unknown completeness")
        seedContact(0x11, "Real0")
        seedContact(0x22, "Real1")
        val session = ContactStream(listOf(meshContact(0x11, "Real0"), meshContact(0x22, "Real1")), reportedTotal = null)

        service(session).syncContacts(radio, null)

        assertContactKeys(0x11, 0x22, 0x99)
        assertEquals(listOf("keep unknown completeness"), messages(orphan))
    }

    @OriginalCase("SyncCoordinatorTests::Full sync prune skips when the self public key is the wrong length()")
    @Test fun malformedEightByteSelfKeyPreservesUnseenContactAndMessages() = runTest {
        seedDevice(selfKey = Bytes(ByteArray(8) { 1 }))
        val orphan = seedContact(0x99, "Orphan")
        seedMessage(orphan, "keep invalid identity")
        seedContact(0x11, "Real0")
        seedContact(0x22, "Real1")
        val session = ContactStream(listOf(meshContact(0x11, "Real0"), meshContact(0x22, "Real1")))

        service(session).syncContacts(radio, null)

        assertContactKeys(0x11, 0x22, 0x99)
        assertEquals(listOf("keep invalid identity"), messages(orphan))
    }

    @OriginalCase("SyncCoordinatorTests::V-contact in the last slot keeps the connect incremental, not at capacity()")
    @Test fun virtualThirdSlotDoesNotForceDestructiveFullSync() = runTest {
        seedDevice(maxContacts = 3u)
        val vKey = assertNotNull(VContactIdentity.publicKey(key(1)))
        val vContact = store.saveContact(radio, frame(vKey, "V-Contact")).id
        seedContact(0x11, "Real0")
        seedContact(0x22, "Real1")
        seedMessage(vContact, "keep virtual")
        val session = ContactStream(listOf(meshContact(0x11, "Real0"), meshContact(0x22, "Real1")))

        connectSync(session)

        assertNotNull(session.invocations.single(), "Source predicate: virtual slot must not force since=null")
        assertEquals(setOf(vKey, key(0x11), key(0x22)), store.fetchContacts(radio).map { it.publicKey }.toSet())
        assertEquals(listOf("keep virtual"), messages(vContact))
    }

    @OriginalCase("SyncCoordinatorTests::advert invalid watermark recovery is prune-free and keeps local-only contacts()")
    @Test fun invalidWatermarkRecoveryKeepsLocalOnlyContactAndMessages() = runTest {
        seedDevice(lastSync = EPOCH.epochSecond.toUInt() + 30u * 24u * 60u * 60u)
        seedContact(0x21, "Kept")
        val orphan = seedContact(0xA9, "Orphan")
        seedMessage(orphan, "keep recovery history")
        val session = ContactStream(listOf(meshContact(0x21, "Kept")))

        assertEquals(SyncAdvertContactSyncOutcome.SYNCED, coordinator().performAdvertContactSync(false, radio, store, service(session)))

        assertEquals(listOf<Instant?>(Instant.EPOCH), session.invocations)
        assertContactKeys(0x21, 0xA9)
        assertEquals(listOf("keep recovery history"), messages(orphan))
        assertEquals(EPOCH.epochSecond.toUInt() + 10u, store.fetchDevice(radio)?.lastContactSync)
    }

    @OriginalCase("SyncCoordinatorTests::performAdvertContactSync fullRefetch uses epoch zero and skips pruning()")
    @Test fun explicitFullRefetchKeepsOrphanUntilActualNullSinceControl() = runTest {
        seedDevice()
        seedContact(0x11, "Kept")
        val orphan = seedContact(0x99, "Orphan")
        seedMessage(orphan, "keep prune-free history")
        val session = ContactStream(listOf(meshContact(0x11, "Kept")))
        val contacts = service(session)

        assertEquals(SyncAdvertContactSyncOutcome.SYNCED, coordinator().performAdvertContactSync(true, radio, store, contacts))

        assertEquals(listOf<Instant?>(Instant.EPOCH), session.invocations)
        assertContactKeys(0x11, 0x99)
        assertEquals(listOf("keep prune-free history"), messages(orphan))

        contacts.syncContacts(radio, null)

        assertEquals(listOf(Instant.EPOCH, null), session.invocations)
        assertContactKeys(0x11)
        assertTrue(messages(orphan).isEmpty(), "Control proves the same real store would cascade on an actual pruning fetch")
    }

    @Test fun failedFetchKeepsTypedCauseAndCannotPruneOrAdvanceWatermark() = runTest {
        seedDevice()
        val orphan = seedContact(0x99, "Orphan")
        seedMessage(orphan, "keep failed fetch")
        val failure = MeshCoreException.NotConnected()
        val session = ContactStream(emptyList()).apply { fetchFailure = failure }
        val contacts = service(session)

        assertSame(failure, assertFailsWith<ContactServiceError.SessionError> { contacts.syncContacts(radio, null) }.error)

        assertContactKeys(0x99)
        assertEquals(listOf("keep failed fetch"), messages(orphan))
        assertEquals(EPOCH.epochSecond.toUInt(), store.fetchDevice(radio)?.lastContactSync)
    }

    @Test fun cancelledAdvertFetchCannotPruneAndReleasesClaimForPruneFreeRetry() = runTest {
        seedDevice()
        val orphan = seedContact(0x99, "Orphan")
        seedMessage(orphan, "keep cancelled fetch")
        val session = ContactStream(listOf(meshContact(0x11, "Kept")), hold = true)
        val contacts = service(session)
        val coordinator = coordinator()
        val fetch = async { coordinator.performAdvertContactSync(true, radio, store, contacts) }
        try {
            session.started.await()
            fetch.cancelAndJoin()
            assertTrue(fetch.isCancelled)
            assertContactKeys(0x99)
            assertEquals(listOf("keep cancelled fetch"), messages(orphan))
            assertEquals(EPOCH.epochSecond.toUInt(), store.fetchDevice(radio)?.lastContactSync)

            session.release.complete(Unit)
            assertEquals(SyncAdvertContactSyncOutcome.SYNCED, coordinator.performAdvertContactSync(true, radio, store, contacts))
            assertEquals(listOf<Instant?>(Instant.EPOCH, Instant.EPOCH), session.invocations)
            assertContactKeys(0x11, 0x99)
            assertEquals(listOf("keep cancelled fetch"), messages(orphan))
        } finally {
            session.release.complete(Unit)
            fetch.cancelAndJoin()
        }
    }

    private suspend fun seedDevice(maxContacts: UShort = 4u, lastSync: UInt = EPOCH.epochSecond.toUInt(), selfKey: Bytes = key(1)) {
        store.saveDevice(DeviceDTO(
            id = radio.value, radioId = radio, publicKey = selfKey, nodeName = "TestDevice",
            maxContacts = maxContacts, maxChannels = 8u, lastContactSync = lastSync,
        ))
    }

    private fun frame(publicKey: Bytes, name: String, flags: UByte = 0u) = ContactFrame(
        publicKey, ContactType.CHAT, flags, 0u, Bytes.EMPTY, name, 1_700_000_000u, 0.0, 0.0, 1_700_000_100u,
    )

    private suspend fun seedContact(byte: Int, name: String, flags: UByte = 0u): UUID =
        store.saveContact(radio, frame(key(byte), name, flags)).id

    private suspend fun seedMessage(contact: UUID, text: String) {
        store.saveMessage(MessageDTO(radioId = radio, contactID = contact, text = text, timestamp = 1_700_000_000u))
    }

    private suspend fun messages(contact: UUID): List<String> =
        store.fetchMessages(EntityKey(radio, contact), 10, 0).map { it.text }

    private suspend fun assertContactKeys(vararg bytes: Int) =
        assertEquals(bytes.map(::key).toSet(), store.fetchContacts(radio).map { it.publicKey }.toSet())

    private fun meshContact(byte: Int, name: String): MeshContact {
        val publicKey = key(byte)
        return MeshContact(
            publicKey.hexString, publicKey, ContactType.CHAT, ContactFlags(0u), 0u, Bytes.EMPTY, name,
            Instant.ofEpochSecond(1_700_000_000), 0.0, 0.0, EPOCH.plusSeconds(10),
        )
    }

    private fun service(session: ContactStream) =
        ContactService(session, store, store, null, null, MemoryFlags()).also { services += it }

    private fun TestScope.coordinator() = SyncCoordinator(backgroundScope, object : SyncClock {
        override fun now(): Instant = EPOCH.plusMillis(testScheduler.currentTime)
        override fun elapsed(): Duration = testScheduler.currentTime.milliseconds
        override suspend fun sleep(duration: Duration) = delay(duration)
    })

    private suspend fun TestScope.connectSync(session: ContactStream) {
        val coordinator = coordinator()
        val polling = CountingPoll()
        val channels = object : ChannelServiceProtocol {
            override suspend fun syncChannels(radioId: RadioId, maxChannels: UByte, usePipelinedRead: Boolean): Nothing = unexpected()
            override suspend fun retryFailedChannels(radioId: RadioId, indices: SnapshotList<UByte>): Nothing = unexpected()
        }
        val background = object : AppStateProvider { override suspend fun isInForeground(): Boolean = false }
        val result = coordinator.performFullSync(radio, store, service(session), channels, polling, background)
        assertTrue(result.isConnectionUsable)
        assertEquals(SyncPhaseStatus.Clean, result.contacts)
        assertEquals(SyncPhaseStatus.Skipped, result.channels, "Unrelated background channel phase is intentionally skipped")
        assertEquals(SyncState.Synced, coordinator.state)
        assertEquals(1, polling.polls)
    }

    private class ContactStream(
        private val contacts: List<MeshContact>,
        private val reportedTotal: Long? = contacts.size.toLong(),
        private val hold: Boolean = false,
    ) : ContactSessionOps {
        val invocations = mutableListOf<Instant?>()
        var fetchFailure: MeshCoreException? = null
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        override suspend fun getContactsReportingTotal(since: Instant?): ContactFetchResult {
            invocations += since
            started.complete(Unit)
            if (hold) release.await()
            fetchFailure?.let { throw it }
            return ContactFetchResult(contacts, reportedTotal)
        }
        override suspend fun getContacts(since: Instant?): List<MeshContact> = getContactsReportingTotal(since).contacts
        override suspend fun getContact(publicKey: Bytes): Nothing = unexpected()
        override suspend fun addContact(contact: MeshContact): Nothing = unexpected()
        override suspend fun removeContact(publicKey: Bytes): Nothing = unexpected()
        override suspend fun resetPath(publicKey: Bytes): Nothing = unexpected()
        override suspend fun sendPathDiscovery(destination: Bytes): Nothing = unexpected()
        override suspend fun shareContact(publicKey: Bytes): Nothing = unexpected()
        override suspend fun exportContact(publicKey: Bytes?): Nothing = unexpected()
        override suspend fun importContact(cardData: Bytes): Nothing = unexpected()
        override suspend fun changeContactFlags(contact: MeshContact, flags: ContactFlags): Nothing = unexpected()
    }

    private class CountingPoll : MessagePollingServiceProtocol {
        var polls = 0
        override suspend fun pollAllMessages(): Long { polls += 1; return 0 }
        override suspend fun waitForPendingHandlers(timeout: java.time.Duration): Nothing = unexpected()
        override suspend fun startAutoFetch(radioId: RadioId): Nothing = unexpected()
        override suspend fun pauseAutoFetch(): Nothing = unexpected()
        override suspend fun resumeAutoFetch(): Nothing = unexpected()
        override suspend fun setContactMessageHandler(handler: suspend (ContactMessage, ContactDTO?, DeliveryContext) -> Unit): Nothing = unexpected()
        override suspend fun setChannelMessageHandler(handler: suspend (ChannelMessage, ChannelDTO?, DeliveryContext) -> Unit): Nothing = unexpected()
        override suspend fun setSignedMessageHandler(handler: suspend (ContactMessage, ContactDTO?) -> Unit): Nothing = unexpected()
        override suspend fun setCLIMessageHandler(handler: suspend (ContactMessage, ContactDTO?) -> Unit): Nothing = unexpected()
        override suspend fun clearMessageHandlers(): Nothing = unexpected()
    }

    private companion object {
        fun unexpected(): Nothing = throw AssertionError("Unexpected unrelated service operation")
    }
}
