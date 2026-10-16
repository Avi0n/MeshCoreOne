package one.meshcore.shared.contact

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Parks 0x80 / path / 0x8F work while the scene is backgrounded, then flushes on foreground or
 * teardown. Mirrors iOS `AdvertisementService` background deferral.
 */
class ContactAdvertForegroundDeferralTest {
    @Test
    fun backgroundAdvertRecordsTheKeyAndDoesNotTouchOrInvokeTheHandler() = runTest {
        val radioID = ContactTestIds.RADIO
        val store = InMemoryContactStore()
        store.saveContact(radioID, contactFrame(0xB0, name = "Known"))
        val key = publicKey(0xB0)
        store.saveContact(store.fetchContact(radioID, key)!!.copy(lastHeardTimestamp = 10L))
        var handlerCalls = 0
        val service = testContactService(store = store, isInForeground = { false })
        service.setDeltaSyncHandler {
            handlerCalls += 1
            AdvertContactSyncOutcome.SYNCED
        }

        assertFalse(service.handleAdvertisement(radioID, key))
        assertTrue(service.hasPendingAdvertKey(key))
        assertEquals(0, handlerCalls)
        assertEquals(10L, store.fetchContact(radioID, key)?.lastHeardTimestamp)

        service.runAdvertDeltaSync()
        assertEquals(0, handlerCalls)
        assertTrue(service.hasPendingAdvertKey(key))
    }

    @Test
    fun handleReturnToForegroundDrainsKeysRecordedWhileBackgrounded() = runTest {
        val radioID = ContactTestIds.RADIO
        val store = InMemoryContactStore()
        val key = publicKey(0xB2)
        val session = FakeContactSession().apply {
            stubbedContacts = listOf(meshContact(keyByte = 0xB2, name = "FromDelta"))
        }
        var foreground = false
        var handlerCalls = 0
        val service = testContactService(
            session = session,
            store = store,
            isInForeground = { foreground },
        )
        service.setDeltaSyncHandler {
            handlerCalls += 1
            service.syncContacts(radioID, since = 1L)
            AdvertContactSyncOutcome.SYNCED
        }

        assertFalse(service.handleAdvertisement(radioID, key))
        assertEquals(0, handlerCalls)

        foreground = true
        service.handleReturnToForeground()
        service.runAdvertDeltaSync()
        assertEquals(1, handlerCalls)
        assertEquals("FromDelta", store.fetchContact(radioID, key)?.name)
    }

    @Test
    fun backgroundPathUpdateRecordsTheKeyAndDoesNotInvokeTheHandler() = runTest {
        val radioID = ContactTestIds.RADIO
        val key = publicKey(0xB3)
        var handlerCalls = 0
        val service = testContactService(isInForeground = { false })
        service.setDeltaSyncHandler {
            handlerCalls += 1
            AdvertContactSyncOutcome.SYNCED
        }

        assertFalse(service.refreshPathFromDevice(radioID, key))
        assertTrue(service.hasPendingPathKey(key))
        service.runAdvertDeltaSync()
        assertEquals(0, handlerCalls)
    }

    @Test
    fun armedRoundThatBackgroundsBeforeFireDoesNotInvokeTheHandler() = runTest {
        val radioID = ContactTestIds.RADIO
        val store = InMemoryContactStore()
        val key = publicKey(0xB4)
        store.saveContact(radioID, contactFrame(0xB4, name = "Armed"))
        var foreground = true
        var handlerCalls = 0
        val service = testContactService(store = store, isInForeground = { foreground })
        service.setDeltaSyncHandler {
            handlerCalls += 1
            AdvertContactSyncOutcome.SYNCED
        }

        assertTrue(service.handleAdvertisement(radioID, key))
        foreground = false
        service.runAdvertDeltaSync()
        assertEquals(0, handlerCalls)
        assertTrue(service.hasPendingAdvertKey(key))
    }

    @Test
    fun setSyncingContactsFalseWhileBackgroundedDoesNotInvokeTheHandler() = runTest {
        val radioID = ContactTestIds.RADIO
        val key = publicKey(0xB6)
        var handlerCalls = 0
        val service = testContactService(isInForeground = { false })
        service.setDeltaSyncHandler {
            handlerCalls += 1
            AdvertContactSyncOutcome.SYNCED
        }

        assertFalse(service.handleAdvertisement(radioID, key))
        service.setSyncingContacts(radioID, isSyncing = true)
        service.setSyncingContacts(radioID, isSyncing = false)
        service.runAdvertDeltaSync()
        assertEquals(0, handlerCalls)
        assertTrue(service.hasPendingAdvertKey(key))
    }

