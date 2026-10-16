package one.meshcore.shared.contact

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * When a delta handler is installed, 0x80 keys (known and unknown) queue for GET_CONTACTS
 * instead of a per-key `getContact`. Mirrors iOS advert delta-sync scheduling.
 */
class ContactAdvertDeltaSyncTest {
    @Test
    fun unknownAdvertQueuesUntilTheDeltaRoundWhenHandlerIsInstalled() = runTest {
        val radioID = ContactTestIds.RADIO
        val store = InMemoryContactStore()
        val key = publicKey(0x41)
        val session = FakeContactSession().apply {
            stubbedContacts = listOf(meshContact(keyByte = 0x41, name = "FromDelta"))
            singleContactLookup = meshContact(keyByte = 0x41, name = "FromGetContact")
        }
        val service = testContactService(session = session, store = store)
        var handlerCalls = 0
        service.setDeltaSyncHandler { fullRefetch ->
            handlerCalls += 1
            assertFalse(fullRefetch)
            service.syncContacts(radioID, since = 1L)
            AdvertContactSyncOutcome.SYNCED
        }

        assertFalse(service.handleAdvertisement(radioID, key))
        assertTrue(session.getContactKeys.isEmpty())
        assertNull(store.fetchContact(radioID, key))

        service.runAdvertDeltaSync()
        assertEquals(1, handlerCalls)
        val saved = store.fetchContact(radioID, key)
        assertEquals("FromDelta", saved?.name)
        assertEquals(CONTACT_TEST_NOW_SECONDS, saved?.lastHeardTimestamp)
        assertTrue(session.getContactKeys.isEmpty())
    }

    @Test
    fun aSyncedDeltaRoundNotifiesContactsChanged() = runTest {
        val radioID = ContactTestIds.RADIO
        val store = InMemoryContactStore()
        val key = publicKey(0x61)
        val session = FakeContactSession().apply {
            stubbedContacts = listOf(meshContact(keyByte = 0x61, name = "Moved"))
        }
        val coordinator = RecordingContactSyncCoordinator()
        val service = testContactService(session = session, store = store, syncCoordinator = coordinator)
        service.setDeltaSyncHandler {
            service.syncContacts(radioID, since = 1L)
            AdvertContactSyncOutcome.SYNCED
        }

        service.handleAdvertisement(radioID, key)
        service.runAdvertDeltaSync()

        assertEquals(1, coordinator.contactsChanged)
    }

    @Test
    fun aSyncedDeltaRoundAppliesDiscoverAndNotifiesInsertedKeys() = runTest {
        val radioID = ContactTestIds.RADIO
        val store = InMemoryContactStore()
        val key = publicKey(0x63)
        val session = FakeContactSession().apply {
            stubbedContacts = listOf(meshContact(keyByte = 0x63, name = "FirstSeen"))
        }
        val notifier = RecordingContactDiscoveryNotifier()
        val service = testContactService(
            session = session,
            store = store,
            discoveryNotifier = notifier,
        )
        service.setDeltaSyncHandler {
            service.syncContacts(radioID, since = 1L)
            AdvertContactSyncOutcome.SYNCED
        }
        val events = service.events()

        service.handleAdvertisement(radioID, key)
        service.runAdvertDeltaSync()

        val applied = mutableListOf<ContactServiceEvent.ContactAdvertisementApplied>()
        while (true) {
            val event = events.tryReceive().getOrNull() ?: break
            if (event is ContactServiceEvent.ContactAdvertisementApplied) applied += event
        }
        assertEquals(1, applied.size)
        assertTrue(applied.single().publicKey.contentEquals(key))
        assertEquals(1, notifier.notified.size)
        assertEquals("FirstSeen", notifier.notified.single().contactName)
    }

