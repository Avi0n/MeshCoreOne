package one.meshcore.shared.contact

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.DebugLogLevel
import one.meshcore.shared.settings.PersistentLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Insert-only rollback after a cancelled 0x80/path fetch. iOS
 * `rollbackInsertIfNeeded` / `deleteContactIfUnreferenced`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactInsertRollbackTest {
    @Test
    fun dumpLineMatchesIos() {
        val id = Uuid.normalized("A0000000-0000-0000-0000-000000000001")
        assertEquals(
            "Insert-only rollback failed for A0000000-0000-0000-0000-000000000001: db locked",
            insertOnlyRollbackFailedLine(id, "db locked"),
        )
    }

    @Test
    fun unreferencedContactIsDeleted() = runTest {
        val store = InMemoryContactStore()
        val id = store.saveContact(ContactTestIds.RADIO, contactFrame(0x11, name = "Bare"))

        store.deleteContactIfUnreferenced(id)

        assertNull(store.fetchContact(id))
    }

    @Test
    fun referencedContactIsKept() = runTest {
        val store = InMemoryContactStore()
        val id = store.saveContact(ContactTestIds.RADIO, contactFrame(0x12, name = "HasMsgs"))
        store.seedMessages(id, count = 1)

        store.deleteContactIfUnreferenced(id)

        assertEquals("HasMsgs", store.fetchContact(id)?.name)
        assertEquals(1, store.remainingMessageCount(id))
    }

    @Test
    fun cancelledSaveOfNewInsertRollsBackTheRow() = runTest {
        val inner = InMemoryContactStore()
        lateinit var service: ContactService
        val radioID = ContactTestIds.RADIO
        val key = publicKey(0x91)
        val store = object : ContactStore by inner {
            override suspend fun saveContact(radioID: Uuid, frame: ContactFrame): Uuid {
                // 0x8F after the pre-save cancel check and before the insert lands.
                service.handleDeviceContactDeleted(radioID, frame.publicKey)
                return inner.saveContact(radioID, frame)
            }
        }
        val session = FakeContactSession().apply {
            singleContactLookup = meshContact(keyByte = 0x91, name = "Ghost")
        }
        service = testContactService(session = session, store = store)

        assertFalse(service.handleAdvertisement(radioID, key))
        assertNull(store.fetchContact(radioID, key))
    }

    @Test
    fun cancelledUpsertOfExistingContactIsNotRolledBack() = runTest {
        val inner = InMemoryContactStore()
        val radioID = ContactTestIds.RADIO
        val key = publicKey(0x92)
        val existingId = inner.saveContact(radioID, contactFrame(0x92, name = "Kept"))
        inner.seedMessages(existingId, count = 1)
        lateinit var service: ContactService
        // 0x8F during save: hide the row from overwrite-oldest so only the cancel stamp lands.
        val store = object : ContactStore by inner {
            var hidingForOverwrite = false
            override suspend fun fetchContact(radioID: Uuid, publicKey: ByteArray) =
                if (hidingForOverwrite) null else inner.fetchContact(radioID, publicKey)

            override suspend fun saveContact(radioID: Uuid, frame: ContactFrame): Uuid {
                val id = inner.saveContact(radioID, frame)
                hidingForOverwrite = true
                service.handleDeviceContactDeleted(radioID, frame.publicKey)
                hidingForOverwrite = false
                return id
            }
        }
        val session = FakeContactSession().apply {
            singleContactLookup = meshContact(keyByte = 0x92, name = "Kept")
        }
        service = testContactService(session = session, store = store)

        assertFalse(service.refreshPathFromDevice(radioID, key))
        assertEquals("Kept", inner.fetchContact(existingId)?.name)
        assertEquals(1, inner.remainingMessageCount(existingId))
    }

    @Test
    fun rollbackPersistFaultRecordsDumpAndDoesNotThrow() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val inner = InMemoryContactStore()
        val radioID = ContactTestIds.RADIO
        val key = publicKey(0x93)
        val insertedId = ContactTestIds.fresh()
        lateinit var service: ContactService
        val store = object : ContactStore by inner {
            override suspend fun saveContact(radioID: Uuid, frame: ContactFrame): Uuid {
                service.handleDeviceContactDeleted(radioID, frame.publicKey)
                inner.saveContact(contactDto(id = insertedId, radioID = radioID, keyByte = 0x93, name = "Ghost"))
                return insertedId
            }

            override suspend fun deleteContactIfUnreferenced(id: Uuid) {
                error("db locked")
            }
        }
        val session = FakeContactSession().apply {
            singleContactLookup = meshContact(keyByte = 0x93, name = "Ghost")
        }
        service = testContactService(
            session = session,
            store = store,
            logger = PersistentLogger(
                scope = this,
                category = "AdvertisementService",
                record = { recorded += it },
            ),
        )

        assertFalse(service.handleAdvertisement(radioID, key))
        val line = recorded.single { it.message.startsWith("Insert-only rollback failed for") }
        assertEquals(DebugLogLevel.WARNING, line.level)
        assertEquals("AdvertisementService", line.category)
        assertEquals(insertOnlyRollbackFailedLine(insertedId, "db locked"), line.message)
    }
}