    @Test
    fun backgroundContactDeletedQueuesTheKeyAndDoesNotDelete() = runTest {
        val radioID = ContactTestIds.RADIO
        val store = InMemoryContactStore()
        val key = publicKey(0x8C)
        val contactID = store.saveContact(radioID, contactFrame(0x8C, name = "Doomed"))
        val cleanup = RecordingCleanupHandler()
        val service = testContactService(store = store, cleanup = cleanup, isInForeground = { false })
        service.setDeltaSyncHandler { AdvertContactSyncOutcome.SYNCED }

        assertFalse(service.handleAdvertisement(radioID, key))
        assertFalse(service.handleDeviceContactDeleted(radioID, key))
        assertTrue(service.hasPendingDeletedKey(key))
        assertFalse(service.hasPendingAdvertKey(key))
        assertEquals(contactID, store.fetchContact(radioID, key)?.id)
        assertTrue(cleanup.invocations.isEmpty())
    }

    @Test
    fun handleReturnToForegroundFlushesQueuedDeletesOnce() = runTest {
        val radioID = ContactTestIds.RADIO
        val store = InMemoryContactStore()
        val keyA = publicKey(0xA1)
        val keyB = publicKey(0xA2)
        val savedA = store.saveContact(radioID, contactFrame(0xA1, name = "A"))
        val savedB = store.saveContact(radioID, contactFrame(0xA2, name = "B"))
        val cleanup = RecordingCleanupHandler()
        var foreground = false
        val service = testContactService(
            store = store,
            cleanup = cleanup,
            isInForeground = { foreground },
        )
        val events = service.events()

        assertFalse(service.handleDeviceContactDeleted(radioID, keyA))
        assertFalse(service.handleDeviceContactDeleted(radioID, keyB))

        foreground = true
        service.handleReturnToForeground()

        assertNull(store.fetchContact(radioID, keyA))
        assertNull(store.fetchContact(radioID, keyB))
        assertFalse(service.hasPendingDeletedKey(keyA))
        assertFalse(service.hasPendingDeletedKey(keyB))
        assertEquals(setOf(savedA, savedB), cleanup.invocations.map { it.contactID }.toSet())
        val yielded = mutableListOf<ContactServiceEvent>()
        while (true) {
            val event = events.tryReceive().getOrNull() ?: break
            yielded += event
        }
        assertEquals(1, yielded.count { it is ContactServiceEvent.NodeDeleted })
        assertEquals(1, yielded.count { it is ContactServiceEvent.NodeStorageFullChanged })
    }

    @Test
    fun foregroundDeletePersistFaultRequeuesAndFlushesOnReturnToForeground() = runTest {
        val radioID = ContactTestIds.RADIO
        val inner = InMemoryContactStore()
        val key = publicKey(0xD1)
        inner.saveContact(radioID, contactFrame(0xD1, name = "Victim"))
        val store = object : ContactStore by inner {
            var remainingThrows = 1
            override suspend fun deleteContact(id: Uuid) {
                if (remainingThrows > 0) {
                    remainingThrows -= 1
                    error("db locked")
                }
                inner.deleteContact(id)
            }
        }
        val cleanup = RecordingCleanupHandler()
        val service = testContactService(store = store, cleanup = cleanup)

        assertFalse(service.handleDeviceContactDeleted(radioID, key))
        assertTrue(service.hasPendingDeletedKey(key))
        assertEquals("Victim", store.fetchContact(radioID, key)?.name)
        assertTrue(cleanup.invocations.isEmpty())

        service.handleReturnToForeground()

        assertNull(store.fetchContact(radioID, key))
        assertFalse(service.hasPendingDeletedKey(key))
        assertEquals(1, cleanup.invocations.size)
    }

    @Test
    fun reAdvertAfterBackgroundDeleteClearsThePendingDelete() = runTest {
        val radioID = ContactTestIds.RADIO
        val store = InMemoryContactStore()
        val key = publicKey(0xA3)
        store.saveContact(radioID, contactFrame(0xA3, name = "Back"))
        var foreground = false
        val service = testContactService(store = store, isInForeground = { foreground })
        service.setDeltaSyncHandler { AdvertContactSyncOutcome.SYNCED }

        assertFalse(service.handleDeviceContactDeleted(radioID, key))
        assertTrue(service.hasPendingDeletedKey(key))

        assertFalse(service.handleAdvertisement(radioID, key))
        assertFalse(service.hasPendingDeletedKey(key))
        assertTrue(service.hasPendingAdvertKey(key))
        assertEquals("Back", store.fetchContact(radioID, key)?.name)

        foreground = true
        service.handleReturnToForeground()
        assertEquals("Back", store.fetchContact(radioID, key)?.name)
    }