    /**
     * iOS `reconcile` upserts Discover from the post-GET_CONTACTS contact even when
     * `stampAdvertReceiveTimes` cannot dual-write lastHeard. Kotlin only yielded
     * [ContactServiceEvent.ContactAdvertisementApplied] and left Discover to a host collector.
     */
    @Test
    fun aSyncedDeltaRoundUpsertsDiscoverEvenWhenLastHeardStampFails() = runTest {
        val radioID = ContactTestIds.RADIO
        val inner = InMemoryContactStore()
        val store = FlakyTouchContactStore(inner, throwOnFirstTouches = 1)
        val discovered = InMemoryDiscoveredNodeStore()
        val key = publicKey(0x64)
        val session = FakeContactSession().apply {
            stubbedContacts = listOf(
                meshContact(keyByte = 0x64, name = "Hilltop", latitude = 37.5, longitude = -122.3),
            )
        }
        val service = testContactService(
            session = session,
            store = store,
            discoveredNodes = discovered,
        )
        service.setDeltaSyncHandler {
            service.syncContacts(radioID, since = 1L)
            AdvertContactSyncOutcome.SYNCED
        }

        assertFalse(service.handleAdvertisement(radioID, key))
        service.runAdvertDeltaSync()

        val row = discovered.list(radioID).single()
        assertEquals("Hilltop", row.name)
        assertEquals(37.5, row.latitude)
        assertEquals(-122.3, row.longitude)
        assertTrue(row.publicKey.contentEquals(key))
    }

    @Test
    fun aBusyDeltaRoundDoesNotNotifyContactsChanged() = runTest {
        val radioID = ContactTestIds.RADIO
        val store = InMemoryContactStore()
        val key = publicKey(0x62)
        val coordinator = RecordingContactSyncCoordinator()
        val service = testContactService(store = store, syncCoordinator = coordinator)
        service.setDeltaSyncHandler { AdvertContactSyncOutcome.BUSY }

        service.handleAdvertisement(radioID, key)
        service.runAdvertDeltaSync()

        assertEquals(0, coordinator.contactsChanged)
    }

    @Test
    fun postDeltaLastHeardProtectsAStaleRadioLastModifiedFromPrune() = runTest {
        val radioID = ContactTestIds.RADIO
        val store = InMemoryContactStore()
        val key = publicKey(0x5C)
        val oldStamp = 1_000_000L
        val session = FakeContactSession().apply {
            stubbedContacts = listOf(
                meshContact(keyByte = 0x5C, name = "JustHeard", lastModified = oldStamp),
            )
        }
        val service = testContactService(session = session, store = store)
        service.setDeltaSyncHandler {
            service.syncContacts(radioID, since = 1L)
            AdvertContactSyncOutcome.SYNCED
        }

        assertFalse(service.handleAdvertisement(radioID, key))
        service.runAdvertDeltaSync()

        val contact = store.fetchContact(radioID, key)!!
        val cutoff = CONTACT_TEST_NOW_SECONDS - 30L * 86_400L
        assertEquals(oldStamp, contact.lastModified)
        assertTrue(contact.lastModified < cutoff)
        assertEquals(CONTACT_TEST_NOW_SECONDS, contact.lastHeardTimestamp)
        assertEquals(CONTACT_TEST_NOW_SECONDS, contact.recencyTimestamp)
        assertFalse(contact.matchesStaleNodePrune(cutoff))
    }

    @Test
    fun deltaRoundNotReadyDropsQueuedKeys() = runTest {
        val radioID = ContactTestIds.RADIO
        val store = InMemoryContactStore()
        val key = publicKey(0x42)
        val session = FakeContactSession()
        val service = testContactService(session = session, store = store)
        service.setDeltaSyncHandler { AdvertContactSyncOutcome.NOT_READY }

        assertFalse(service.handleAdvertisement(radioID, key))
        service.runAdvertDeltaSync()
        assertNull(store.fetchContact(radioID, key))
        service.runAdvertDeltaSync()
        assertTrue(session.getContactKeys.isEmpty())
    }

    @Test
    fun notReadyDropStampsLastHeardOnAnExistingContact() = runTest {
        val radioID = ContactTestIds.RADIO
        val store = InMemoryContactStore()
        val key = publicKey(0x5D)
        store.saveContact(radioID, contactFrame(0x5D, name = "Known"))
        store.saveContact(store.fetchContact(radioID, key)!!.copy(lastHeardTimestamp = null, lastModified = 1_000_000L))
        var foreground = false
        val service = testContactService(store = store, isInForeground = { foreground })
        service.setDeltaSyncHandler { AdvertContactSyncOutcome.NOT_READY }

        assertFalse(service.handleAdvertisement(radioID, key))
        assertNull(store.fetchContact(radioID, key)?.lastHeardTimestamp)

        foreground = true
        service.runAdvertDeltaSync()
        val contact = store.fetchContact(radioID, key)!!
        assertEquals(CONTACT_TEST_NOW_SECONDS, contact.lastHeardTimestamp)
        assertFalse(service.hasPendingAdvertKey(key))
        val cutoff = CONTACT_TEST_NOW_SECONDS - 30L * 86_400L
        assertFalse(contact.matchesStaleNodePrune(cutoff))
    }

