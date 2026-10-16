package one.meshcore.shared.contact

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.DebugLogLevel
import one.meshcore.shared.settings.PersistentLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** iOS `ContactService` full-sync prune dump lines. */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactSyncPruneDumpTest {
    @Test
    fun dumpLinesMatchIos() {
        val key = publicKey(0xDD)
        assertEquals(
            "Full sync prune: 1 local contact(s) not found on device (device has 2, local has 3)",
            fullSyncPruneSummaryLine(orphanCount = 1, deviceCount = 2, localCount = 3),
        )
        assertEquals(
            "Full sync prune: deleting 'Stale' [dddddddd…] (favorite=false, " +
                "type=${ContactType.CHAT.raw}, lastModified=0)",
            fullSyncPruneDeletingLine(
                name = "Stale",
                publicKey = key,
                isFavorite = false,
                typeRawValue = ContactType.CHAT.raw,
                lastModified = 0,
            ),
        )
    }

    @Test
    fun fullSyncPruneRecordsSummaryAndDeletingLines() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val radioID = ContactTestIds.RADIO
        val store = InMemoryContactStore()
        store.saveContact(radioID, contactFrame(0xDD, name = "Stale"))
        val session = FakeContactSession().apply {
            stubbedContacts = listOf(
                meshContact(0xAA, name = "Alice"),
                meshContact(0xBB, name = "Bob"),
            )
        }
        val service = testContactService(
            session = session,
            store = store,
            logger = PersistentLogger(
                scope = this,
                category = "ContactService",
                record = { recorded += it },
            ),
        )

        service.syncContacts(radioID, since = null)

        val summary = recorded.single { it.message.startsWith("Full sync prune: 1 local contact") }
        assertEquals(DebugLogLevel.NOTICE, summary.level)
        assertEquals("ContactService", summary.category)
        assertEquals(
            fullSyncPruneSummaryLine(orphanCount = 1, deviceCount = 2, localCount = 3),
            summary.message,
        )
        val deleting = recorded.single { it.message.startsWith("Full sync prune: deleting") }
        assertEquals(
            fullSyncPruneDeletingLine(
                name = "Stale",
                publicKey = publicKey(0xDD),
                isFavorite = false,
                typeRawValue = ContactType.CHAT.raw,
                lastModified = 0,
            ),
            deleting.message,
        )
        assertNull(store.fetchContact(radioID, publicKey(0xDD)))
    }

    @Test
    fun pruneDeletePersistFaultKeepsSyncAndDoesNotThrow() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val radioID = ContactTestIds.RADIO
        val inner = InMemoryContactStore()
        inner.saveContact(radioID, contactFrame(0xDD, name = "Stale"))
        val store = object : ContactStore by inner {
            override suspend fun deleteContact(id: Uuid) {
                error("db locked")
            }
        }
        val session = FakeContactSession().apply {
            stubbedContacts = listOf(meshContact(0xAA, name = "Alice"))
        }
        val service = testContactService(
            session = session,
            store = store,
            logger = PersistentLogger(
                scope = this,
                category = "ContactService",
                record = { recorded += it },
            ),
        )

        val result = service.syncContacts(radioID, since = null)

        assertEquals(1, result.contactsReceived)
        assertTrue(recorded.any { it.message.startsWith("Full sync prune: deleting") })
        assertEquals("Stale", inner.fetchContact(radioID, publicKey(0xDD))?.name)
    }
}
