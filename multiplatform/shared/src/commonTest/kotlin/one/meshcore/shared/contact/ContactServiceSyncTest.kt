package one.meshcore.shared.contact

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Mirrors the iOS `ContactServiceSyncTests`: a full sync persists every device contact and prunes local
 * rows the device no longer reports, while an incremental sync upserts without pruning. Exercises the
 * real [InMemoryContactStore] so the batch-upsert and prune paths run end to end.
 */
class ContactServiceSyncTest {
    @Test
    fun fullSyncPersistsAllDeviceContactsAndPrunesLocalsNotOnDevice() = runTest {
        val radioID = ContactTestIds.RADIO
        val store = InMemoryContactStore()
        // A stale local contact the device no longer reports.
        store.saveContact(radioID, contactFrame(0xDD, name = "Stale"))

        val session = FakeContactSession()
        val lastModified = 1_700_000_000L
        session.stubbedContacts = listOf(
            meshContact(0xAA, name = "Alice", lastModified = lastModified),
            meshContact(0xBB, name = "Bob"),
        )

        val service = testContactService(session = session, store = store)
        val result = service.syncContacts(radioID, since = null)

        assertEquals(2, result.contactsReceived)
        assertFalse(result.isIncremental)
        assertEquals(lastModified, result.lastSyncTimestamp)

        val stored = store.fetchContacts(radioID)
        assertEquals(listOf("Alice", "Bob"), stored.map { it.name })
        // The stale contact was pruned on full sync.
        assertNull(store.fetchContact(radioID, publicKey(0xDD)))
    }

    @Test
    fun incrementalSyncUpsertsWithoutPruningUnseenLocals() = runTest {
        val radioID = ContactTestIds.RADIO
        val store = InMemoryContactStore()
        // A local contact absent from this incremental batch must survive.
        store.saveContact(radioID, contactFrame(0xDD, name = "Existing"))

        val session = FakeContactSession()
        session.stubbedContacts = listOf(meshContact(0xAA, name = "Alice"))

        val service = testContactService(session = session, store = store)
        val result = service.syncContacts(radioID, since = 100L)

        assertEquals(1, result.contactsReceived)
        assertTrue(result.isIncremental)

        val stored = store.fetchContacts(radioID)
        assertEquals(setOf("Alice", "Existing"), stored.map { it.name }.toSet())
    }

    @Test
    fun fullSyncSkipsPruneWhenReportedTotalIsMissing() = runTest {
        val radioID = ContactTestIds.RADIO
        val store = InMemoryContactStore()
        store.saveContact(radioID, contactFrame(0xDD, name = "Stale"))

        val session = FakeContactSession()
        session.stubbedContacts = listOf(meshContact(0xAA, name = "Alice"))
        session.omitReportedTotal = true

        val service = testContactService(session = session, store = store)
        service.syncContacts(radioID, since = null)

        assertEquals("Stale", store.fetchContact(radioID, publicKey(0xDD))?.name)
        assertEquals("Alice", store.fetchContact(radioID, publicKey(0xAA))?.name)
    }

    @Test
    fun fullSyncSkipsPruneWhenSnapshotIsTruncated() = runTest {
        val radioID = ContactTestIds.RADIO
        val store = InMemoryContactStore()
        store.saveContact(radioID, contactFrame(0xDD, name = "Stale"))

        val session = FakeContactSession()
        session.stubbedContacts = listOf(meshContact(0xAA, name = "Alice"))
        session.stubbedReportedTotal = 5

        val service = testContactService(session = session, store = store)
        service.syncContacts(radioID, since = null)

        assertEquals("Stale", store.fetchContact(radioID, publicKey(0xDD))?.name)
    }

    @Test
    fun fullSyncSkipsPruneWhenSelfPublicKeyIsMissing() = runTest {
        val radioID = ContactTestIds.RADIO
        val store = InMemoryContactStore()
        store.saveContact(radioID, contactFrame(0xDD, name = "Stale"))

        val session = FakeContactSession()
        session.stubbedContacts = listOf(meshContact(0xAA, name = "Alice"))

        val service = testContactService(session = session, store = store, selfPublicKey = { null })
        service.syncContacts(radioID, since = null)

        assertEquals("Stale", store.fetchContact(radioID, publicKey(0xDD))?.name)
    }
}