    @Test
    fun failedCapDropStampsLastHeardOnAnExistingContact() = runTest {
        val radioID = ContactTestIds.RADIO
        val store = InMemoryContactStore()
        val key = publicKey(0x5E)
        store.saveContact(radioID, contactFrame(0x5E, name = "Known"))
        store.saveContact(store.fetchContact(radioID, key)!!.copy(lastHeardTimestamp = null, lastModified = 1_000_000L))
        var foreground = false
        val service = testContactService(store = store, isInForeground = { foreground })
        service.setDeltaSyncHandler { AdvertContactSyncOutcome.FAILED }

        assertFalse(service.handleAdvertisement(radioID, key))
        foreground = true
        repeat(5) { service.runAdvertDeltaSync() }

        val contact = store.fetchContact(radioID, key)!!
        assertEquals(CONTACT_TEST_NOW_SECONDS, contact.lastHeardTimestamp)
        assertFalse(service.hasPendingAdvertKey(key))
        val cutoff = CONTACT_TEST_NOW_SECONDS - 30L * 86_400L
        assertFalse(contact.matchesStaleNodePrune(cutoff))
    }

    @Test
    fun busyOutcomeRequeuesQueuedKeysForTheNextRound() = runTest {
        val radioID = ContactTestIds.RADIO
        val store = InMemoryContactStore()
        val key = publicKey(0x43)
        val session = FakeContactSession().apply {
            stubbedContacts = listOf(meshContact(keyByte = 0x43, name = "FromDelta"))
        }
        val service = testContactService(session = session, store = store)
        var handlerCalls = 0
        service.setDeltaSyncHandler {
            handlerCalls += 1
            if (handlerCalls == 1) {
                AdvertContactSyncOutcome.BUSY
            } else {
                service.syncContacts(radioID, since = 1L)
                AdvertContactSyncOutcome.SYNCED
            }
        }

        assertFalse(service.handleAdvertisement(radioID, key))
        service.runAdvertDeltaSync()
        assertEquals(1, handlerCalls)
        assertNull(store.fetchContact(radioID, key))

        service.runAdvertDeltaSync()
        assertEquals(2, handlerCalls)
        assertEquals("FromDelta", store.fetchContact(radioID, key)?.name)
    }

    @Test
    fun undeliveredPathUpdateEscalatesTheNextRoundToFullRefetch() = runTest {
        val radioID = ContactTestIds.RADIO
        val store = InMemoryContactStore()
        val key = publicKey(0x44)
        store.saveContact(
            radioID,
            contactFrame(0x44, name = "Path").copy(lastModified = 100L),
        )
        val session = FakeContactSession()
        val service = testContactService(session = session, store = store)
        val refetchFlags = mutableListOf<Boolean>()
        service.setDeltaSyncHandler { fullRefetch ->
            refetchFlags += fullRefetch
            AdvertContactSyncOutcome.SYNCED
        }

        assertFalse(service.refreshPathFromDevice(radioID, key))
        service.runAdvertDeltaSync()
        assertEquals(listOf(false), refetchFlags)
        assertEquals(100L, store.fetchContact(radioID, key)?.lastModified)

        service.runAdvertDeltaSync()
        assertEquals(listOf(false, true), refetchFlags)
    }

