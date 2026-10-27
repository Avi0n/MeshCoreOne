// PortedFrom: MC1Services/Tests/MC1ServicesTests/SyncCoordinatorTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.sync

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreProtocol
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.ContactFrame
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.VContactIdentity
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactFlags
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.core.protocol.model.MeshContact
import com.meshcoreone.android.core.services.contacts.ContactService
import com.meshcoreone.android.core.services.contacts.ContactsFakePreferences
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

internal fun key(byte: Int): Bytes = Bytes(ByteArray(32) { byte.toByte() })

internal fun contactFrame(key: Bytes, name: String, flags: UByte = 0u, lastModified: UInt = 1_700_000_100u) =
    ContactFrame(key, ContactType.CHAT, flags, 0u, Bytes.EMPTY, name, 1_700_000_000u, 0.0, 0.0, lastModified)

internal fun meshContact(key: Bytes, name: String, lastModified: Instant = Instant.ofEpochSecond(1_800_000_000)) = MeshContact(
    key.hexString, key, ContactType.CHAT, ContactFlags(0u), 0u, Bytes.EMPTY, name, Instant.ofEpochSecond(1_700_000_000),
    0.0, 0.0, lastModified,
)

private fun pruneContactServiceFactory(session: FakeContactSession, store: PersistenceStoreProtocol) =
    ContactService(session, store, store, null, null, ContactsFakePreferences())

/** Original SyncCoordinatorTests: advert delta sync, watermarks, claims, capacity and prune cases. */
class SyncCoordinatorContactTests {
    private fun case(name: String, body: suspend SyncTestScope.() -> Unit) = syncCase(SYNC_SUITE, name, body)
    private fun phoneNow(scope: SyncTestScope): UInt = scope.clock.now().uint32Seconds()