    @Test
    fun stopAdvertDeltaSyncStampsLastHeardForABackgroundAdvert() = runTest {
        val radioID = ContactTestIds.RADIO
        val store = InMemoryContactStore()
        store.saveContact(radioID, contactFrame(0xB7, name = "HeardOvernight"))
        val key = publicKey(0xB7)
        store.saveContact(store.fetchContact(radioID, key)!!.copy(lastHeardTimestamp = 10L))
        val service = testContactService(store = store, isInForeground = { false })
        service.setDeltaSyncHandler { AdvertContactSyncOutcome.SYNCED }

        assertFalse(service.handleAdvertisement(radioID, key))
        assertEquals(10L, store.fetchContact(radioID, key)?.lastHeardTimestamp)

        service.stopAdvertDeltaSync()
        assertEquals(CONTACT_TEST_NOW_SECONDS, store.fetchContact(radioID, key)?.lastHeardTimestamp)
    }

    @Test
    fun stopAdvertDeltaSyncFlushesQueuedDeletes() = runTest {
        val radioID = ContactTestIds.RADIO
        val store = InMemoryContactStore()
        val key = publicKey(0xC1)
        store.saveContact(radioID, contactFrame(0xC1, name = "Parked"))
        val service = testContactService(store = store, isInForeground = { false })

        assertFalse(service.handleDeviceContactDeleted(radioID, key))
        assertEquals("Parked", store.fetchContact(radioID, key)?.name)

        service.stopAdvertDeltaSync()
        assertNull(store.fetchContact(radioID, key))
        assertFalse(service.hasPendingDeletedKey(key))
    }

    @Test
    fun advertDuringDeleteFlushHopDoesNotDeleteTheRevivedContact() = runTest {
        val radioID = ContactTestIds.RADIO
        val inner = InMemoryContactStore()
        val key = publicKey(0xA4)
        val saved = inner.saveContact(radioID, contactFrame(0xA4, name = "Revived"))
        val hold = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val store = object : ContactStore by inner {
            override suspend fun deleteContacts(
                radioID: Uuid,
                publicKeys: Collection<ByteArray>,
                skippingPublicKeys: suspend () -> Set<String>,
            ): List<Uuid> {
                entered.complete(Unit)
                hold.await()
                return inner.deleteContacts(radioID, publicKeys, skippingPublicKeys)
            }
        }
        var foreground = false
        val service = testContactService(store = store, isInForeground = { foreground })
        service.setDeltaSyncHandler { AdvertContactSyncOutcome.SYNCED }

        assertFalse(service.handleDeviceContactDeleted(radioID, key))

        foreground = true
        val flushed = async { service.handleReturnToForeground() }
        entered.await()
        assertTrue(service.handleAdvertisement(radioID, key))
        assertTrue(service.hasPendingAdvertKey(key))
        hold.complete(Unit)
        flushed.await()

        assertEquals(saved, store.fetchContact(radioID, key)?.id)
        assertTrue(inner.deletedMessagesForContactIDs().isEmpty())
    }

    @Test
    fun aFailedForegroundHearStillSkipsTheDeferredDelete() = runTest {
        val radioID = ContactTestIds.RADIO
        val inner = InMemoryContactStore()
        val key = publicKey(0xA5)
        val saved = inner.saveContact(radioID, contactFrame(0xA5, name = "Heard"))
        val store = FlakyTouchContactStore(inner, throwOnFirstTouches = 2)
        var foreground = false
        val service = testContactService(store = store, isInForeground = { foreground })
        service.setDeltaSyncHandler { AdvertContactSyncOutcome.SYNCED }

        assertFalse(service.handleDeviceContactDeleted(radioID, key))
        assertTrue(service.hasPendingDeletedKey(key))

        foreground = true
        assertFalse(service.handleAdvertisement(radioID, key))
        assertTrue(service.hasPendingDeletedKey(key))

        service.handleReturnToForeground()
        assertEquals(saved, store.fetchContact(radioID, key)?.id)
        assertTrue(inner.deletedMessagesForContactIDs().isEmpty())
    }
}