    @Test
    fun knownAdvertQueuesDeltaRoundWithoutAPerKeyFetch() = runTest {
        val radioID = ContactTestIds.RADIO
        val store = InMemoryContactStore()
        val key = publicKey(0x45)
        store.saveContact(
            radioID,
            contactFrame(0x45, name = "Known").copy(lastAdvertTimestamp = 1_000L, lastModified = 1_000L),
        )
        val session = FakeContactSession().apply {
            stubbedContacts = listOf(
                meshContact(keyByte = 0x45, name = "FromDelta", lastAdvertisement = 2_000L),
            )
            singleContactLookup = meshContact(keyByte = 0x45, name = "FromGetContact")
        }
        val service = testContactService(session = session, store = store)
        var handlerCalls = 0
        service.setDeltaSyncHandler { fullRefetch ->
            handlerCalls += 1
            assertFalse(fullRefetch)
            service.syncContacts(radioID, since = 1L)
            AdvertContactSyncOutcome.SYNCED
        }

        assertTrue(service.handleAdvertisement(radioID, key))
        assertTrue(session.getContactKeys.isEmpty())
        assertEquals(1_000L, store.fetchContact(radioID, key)?.lastAdvertTimestamp)
        assertEquals("Known", store.fetchContact(radioID, key)?.name)

        service.runAdvertDeltaSync()
        assertEquals(1, handlerCalls)
        assertTrue(session.getContactKeys.isEmpty())
        assertEquals("FromDelta", store.fetchContact(radioID, key)?.name)
        assertEquals(2_000L, store.fetchContact(radioID, key)?.lastAdvertTimestamp)
    }

    @Test
    fun successfulDeltaRoundAdoptsOrphanedDirectMessages() = runTest {
        val radioID = ContactTestIds.RADIO
        val store = InMemoryContactStore()
        val key = publicKey(0x46)
        val session = FakeContactSession().apply {
            stubbedContacts = listOf(meshContact(keyByte = 0x46, name = "FromDelta"))
        }
        val adopter = RecordingOrphanDmAdopter()
        val service = testContactService(session = session, store = store, orphanDmAdopter = adopter)
        service.setDeltaSyncHandler {
            service.syncContacts(radioID, since = 1L)
            AdvertContactSyncOutcome.SYNCED
        }

        assertFalse(service.handleAdvertisement(radioID, key))
        service.runAdvertDeltaSync()
        assertEquals(1, adopter.calls)
        assertEquals(1, adopter.lastContacts.size)
        assertTrue(adopter.lastContacts.single().publicKey.contentEquals(key))
    }

    @Test
    fun refreshHoldsTheAdvertClaimUntilGetContactsReturns() = runTest {
        val radioID = ContactTestIds.RADIO
        val claim = AdvertContactSyncClaim()
        val session = FakeContactSession().apply {
            stubbedContacts = listOf(meshContact(keyByte = 0x47, name = "N"))
            onGetContacts = {
                assertFalse(claim.tryBeginAdvert())
            }
        }
        val service = testContactService(session = session, advertSyncClaim = claim)
        service.syncContactsForRefresh(radioID)
        assertTrue(claim.tryBeginAdvert())
        claim.endAdvert()
    }

    @Test
    fun missingUnknownKeyAfterIncrementalEscalatesToFullRefetch() = runTest {
        val radioID = ContactTestIds.RADIO
        val store = InMemoryContactStore()
        val key = publicKey(0x48)
        val session = FakeContactSession()
        val service = testContactService(session = session, store = store)
        val refetchFlags = mutableListOf<Boolean>()
        service.setDeltaSyncHandler { fullRefetch ->
            refetchFlags += fullRefetch
            AdvertContactSyncOutcome.SYNCED
        }

        assertFalse(service.handleAdvertisement(radioID, key))
        service.runAdvertDeltaSync()
        assertEquals(listOf(false), refetchFlags)
        assertNull(store.fetchContact(radioID, key))

        service.runAdvertDeltaSync()
        assertEquals(listOf(false, true), refetchFlags)
    }