    @TestFactory
    fun advertDeltaSync(): List<DynamicTest> = listOf(
        case("performAdvertContactSync writes watermark and uses since filter") {
            val coordinator = coordinator()
            val radioId = newRadio()
            val watermark = 1_704_067_200u
            val store = SyncInMemoryStore.createTestDataStore(radioId, lastContactSync = watermark)
            val newWatermark = phoneNow(this) + 60u
            val contacts = MockContactService().apply { stubbedSyncContactsResult = contactResult(3, newWatermark) }
            assertEquals(SyncAdvertContactSyncOutcome.SYNCED, coordinator.performAdvertContactSync(false, radioId, store, contacts))
            assertEquals(1, contacts.syncContactsInvocations.size)
            assertEquals(Instant.ofEpochSecond(watermark.toLong() - 1), contacts.syncContactsInvocations[0].since)
            assertEquals(newWatermark, store.device(radioId)?.lastContactSync)
        },
        case("performAdvertContactSync fullRefetch uses epoch zero and skips pruning") {
            val coordinator = coordinator()
            val radioId = newRadio()
            val store = SyncInMemoryStore.createTestDataStore(radioId, lastContactSync = 1_704_067_200u)
            val kept = key(0x11)
            val orphan = key(0x99)
            store.saveContact(radioId, contactFrame(kept, "Kept"))
            store.saveContact(radioId, contactFrame(orphan, "Orphan"))
            val session = FakeContactSession().apply { stubbedContacts = listOf(meshContact(kept, "Kept")) }
            val contactService = pruneContactServiceFactory(session, store)
            assertEquals(SyncAdvertContactSyncOutcome.SYNCED, coordinator.performAdvertContactSync(true, radioId, store, contactService))
            assertEquals(listOf<Instant?>(Instant.EPOCH), session.getContactsInvocations.toList())
            assertNotNull(store.fetchContact(radioId, orphan), "Prune-free: local-only orphan survives epoch-0 refetch")
            // Control: since == null prunes the orphan.
            contactService.syncContacts(radioId, null)
            assertNull(store.fetchContact(radioId, orphan))
            assertNotNull(store.fetchContact(radioId, kept))
        },
        case("performAdvertContactSync returns busy when manual contact sync is active") {
            val coordinator = coordinator()
            val radioId = newRadio()
            val store = SyncInMemoryStore.createTestDataStore(radioId, lastContactSync = 1_704_067_200u)
            val contacts = MockContactService().apply { stubbedSyncContactsResult = contactResult(1, phoneNow(this@case)) }
            coordinator.setManualContactSyncActive(true)
            assertEquals(SyncAdvertContactSyncOutcome.BUSY, coordinator.performAdvertContactSync(false, radioId, store, contacts))
            assertTrue(contacts.syncContactsInvocations.isEmpty(), "manual refresh must block advert delta from reaching the radio")
            coordinator.setManualContactSyncActive(false)
            assertEquals(SyncAdvertContactSyncOutcome.SYNCED, coordinator.performAdvertContactSync(false, radioId, store, contacts))
            assertEquals(1, contacts.syncContactsInvocations.size)
        },
        case("far-future watermark recovers with one full refetch then incremental") {
            val coordinator = coordinator()
            val radioId = newRadio()
            val now = phoneNow(this)
            val farFuture = now + 30u * 24u * 60u * 60u
            val store = SyncInMemoryStore.createTestDataStore(radioId, lastContactSync = farFuture)
            val recovered = now + 30u
            val contacts = MockContactService().apply { stubbedSyncContactsResult = contactResult(4, recovered) }
            assertEquals(SyncAdvertContactSyncOutcome.SYNCED, coordinator.performAdvertContactSync(false, radioId, store, contacts))
            assertEquals(Instant.EPOCH, contacts.syncContactsInvocations.single().since, "advert invalid recovery must use prune-free epoch-0, not since=nil")
            assertEquals(recovered, store.device(radioId)?.lastContactSync)

            val next = recovered + 10u
            contacts.reset()
            contacts.stubbedSyncContactsResult = contactResult(1, next)
            assertEquals(SyncAdvertContactSyncOutcome.SYNCED, coordinator.performAdvertContactSync(false, radioId, store, contacts))
            assertEquals(
                Instant.ofEpochSecond(recovered.toLong() - 1), contacts.syncContactsInvocations.single().since,
                "after recovery the next round must be incremental, not another full fetch",
            )
            assertEquals(next, store.device(radioId)?.lastContactSync)
        },
        case("far-future watermark recovery retries after a failed recovery fetch") {
            val coordinator = coordinator()
            val radioId = newRadio()
            val now = phoneNow(this)
            val store = SyncInMemoryStore.createTestDataStore(radioId, lastContactSync = now + 30u * 24u * 60u * 60u)
            val contacts = MockContactService().apply { stubbedSyncContactsResult = Result.failure(SyncCoordinatorError.SyncFailed("boom")) }
            assertEquals(SyncAdvertContactSyncOutcome.FAILED, coordinator.performAdvertContactSync(false, radioId, store, contacts))
            assertEquals(Instant.EPOCH, contacts.syncContactsInvocations.single().since, "failed round must still attempt prune-free epoch-0 recovery")

            val recovered = now + 30u
            contacts.reset()
            contacts.stubbedSyncContactsResult = contactResult(2, recovered)
            assertEquals(SyncAdvertContactSyncOutcome.SYNCED, coordinator.performAdvertContactSync(false, radioId, store, contacts))
            assertEquals(Instant.EPOCH, contacts.syncContactsInvocations.single().since, "a failed recovery must not spend the one-shot")
            assertEquals(recovered, store.device(radioId)?.lastContactSync)
        },
        case("advert invalid watermark recovery is prune-free and keeps local-only contacts") {
            val coordinator = coordinator()
            val radioId = newRadio()
            val now = phoneNow(this)
            val store = SyncInMemoryStore.createTestDataStore(radioId, lastContactSync = now + 30u * 24u * 60u * 60u)
            val kept = key(0x21)
            val orphan = key(0xA9)
            store.saveContact(radioId, contactFrame(kept, "Kept", lastModified = now))
            store.saveContact(radioId, contactFrame(orphan, "Orphan", lastModified = now))
            val session = FakeContactSession().apply {
                stubbedContacts = listOf(meshContact(kept, "Kept", Instant.ofEpochSecond(now.toLong() + 10)))
            }
            val contactService = pruneContactServiceFactory(session, store)
            assertEquals(SyncAdvertContactSyncOutcome.SYNCED, coordinator.performAdvertContactSync(false, radioId, store, contactService))
            assertEquals(listOf<Instant?>(Instant.EPOCH), session.getContactsInvocations.toList(), "invalid advert recovery must full-fetch with prune-free epoch-0")
            assertNotNull(store.fetchContact(radioId, orphan), "local-only contact must survive advert invalid-watermark recovery")
            assertNotNull(store.fetchContact(radioId, kept))
        },
        case("advert invalid watermark recovery full-fetches at most once per coordinator") {
            val coordinator = coordinator()
            val radioId = newRadio()
            val farFuture = phoneNow(this) + 30u * 24u * 60u * 60u
            val store = SyncInMemoryStore.createTestDataStore(radioId, lastContactSync = farFuture)
            val contacts = MockContactService().apply { stubbedSyncContactsResult = contactResult(10, farFuture) }
            assertEquals(SyncAdvertContactSyncOutcome.SYNCED, coordinator.performAdvertContactSync(false, radioId, store, contacts))
            assertEquals(Instant.EPOCH, contacts.syncContactsInvocations.single().since, "first round recovers with full fetch")
            contacts.reset()
            contacts.stubbedSyncContactsResult = contactResult(0, farFuture)
            assertEquals(SyncAdvertContactSyncOutcome.SYNCED, coordinator.performAdvertContactSync(false, radioId, store, contacts))
            val since = contacts.syncContactsInvocations.single().since
            assertEquals(Instant.ofEpochSecond(farFuture.toLong() - 1), since, "second round must use the stored stamp")
            assertNotEquals(Instant.EPOCH, since)
        },
        case("watermark a few minutes ahead of phone stays incremental") {
            val coordinator = coordinator()
            val radioId = newRadio()
            val radioLead = phoneNow(this) + 5u * 60u
            val store = SyncInMemoryStore.createTestDataStore(radioId, lastContactSync = radioLead)
            val newWatermark = radioLead + 15u
            val contacts = MockContactService().apply { stubbedSyncContactsResult = contactResult(1, newWatermark) }
            assertEquals(SyncAdvertContactSyncOutcome.SYNCED, coordinator.performAdvertContactSync(false, radioId, store, contacts))
            assertEquals(Instant.ofEpochSecond(radioLead.toLong() - 1), contacts.syncContactsInvocations.single().since)
            assertEquals(newWatermark, store.device(radioId)?.lastContactSync)
        },
        pureCase(SYNC_SUITE, "contactWatermarkUse marks far-future invalid and minute lead incremental") {
            val reference = Instant.ofEpochSecond(1_800_000_000)
            val refSeconds = 1_800_000_000u
            val minuteLead = refSeconds + 5u * 60u
            val farFuture = refSeconds + 30u * 24u * 60u * 60u
            assertEquals(ContactWatermarkUse.None, contactWatermarkUse(null, reference))
            assertEquals(ContactWatermarkUse.None, contactWatermarkUse(0u, reference))
            assertEquals(ContactWatermarkUse.Incremental(minuteLead), contactWatermarkUse(minuteLead, reference))
            assertEquals(ContactWatermarkUse.Invalid(farFuture), contactWatermarkUse(farFuture, reference))
        },
        syncParameterizedCase(
            SYNC_SUITE, "performAdvertContactSync with zero watermark does not sync", "(fullRefetch : Bool)", listOf(false, true),
        ) { fullRefetch ->
            val coordinator = coordinator()
            val radioId = newRadio()
            val store = SyncInMemoryStore.createTestDataStore(radioId, lastContactSync = 0u)
            val contacts = MockContactService().apply { stubbedSyncContactsResult = contactResult(2, 1_800_000_000u) }
            assertEquals(SyncAdvertContactSyncOutcome.NOT_READY, coordinator.performAdvertContactSync(fullRefetch, radioId, store, contacts))
            assertTrue(contacts.syncContactsInvocations.isEmpty(), "No advert sync may run before the first pruning full sync succeeds")
            assertEquals(0u, store.device(radioId)?.lastContactSync, "Writing a watermark here would suppress the pruning full sync forever")
        },
        case("Advert sync runs after a full sync that found no contacts") {
            val coordinator = coordinator()
            val radioId = newRadio()
            val store = SyncInMemoryStore.createTestDataStore(radioId, lastContactSync = 0u)
            val contacts = MockContactService().apply { stubbedSyncContactsResult = contactResult(0, 0u, incremental = false) }
            coordinator.performFullSync(radioId, store, contacts, MockChannelService(), MockMessagePollingService())
            assertEquals(0u, store.device(radioId)?.lastContactSync)
            assertEquals(SyncAdvertContactSyncOutcome.SYNCED, coordinator.performAdvertContactSync(false, radioId, store, contacts))
            assertEquals(2, contacts.syncContactsInvocations.size)
            assertEquals(Instant.EPOCH, contacts.syncContactsInvocations[1].since, "With no watermark the delta round fetches prune-free from epoch zero")
        },
        case("Full sync still prunes after an advert sync attempt with no watermark") {
            val coordinator = coordinator()
            val radioId = newRadio()
            val store = SyncInMemoryStore.createTestDataStore(radioId, lastContactSync = 0u)
            val contacts = MockContactService().apply { stubbedSyncContactsResult = contactResult(2, 1_800_000_000u) }
            coordinator.performAdvertContactSync(false, radioId, store, contacts)
            coordinator.performFullSync(radioId, store, contacts, MockChannelService(), MockMessagePollingService())
            assertEquals(1, contacts.syncContactsInvocations.size)
            assertNull(contacts.syncContactsInvocations[0].since, "The first full sync after a failed connect sync must still prune")
        },
        case("performAdvertContactSync returns false while sync claimed") {
            val coordinator = coordinator()
            val radioId = newRadio()
            val store = SyncInMemoryStore.createTestDataStore(radioId, lastContactSync = 1_704_067_200u)
            val delaying = DelayingContactService()
            val started = CallTracker()
            coordinator.setSyncActivityCallbacks({ started.markCalled() }, {}, {})
            val first = task { coordinator.performFullSync(radioId, store, delaying, MockChannelService(), MockMessagePollingService()) }
            waitUntil("First sync should have started") { started.callCount >= 1 }
            assertEquals(
                SyncAdvertContactSyncOutcome.BUSY, coordinator.performAdvertContactSync(false, radioId, store, MockContactService()),
                "A collision with another sync is not a failed exchange",
            )
            delaying.completeSync()
            first.cancel()
        },
    )

