package one.meshcore.shared.contact

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.model.toUppercaseHex
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.DebugLogLevel
import one.meshcore.shared.settings.PersistentLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The radio-drop dump must carry overwrite-oldest and contacts-full lines. iOS writes these through
 * `PersistentLogger(category: "AdvertisementService")`; these tests pin the same strings on Kotlin.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactOverwriteOldestLoggerTest {
    @Test
    fun `overwrite oldest records delete dump lines`() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val store = InMemoryContactStore()
        val radioID = ContactTestIds.RADIO
        val key = publicKey(0x44)
        store.saveContact(contactDto(id = ContactTestIds.fresh(), radioID = radioID, keyByte = 0x44, name = "Victim"))
        val service = testContactService(store = store, logger = logger(this, recorded))

        assertTrue(service.handleDeviceContactDeleted(radioID, key))

        val prefix = key.copyOfRange(0, 6).toUppercaseHex()
        assertTrue(
            recorded.any {
                it.level == DebugLogLevel.INFO &&
                    it.message.contains("device deleted contact with key $prefix")
            },
        )
        val notice = recorded.single { it.level == DebugLogLevel.NOTICE }
        assertTrue(notice.message.contains("deleting contact 'Victim'"))
        assertTrue(notice.message.contains(key.toUppercaseHex()))
        assertTrue(recorded.any { it.message.contains("cleanup complete for 'Victim'") })
        assertEquals("AdvertisementService", recorded.first().category)
    }

    @Test
    fun `overwrite oldest persist fault records failed to delete and returns false`() =
        runTest(UnconfinedTestDispatcher()) {
            val recorded = mutableListOf<DebugLogEntryDto>()
            val radioID = ContactTestIds.RADIO
            val key = publicKey(0x46)
            val store = object : ContactStore by InMemoryContactStore() {
                override suspend fun fetchContact(radioID: Uuid, publicKey: ByteArray) =
                    contactDto(id = ContactTestIds.fresh(), radioID = radioID, keyByte = 0x46, name = "Victim")

                override suspend fun deleteContact(id: Uuid) {
                    error("db locked")
                }
            }
            val service = testContactService(store = store, logger = logger(this, recorded))

            assertFalse(service.handleDeviceContactDeleted(radioID, key))
            assertTrue(service.hasPendingDeletedKey(key))

            val prefix = key.copyOfRange(0, 6).toUppercaseHex()
            val line = recorded.single { it.message.contains("Overwrite oldest: failed to delete contact") }
            assertEquals(DebugLogLevel.ERROR, line.level)
            assertEquals("AdvertisementService", line.category)
            assertTrue(line.message.contains("$prefix..."))
            assertTrue(line.message.contains("db locked"))
            assertFalse(recorded.any { it.message.contains("cleanup complete") })
        }

    @Test
    fun `overwrite oldest missing row records not-found warning`() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val service = testContactService(logger = logger(this, recorded))

        assertFalse(service.handleDeviceContactDeleted(ContactTestIds.RADIO, publicKey(0x55)))

        val line = recorded.single { it.message.contains("contact not found in local database") }
        assertEquals(DebugLogLevel.WARNING, line.level)
    }

    @Test
    fun `contacts full records the iOS storage-full warning`() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val service = testContactService(logger = logger(this, recorded))

        service.notifyNodeStorageFull()

        val line = recorded.single { it.message.contains("Device node storage is full") }
        assertEquals(DebugLogLevel.WARNING, line.level)
        assertTrue(line.message.contains("overwrite oldest"))
    }

    @Test
    fun `auto-add persist fault records Failed to save fetched contact and returns false`() =
        runTest(UnconfinedTestDispatcher()) {
            val recorded = mutableListOf<DebugLogEntryDto>()
            val store = object : ContactStore by InMemoryContactStore() {
                override suspend fun saveContact(radioID: Uuid, frame: ContactFrame): Uuid {
                    error("db locked")
                }
            }
            val session = FakeContactSession().apply {
                singleContactLookup = meshContact(keyByte = 0x29, name = "AutoAdded")
            }
            val service = testContactService(session = session, store = store, logger = logger(this, recorded))

            assertFalse(service.handleAdvertisement(ContactTestIds.RADIO, publicKey(0x29)))

            val line = recorded.single { it.message.contains("Failed to save fetched contact") }
            assertEquals(DebugLogLevel.ERROR, line.level)
            assertEquals("AdvertisementService", line.category)
            assertTrue(line.message.contains("db locked"))
        }

    @Test
    fun `known-contact advert stamp records discover-trace lines`() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val store = InMemoryContactStore()
        val radioID = ContactTestIds.RADIO
        val key = publicKey(0x21)
        store.saveContact(
            contactDto(id = ContactTestIds.fresh(), radioID = radioID, keyByte = 0x21, name = "Known"),
        )
        val service = testContactService(
            store = store,
            discoverTrace = PersistentLogger(this, category = "discover-trace", record = { recorded += it }),
        )

        assertTrue(service.handleAdvertisement(radioID, key))

        val received = recorded.single { it.message.contains("B1 0x80 ADVERT received key=") }
        val heard = recorded.single { it.message.contains("B2 0x80 known-contact hear key=") }
        assertEquals("discover-trace", received.category)
        assertEquals(DebugLogLevel.INFO, received.level)
        assertTrue(received.message.contains(key.toUppercaseHex()))
        assertTrue(heard.message.contains("isNew=false"))
    }

    @Test
    fun `known-contact advert persist fault records Advert touch retry failed`() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val inner = InMemoryContactStore()
        val radioID = ContactTestIds.RADIO
        val key = publicKey(0x21)
        inner.saveContact(
            contactDto(id = ContactTestIds.fresh(), radioID = radioID, keyByte = 0x21, name = "Known"),
        )
        val store = FlakyTouchContactStore(inner, throwOnFirstTouches = 2)
        val service = testContactService(
            store = store,
            logger = logger(this, recorded),
        )

        assertFalse(service.handleAdvertisement(radioID, key))

        val retrying = recorded.single { it.message.contains("Error handling advert event (retrying once)") }
        val failed = recorded.single { it.message.contains("Advert touch retry failed") }
        assertEquals(DebugLogLevel.ERROR, retrying.level)
        assertEquals(DebugLogLevel.ERROR, failed.level)
        assertEquals("AdvertisementService", failed.category)
        assertTrue(failed.message.contains("storeUnavailable"))
    }

    @Test
    fun `unknown advert with no device row records B2 getContact returned nil`() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val service = testContactService(
            discoverTrace = PersistentLogger(this, category = "discover-trace", record = { recorded += it }),
        )

        assertFalse(service.handleAdvertisement(ContactTestIds.RADIO, publicKey(0x2A)))

        val line = recorded.single { it.message.contains("B2 getContact returned nil") }
        assertEquals(DebugLogLevel.NOTICE, line.level)
        assertEquals("discover-trace", line.category)
    }

    @Test
    fun `unknown advert fetch throw records B2 getContact THREW`() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val session = FakeContactSession().apply {
            onGetContact = { error("radio busy") }
        }
        val service = testContactService(
            session = session,
            discoverTrace = PersistentLogger(this, category = "discover-trace", record = { recorded += it }),
        )

        val thrown = runCatching {
            service.handleAdvertisement(ContactTestIds.RADIO, publicKey(0x2C))
        }.exceptionOrNull()
        assertTrue(thrown != null)
        val line = recorded.single { it.message.contains("B2 getContact THREW") }
        assertEquals(DebugLogLevel.ERROR, line.level)
        assertEquals("discover-trace", line.category)
        assertTrue(line.message.contains("radio busy"))
    }

    private fun logger(scope: kotlinx.coroutines.CoroutineScope, recorded: MutableList<DebugLogEntryDto>) =
        PersistentLogger(
            scope = scope,
            category = "AdvertisementService",
            record = { recorded += it },
        )
}