    @Test
    fun escalatedUnknownAdvertStampsLastHeardOnTheFullRefetch() = runTest {
        val radioID = ContactTestIds.RADIO
        val store = InMemoryContactStore()
        val key = publicKey(0x49)
        val oldStamp = 1_000_000L
        val session = FakeContactSession()
        val service = testContactService(session = session, store = store)
        val refetchFlags = mutableListOf<Boolean>()
        service.setDeltaSyncHandler { fullRefetch ->
            refetchFlags += fullRefetch
            if (fullRefetch) {
                session.stubbedContacts = listOf(
                    meshContact(keyByte = 0x49, name = "LateInsert", lastModified = oldStamp),
                )
                service.syncContacts(radioID, since = 0L)
            }
            AdvertContactSyncOutcome.SYNCED
        }

        assertFalse(service.handleAdvertisement(radioID, key))
        service.runAdvertDeltaSync()
        assertEquals(listOf(false), refetchFlags)
        assertNull(store.fetchContact(radioID, key))

        service.runAdvertDeltaSync()
        assertEquals(listOf(false, true), refetchFlags)
        val contact = store.fetchContact(radioID, key)!!
        val cutoff = CONTACT_TEST_NOW_SECONDS - 30L * 86_400L
        assertEquals(oldStamp, contact.lastModified)
        assertEquals(CONTACT_TEST_NOW_SECONDS, contact.lastHeardTimestamp)
        assertFalse(contact.matchesStaleNodePrune(cutoff))
    }

    @Test
    fun contactDeletedDuringDeltaRoundRollsBackAResavedRow() = runTest {
        val radioID = ContactTestIds.RADIO
        val store = InMemoryContactStore()
        val deletedKey = publicKey(0x94)
        val advertKey = publicKey(0x93)
        store.saveContact(radioID, contactFrame(0x94, name = "SlotVictim"))
        val session = FakeContactSession()
        val service = testContactService(session = session, store = store)
        val inHandler = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        service.setDeltaSyncHandler {
            inHandler.complete(Unit)
            release.await()
            store.saveContact(radioID, contactFrame(0x94, name = "Ghost"))
            AdvertContactSyncOutcome.SYNCED
        }

        assertFalse(service.handleAdvertisement(radioID, advertKey))
        val round = launch { service.runAdvertDeltaSync() }
        inHandler.await()
        assertTrue(service.handleDeviceContactDeleted(radioID, deletedKey))
        assertNull(store.fetchContact(radioID, deletedKey))
        release.complete(Unit)
        round.join()
        assertNull(store.fetchContact(radioID, deletedKey))
    }

    @Test
    fun reAdvertDuringDeltaRoundClearsTheTombstone() = runTest {
        val radioID = ContactTestIds.RADIO
        val store = InMemoryContactStore()
        val deletedKey = publicKey(0x94)
        val advertKey = publicKey(0x93)
        store.saveContact(radioID, contactFrame(0x94, name = "SlotVictim"))
        val session = FakeContactSession()
        val service = testContactService(session = session, store = store)
        val inHandler = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        service.setDeltaSyncHandler {
            inHandler.complete(Unit)
            release.await()
            store.saveContact(radioID, contactFrame(0x94, name = "ReAdded"))
            AdvertContactSyncOutcome.SYNCED
        }

        assertFalse(service.handleAdvertisement(radioID, advertKey))
        val round = launch { service.runAdvertDeltaSync() }
        inHandler.await()
        assertTrue(service.handleDeviceContactDeleted(radioID, deletedKey))
        assertFalse(service.handleAdvertisement(radioID, deletedKey))
        release.complete(Unit)
        round.join()
        assertEquals("ReAdded", store.fetchContact(radioID, deletedKey)?.name)
    }
}

/** Known-contact advert retries: one retry, then the key is recorded or dropped. */
class ContactAdvertKnownRetryTest {
    @Test
    fun knownAdvertRetriesTouchOnceThenRecordsTheKey() = runTest {
        val radioID = ContactTestIds.RADIO
        val inner = InMemoryContactStore()
        val key = publicKey(0x50)
        inner.saveContact(
            radioID,
            contactFrame(0x50, name = "Flaky").copy(
                lastAdvertTimestamp = 1_000L,
                lastModified = 1_000L,
            ),
        )
        inner.saveContact(inner.fetchContact(radioID, key)!!.copy(lastHeardTimestamp = 1L))
        val store = FlakyTouchContactStore(inner, throwOnFirstTouches = 1)
        val service = testContactService(store = store)
        var handlerCalls = 0
        service.setDeltaSyncHandler {
            handlerCalls += 1
            AdvertContactSyncOutcome.SYNCED
        }

        assertTrue(service.handleAdvertisement(radioID, key))
        assertEquals(2, store.touchCalls)
        assertEquals(CONTACT_TEST_NOW_SECONDS, inner.fetchContact(radioID, key)?.lastHeardTimestamp)

        service.runAdvertDeltaSync()
        assertEquals(1, handlerCalls)
    }