    @TestFactory
    fun advertClaimWaits(): List<DynamicTest> = listOf(
        case("Channel retry waits for an active advert contact sync instead of skipping") {
            val coordinator = coordinator()
            val radioId = newRadio()
            val store = SyncInMemoryStore.createTestDataStore(radioId, lastContactSync = 1_704_067_200u)
            val gated = GatedContactService()
            val channels = MockChannelService()
            val indices = listOf<UByte>(3u, 5u)
            val advert = task { coordinator.performAdvertContactSync(false, radioId, store, gated) }
            gated.waitForSyncStart()
            val finished = CallTracker()
            val retry = task { coordinator.retryChannels(radioId, channels, indices).also { finished.markCalled() } }
            sleep(200.milliseconds)
            assertFalse(finished.wasCalled, "Channel retry must wait while an advert contact sync holds the claim")
            assertTrue(channels.retryInvocations.isEmpty())
            gated.release()
            assertEquals(SyncAdvertContactSyncOutcome.SYNCED, advert.await(), "Advert contact sync should complete once released")
            assertTrue(retry.await().errors.isEmpty(), "Channel retry must run for real after the advert sync releases")
            assertEquals(listOf(indices), channels.retryInvocations.map { it.indices })
        },
        case("claimManualContactSync is atomic with the wait so advert cannot claim in the gap") {
            val coordinator = coordinator()
            val radioId = newRadio()
            val store = SyncInMemoryStore.createTestDataStore(radioId, lastContactSync = 1_704_067_200u)
            val gated = GatedContactService()
            val advert = task { coordinator.performAdvertContactSync(false, radioId, store, gated) }
            gated.waitForSyncStart()
            val claim = task { coordinator.claimManualContactSync() }
            sleep(100.milliseconds)
            gated.release()
            assertEquals(SyncAdvertContactSyncOutcome.SYNCED, advert.await())
            claim.await()
            assertEquals(
                SyncAdvertContactSyncOutcome.BUSY, coordinator.performAdvertContactSync(false, radioId, store, MockContactService()),
                "After claimManualContactSync returns, manual flag must already be set",
            )
            coordinator.setManualContactSyncActive(false)
        },
        case("waitForAdvertContactSync throws when cancelled") {
            val coordinator = coordinator()
            val radioId = newRadio()
            val store = SyncInMemoryStore.createTestDataStore(radioId, lastContactSync = 1_704_067_200u)
            val gated = GatedContactService()
            val advert = task { coordinator.performAdvertContactSync(false, radioId, store, gated) }
            gated.waitForSyncStart()
            val wait = task { coordinator.waitForAdvertContactSync() }
            sleep(50.milliseconds)
            wait.cancel()
            assertFailsWith<CancellationException>("Cancelled wait must throw CancellationException, not hang") { wait.await() }
            assertEquals(0, coordinator.advertSyncWaiterCount, "A cancelled waiter must be unregistered")
            gated.release()
            advert.await()
        },
        case("waitForAdvertContactSync throws when the bound is reached") {
            val coordinator = coordinator()
            val radioId = newRadio()
            val store = SyncInMemoryStore.createTestDataStore(radioId, lastContactSync = 1_704_067_200u)
            val gated = GatedContactService()
            val advert = task { coordinator.performAdvertContactSync(false, radioId, store, gated) }
            gated.waitForSyncStart()
            val error = assertFailsWith<SyncCoordinatorError.SyncFailed> { coordinator.waitForAdvertContactSync(40.milliseconds) }
            assertEquals(ADVERT_CONTACT_SYNC_WAIT_TIMED_OUT_MESSAGE, error.reason, "Wait must surface a timeout error rather than hang or silent-skip")
            gated.release()
            advert.await()
        },
        case("onDisconnected resumes advert claim waiters") {
            val coordinator = coordinator()
            val radioId = newRadio()
            val store = SyncInMemoryStore.createTestDataStore(radioId, lastContactSync = 1_704_067_200u)
            val gated = GatedContactService()
            val advert = task { coordinator.performAdvertContactSync(false, radioId, store, gated) }
            gated.waitForSyncStart()
            val wait = task { coordinator.waitForAdvertContactSync(5.seconds) }
            sleep(50.milliseconds)
            coordinator.onDisconnected(FakeNotificationService())
            // The waiter resumes when the connection drops, not when the advert round ends.
            wait.await()
            assertTrue(advert.isActive, "advert round is still parked")
            gated.release()
            advert.await()
        },
    )