    @Test
    fun knownAdvertDropsTheKeyWhenTouchFailsTwice() = runTest {
        val radioID = ContactTestIds.RADIO
        val inner = InMemoryContactStore()
        val key = publicKey(0x51)
        inner.saveContact(
            radioID,
            contactFrame(0x51, name = "LongKnown").copy(
                lastAdvertTimestamp = 1_000L,
                lastModified = 1_000L,
            ),
        )
        inner.saveContact(inner.fetchContact(radioID, key)!!.copy(lastHeardTimestamp = 1L))
        val store = FlakyTouchContactStore(inner, throwOnFirstTouches = 2)
        val service = testContactService(store = store)
        var handlerCalls = 0
        service.setDeltaSyncHandler {
            handlerCalls += 1
            AdvertContactSyncOutcome.SYNCED
        }

        assertFalse(service.handleAdvertisement(radioID, key))
        assertEquals(2, store.touchCalls)
        assertEquals(1L, inner.fetchContact(radioID, key)?.lastHeardTimestamp)

        service.runAdvertDeltaSync()
        assertEquals(0, handlerCalls)
    }

    @Test
    fun failedKnownAdvertStillSchedulesDeltaForOtherPendingKeys() = runTest {
        val radioID = ContactTestIds.RADIO
        val inner = InMemoryContactStore()
        val unknownKey = publicKey(0x52)
        val knownKey = publicKey(0x53)
        inner.saveContact(
            radioID,
            contactFrame(0x53, name = "Known").copy(
                lastAdvertTimestamp = 1_000L,
                lastModified = 1_000L,
            ),
        )
        inner.saveContact(inner.fetchContact(radioID, knownKey)!!.copy(lastHeardTimestamp = 1L))
        val store = FlakyTouchContactStore(inner, throwOnFirstTouches = 2)
        val session = FakeContactSession().apply {
            stubbedContacts = listOf(meshContact(keyByte = 0x52, name = "FromDelta"))
        }
        val service = testContactService(session = session, store = store)
        var handlerCalls = 0
        service.setDeltaSyncHandler {
            handlerCalls += 1
            service.syncContacts(radioID, since = 1L)
            AdvertContactSyncOutcome.SYNCED
        }

        assertFalse(service.handleAdvertisement(radioID, unknownKey))
        assertFalse(service.handleAdvertisement(radioID, knownKey))
        assertEquals(2, store.touchCalls)
        assertEquals(1L, inner.fetchContact(radioID, knownKey)?.lastHeardTimestamp)

        service.runAdvertDeltaSync()
        assertEquals(1, handlerCalls)
        assertEquals("FromDelta", inner.fetchContact(radioID, unknownKey)?.name)
        assertEquals(1L, inner.fetchContact(radioID, knownKey)?.lastHeardTimestamp)
    }

    @Test
    fun knownAdvertTouchCancelDoesNotRetry() = runTest {
        val radioID = ContactTestIds.RADIO
        val inner = InMemoryContactStore()
        val key = publicKey(0x54)
        inner.saveContact(radioID, contactFrame(0x54, name = "Cancel"))
        val store = FlakyTouchContactStore(inner, cancelOnFirstTouch = true)
        val service = testContactService(store = store)
        var handlerCalls = 0
        service.setDeltaSyncHandler {
            handlerCalls += 1
            AdvertContactSyncOutcome.SYNCED
        }

        assertFailsWith<CancellationException> { service.handleAdvertisement(radioID, key) }
        assertEquals(1, store.touchCalls)
        service.runAdvertDeltaSync()
        assertEquals(0, handlerCalls)
    }

    @Test
    fun knownAdvertRetriesFetchOnceThenRecordsTheKey() = runTest {
        val radioID = ContactTestIds.RADIO
        val inner = InMemoryContactStore()
        val key = publicKey(0x55)
        inner.saveContact(
            radioID,
            contactFrame(0x55, name = "FetchFlaky").copy(
                lastAdvertTimestamp = 1_000L,
                lastModified = 1_000L,
            ),
        )
        inner.saveContact(inner.fetchContact(radioID, key)!!.copy(lastHeardTimestamp = 1L))
        val store = FlakyTouchContactStore(inner, throwOnFirstFetches = 1)
        val service = testContactService(store = store)
        var handlerCalls = 0
        service.setDeltaSyncHandler {
            handlerCalls += 1
            AdvertContactSyncOutcome.SYNCED
        }

        assertTrue(service.handleAdvertisement(radioID, key))
        assertEquals(2, store.fetchCalls)
        assertEquals(CONTACT_TEST_NOW_SECONDS, inner.fetchContact(radioID, key)?.lastHeardTimestamp)

        service.runAdvertDeltaSync()
        assertEquals(1, handlerCalls)
    }

    @Test
    fun knownAdvertDropsTheKeyWhenFetchFailsTwice() = runTest {
        val radioID = ContactTestIds.RADIO
        val inner = InMemoryContactStore()
        val key = publicKey(0x56)
        inner.saveContact(
            radioID,
            contactFrame(0x56, name = "LongKnown").copy(
                lastAdvertTimestamp = 1_000L,
                lastModified = 1_000L,
            ),
        )
        inner.saveContact(inner.fetchContact(radioID, key)!!.copy(lastHeardTimestamp = 1L))
        val store = FlakyTouchContactStore(inner, throwOnFirstFetches = 2)
        val service = testContactService(store = store)
        var handlerCalls = 0
        service.setDeltaSyncHandler {
            handlerCalls += 1
            AdvertContactSyncOutcome.SYNCED
        }

        assertFalse(service.handleAdvertisement(radioID, key))
        assertEquals(2, store.fetchCalls)
        assertEquals(0, store.touchCalls)
        assertEquals(1L, inner.fetchContact(radioID, key)?.lastHeardTimestamp)

        service.runAdvertDeltaSync()
        assertEquals(0, handlerCalls)
    }

    @Test
    fun failedFetchStillSchedulesDeltaForOtherPendingKeys() = runTest {
        val radioID = ContactTestIds.RADIO
        val inner = InMemoryContactStore()
        val unknownKey = publicKey(0x57)
        val knownKey = publicKey(0x58)
        inner.saveContact(
            radioID,
            contactFrame(0x58, name = "Known").copy(
                lastAdvertTimestamp = 1_000L,
                lastModified = 1_000L,
            ),
        )
        inner.saveContact(inner.fetchContact(radioID, knownKey)!!.copy(lastHeardTimestamp = 1L))
        val store = FlakyTouchContactStore(
            inner,
            throwOnFirstFetches = 2,
            throwOnFetchKey = knownKey,
        )
        val session = FakeContactSession().apply {
            stubbedContacts = listOf(meshContact(keyByte = 0x57, name = "FromDelta"))
        }
        val service = testContactService(session = session, store = store)
        var handlerCalls = 0
        service.setDeltaSyncHandler {
            handlerCalls += 1
            service.syncContacts(radioID, since = 1L)
            AdvertContactSyncOutcome.SYNCED
        }

        assertFalse(service.handleAdvertisement(radioID, unknownKey))
        assertFalse(service.handleAdvertisement(radioID, knownKey))
        assertEquals(2, store.fetchCalls)
        assertEquals(0, store.touchCalls)

        service.runAdvertDeltaSync()
        assertEquals(1, handlerCalls)
        assertEquals("FromDelta", inner.fetchContact(radioID, unknownKey)?.name)
        assertEquals(1L, inner.fetchContact(radioID, knownKey)?.lastHeardTimestamp)
    }
}

private class RecordingOrphanDmAdopter : OrphanDmAdopter {
    var calls = 0
    var lastContacts: List<OrphanDmContact> = emptyList()

    override suspend fun adopt(
        radioID: one.meshcore.shared.model.Uuid,
        contacts: List<OrphanDmContact>,
    ): Set<one.meshcore.shared.model.Uuid> {
        calls += 1
        lastContacts = contacts
        return contacts.map { it.id }.toSet()
    }
}