    @TestFactory
    fun capacityAndPrune(): List<DynamicTest> = listOf(
        case("At-capacity connect forces full contact sync and prunes the missing non-favourite") {
            val coordinator = coordinator()
            val radioId = newRadio()
            val store = SyncInMemoryStore.createTestDataStore(radioId, maxContacts = 3u, lastContactSync = 1_704_067_200u)
            val keptA = key(0x11)
            val keptB = key(0x22)
            val evicted = key(0x99)
            listOf(keptA to "KeptA", keptB to "KeptB", evicted to "Evicted").forEach { (k, n) -> store.saveContact(radioId, contactFrame(k, n)) }
            val session = FakeContactSession().apply {
                stubbedContacts = listOf(meshContact(keptA, "KeptA"), meshContact(keptB, "KeptB"), meshContact(key(0x33), "Newcomer"))
            }
            coordinator.performFullSync(radioId, store, pruneContactServiceFactory(session, store), MockChannelService(), MockMessagePollingService())
            assertEquals(listOf<Instant?>(null), session.getContactsInvocations.toList(), "At-capacity connect must force since == nil")
            assertNull(store.fetchContact(radioId, evicted))
            assertNotNull(store.fetchContact(radioId, keptA))
            assertNotNull(store.fetchContact(radioId, keptB))
        },
        case("Below-capacity connect keeps incremental contact sync and does not prune") {
            val coordinator = coordinator()
            val radioId = newRadio()
            val watermark = 1_704_067_200u
            val store = SyncInMemoryStore.createTestDataStore(radioId, maxContacts = 100u, lastContactSync = watermark)
            val kept = key(0x11)
            val orphan = key(0x99)
            store.saveContact(radioId, contactFrame(kept, "Kept"))
            store.saveContact(radioId, contactFrame(orphan, "Orphan"))
            val session = FakeContactSession().apply { stubbedContacts = listOf(meshContact(kept, "Kept")) }
            coordinator.performFullSync(radioId, store, pruneContactServiceFactory(session, store), MockChannelService(), MockMessagePollingService())
            assertEquals(listOf<Instant?>(Instant.ofEpochSecond(watermark.toLong() - 1)), session.getContactsInvocations.toList())
            assertNotNull(store.fetchContact(radioId, orphan))
        },
        case("maxContacts zero does not force capacity full contact sync") {
            val coordinator = coordinator()
            val radioId = newRadio()
            val store = SyncInMemoryStore.createTestDataStore(radioId, maxContacts = 0u, lastContactSync = 1_704_067_200u)
            store.saveContact(radioId, contactFrame(key(0x11), "Any"))
            val contacts = MockContactService()
            coordinator.performFullSync(radioId, store, contacts, MockChannelService(), MockMessagePollingService())
            assertNotNull(contacts.syncContactsInvocations.single().since, "maxContacts == 0 must not force since == nil")
        },
        case("Contact count read failure falls back to incremental and keeps connection usable") {
            val coordinator = coordinator()
            val radioId = newRadio()
            val store = SyncInMemoryStore.createTestDataStore(radioId, maxContacts = 3u, lastContactSync = 1_704_067_200u)
            for (byte in listOf(0x11, 0x22, 0x33)) {
                store.saveContact(ContactDTO(radioId = radioId, publicKey = key(byte), name = "C$byte", lastHeardTimestamp = null))
            }
            store.failures["fetchContactPublicKeys"] = IllegalStateException("count read failed")
            val contacts = MockContactService()
            val result = coordinator.performFullSync(radioId, store, contacts, MockChannelService(), MockMessagePollingService())
            assertTrue(result.isConnectionUsable)
            assertNotNull(contacts.syncContactsInvocations.single().since, "Count-read failure must degrade to incremental")
        },
        case("Full sync prune skips a truncated stream the ratio floor would have pruned") {
            val radioId = newRadio()
            val store = SyncInMemoryStore.createTestDataStore(radioId, maxContacts = 4u, lastContactSync = 1_704_067_200u)
            val favouriteKey = key(0xAA)
            val others = listOf(key(0x11), key(0x22), key(0x33))
            val favouriteID = store.saveContact(radioId, contactFrame(favouriteKey, "Favourite", flags = ContactFlags.FAVORITE.rawValue)).id
            assertTrue(store.fetchContact(EntityKey(radioId, favouriteID))?.isFavorite == true)
            store.saveMessage(MessageDTO(radioId = radioId, contactID = favouriteID, text = "keep me", timestamp = 1_700_000_000u))
            others.forEachIndexed { index, k -> store.saveContact(radioId, contactFrame(k, "Other$index")) }
            val session = FakeContactSession().apply {
                stubbedContacts = others.mapIndexed { index, k -> meshContact(k, "Other$index") }
                stubbedReportedTotal = 4
            }
            pruneContactServiceFactory(session, store).syncContacts(radioId, null)
            assertNotNull(store.fetchContact(radioId, favouriteKey))
            val messages = store.fetchMessages(EntityKey(radioId, favouriteID), 10, 0)
            assertEquals(listOf("keep me"), messages.map { it.text })
            others.forEach { assertNotNull(store.fetchContact(radioId, it)) }
        },
        case("Full sync prune removes a favourite absent from a complete device set") {
            val radioId = newRadio()
            val store = SyncInMemoryStore.createTestDataStore(radioId, maxContacts = 4u, lastContactSync = 1_704_067_200u)
            val favouriteKey = key(0xAA)
            val kept = listOf(key(0x11), key(0x22), key(0x33))
            store.saveContact(radioId, contactFrame(favouriteKey, "Favourite", flags = ContactFlags.FAVORITE.rawValue))
            kept.forEachIndexed { index, k -> store.saveContact(radioId, contactFrame(k, "Kept$index")) }
            val session = FakeContactSession().apply { stubbedContacts = kept.mapIndexed { index, k -> meshContact(k, "Kept$index") } }
            pruneContactServiceFactory(session, store).syncContacts(radioId, null)
            assertNull(store.fetchContact(radioId, favouriteKey))
            kept.forEach { assertNotNull(store.fetchContact(radioId, it)) }
        },
        case("Full sync prune removes orphans when the device genuinely shrank below half") {
            val radioId = newRadio()
            val store = SyncInMemoryStore.createTestDataStore(radioId, maxContacts = 4u, lastContactSync = 1_704_067_200u)
            val survivor = key(0x11)
            val stale = listOf(key(0xAA), key(0x22), key(0x33))
            store.saveContact(radioId, contactFrame(survivor, "Survivor"))
            stale.forEachIndexed { index, k -> store.saveContact(radioId, contactFrame(k, "Stale$index")) }
            val session = FakeContactSession().apply { stubbedContacts = listOf(meshContact(survivor, "Survivor")) }
            pruneContactServiceFactory(session, store).syncContacts(radioId, null)
            assertNotNull(store.fetchContact(radioId, survivor))
            stale.forEach { assertNull(store.fetchContact(radioId, it)) }
        },
        case("Full sync prune preserves the local V-contact omitted from a complete stream") {
            val radioId = newRadio()
            val store = SyncInMemoryStore.createTestDataStore(radioId, maxContacts = 4u, lastContactSync = 1_704_067_200u)
            val vContact = checkNotNull(VContactIdentity.publicKey(key(0x01)))
            val real = listOf(key(0x11), key(0x22))
            val orphan = key(0x99)
            store.saveContact(radioId, contactFrame(vContact, "V-Contact"))
            store.saveContact(radioId, contactFrame(orphan, "Orphan"))
            real.forEachIndexed { index, k -> store.saveContact(radioId, contactFrame(k, "Real$index")) }
            val session = FakeContactSession().apply { stubbedContacts = real.mapIndexed { index, k -> meshContact(k, "Real$index") } }
            pruneContactServiceFactory(session, store).syncContacts(radioId, null)
            assertNotNull(store.fetchContact(radioId, vContact))
            assertNull(store.fetchContact(radioId, orphan))
            real.forEach { assertNotNull(store.fetchContact(radioId, it)) }
        },
        case("V-contact in the last slot keeps the connect incremental, not at capacity") {
            val coordinator = coordinator()
            val radioId = newRadio()
            val store = SyncInMemoryStore.createTestDataStore(radioId, maxContacts = 3u, lastContactSync = 1_704_067_200u)
            val vContact = checkNotNull(VContactIdentity.publicKey(key(0x01)))
            val real = listOf(key(0x11), key(0x22))
            store.saveContact(radioId, contactFrame(vContact, "V-Contact"))
            real.forEachIndexed { index, k -> store.saveContact(radioId, contactFrame(k, "Real$index")) }
            val session = FakeContactSession().apply { stubbedContacts = real.mapIndexed { index, k -> meshContact(k, "Real$index") } }
            coordinator.performFullSync(radioId, store, pruneContactServiceFactory(session, store), MockChannelService(), MockMessagePollingService())
            assertNotNull(session.getContactsInvocations.single(), "V-contact in the last slot must not trip at-capacity")
        },
        case("Full sync prune skips when the device sent no contact total") {
            val radioId = newRadio()
            val store = SyncInMemoryStore.createTestDataStore(radioId, maxContacts = 4u, lastContactSync = 1_704_067_200u)
            val real = listOf(key(0x11), key(0x22))
            val orphan = key(0x99)
            store.saveContact(radioId, contactFrame(orphan, "Orphan"))
            real.forEachIndexed { index, k -> store.saveContact(radioId, contactFrame(k, "Real$index")) }
            val session = FakeContactSession().apply {
                stubbedContacts = real.mapIndexed { index, k -> meshContact(k, "Real$index") }
                stubbedReportsNoTotal = true
            }
            pruneContactServiceFactory(session, store).syncContacts(radioId, null)
            assertNotNull(store.fetchContact(radioId, orphan), "Prune must skip when the device reports no total")
            real.forEach { assertNotNull(store.fetchContact(radioId, it)) }
        },
        case("Full sync prune skips when the self public key is the wrong length") {
            val radioId = newRadio()
            val store = SyncInMemoryStore.createTestDataStore(radioId, maxContacts = 4u, lastContactSync = 1_704_067_200u)
            store.saveDevice(SyncInMemoryStore.testDevice(radioId, publicKey = Bytes(ByteArray(8) { 1 }), maxContacts = 4u, lastContactSync = 1_704_067_200u))
            val real = listOf(key(0x11), key(0x22))
            val orphan = key(0x99)
            store.saveContact(radioId, contactFrame(orphan, "Orphan"))
            real.forEachIndexed { index, k -> store.saveContact(radioId, contactFrame(k, "Real$index")) }
            val session = FakeContactSession().apply { stubbedContacts = real.mapIndexed { index, k -> meshContact(k, "Real$index") } }
            pruneContactServiceFactory(session, store).syncContacts(radioId, null)
            assertNotNull(store.fetchContact(radioId, orphan), "Prune must skip when the self key is malformed")
            real.forEach { assertNotNull(store.fetchContact(radioId, it)) }
        },
    )
}
