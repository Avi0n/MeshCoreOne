package one.meshcore.shared.persistence

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import one.meshcore.shared.contact.ContactFrame
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Contact batch identity and local metadata against Android's actual Room database. */
@RunWith(AndroidJUnit4::class)
@Suppress("MagicNumber")
class RoomContactBatchInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseName = "contact-batch-${Uuid.random().value}.db"
    private val radioID = Uuid.random()
    private val mintedIds = mutableListOf<Uuid>()
    private lateinit var database: MeshDatabase
    private lateinit var store: RoomContactStore

    @Before
    fun setUp() {
        database = meshDatabase(context, Dispatchers.IO, databaseName)
        store = RoomContactStore(
            database,
            newId = { Uuid.random().also { mintedIds += it } },
        )
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun repeatedNewKeysReuseFirstIdentityAndMatchWholeKeys() = runBlocking {
        val first = frame(ByteArray(32) { 0x80.toByte() }, "First", flags = 1)
        val differentKey = first.copy(
            publicKey = first.publicKey.copyOf().also { it[it.lastIndex] = 0x81.toByte() },
            name = "Different key",
            flags = 0,
        )
        val updated = first.copy(name = "Updated", flags = 2, lastModified = 88)

        assertEquals(3, store.batchSaveContacts(radioID, listOf(first, differentKey, updated)))
        assertEquals(2, store.fetchContacts(radioID).size)
        val merged = requireNotNull(store.fetchContact(radioID, first.publicKey))
        assertEquals(mintedIds.first(), merged.id)
        assertArrayEquals(first.publicKey, merged.publicKey)
        assertEquals("Updated", merged.name)
        assertEquals(3, merged.flags)
        assertTrue(merged.isFavorite)
        assertEquals(88L, merged.lastModified)
        val distinct = requireNotNull(store.fetchContact(radioID, differentKey.publicKey))
        assertEquals(mintedIds[1], distinct.id)
        assertEquals("Different key", distinct.name)
        assertArrayEquals(differentKey.publicKey, distinct.publicKey)
        assertFalse(distinct.isFavorite)
        assertEquals(2, mintedIds.size)
    }

    @Test
    fun repeatedExistingKeysPreserveMetadataAndRadioPartition() = runBlocking {
        val first = frame(ByteArray(32) { 0xFF.toByte() }, "Before", flags = 1)
        val id = store.saveContact(radioID, first)
        store.saveContact(
            requireNotNull(store.fetchContact(id)).copy(
                nickname = "Local name",
                isBlocked = true,
                isMuted = true,
                unreadCount = 7,
                unreadMentionCount = 2,
                lastHeardTimestamp = 123,
                lastMessageDate = 456.0,
            ),
        )
        val otherRadio = Uuid.random()
        val otherId = store.saveContact(otherRadio, first.copy(name = "Other radio", flags = 0))
        val update = first.copy(name = "Intermediate", flags = 2, lastModified = 77)
        assertEquals(2, store.batchSaveContacts(radioID, listOf(update, update.copy(name = "Latest"))))

        val merged = requireNotNull(store.fetchContact(radioID, first.publicKey))
        assertEquals(id, merged.id)
        assertEquals(radioID, merged.radioID)
        assertArrayEquals(first.publicKey, merged.publicKey)
        assertEquals("Latest", merged.name)
        assertEquals(3, merged.flags)
        assertEquals(77L, merged.lastModified)
        assertEquals("Local name", merged.nickname)
        assertTrue(merged.isFavorite)
        assertTrue(merged.isBlocked)
        assertTrue(merged.isMuted)
        assertEquals(7, merged.unreadCount)
        assertEquals(2, merged.unreadMentionCount)
        assertEquals(123L, merged.lastHeardTimestamp)
        assertEquals(456.0, merged.lastMessageDate)
        assertEquals(1, store.fetchContacts(radioID).size)
        val untouched = requireNotNull(store.fetchContact(otherId))
        assertEquals(otherRadio, untouched.radioID)
        assertEquals("Other radio", untouched.name)
        assertEquals(0, untouched.flags)
        assertFalse(untouched.isFavorite)
        assertEquals(1, store.fetchContacts(otherRadio).size)
        assertEquals(0, store.batchSaveContacts(radioID, emptyList()))
        assertEquals(id, store.fetchContact(radioID, first.publicKey)?.id)
    }

    private fun frame(publicKey: ByteArray, name: String, flags: Int) = ContactFrame(
        publicKey = publicKey,
        type = ContactType.CHAT,
        flags = flags,
        outPathLength = 0,
        outPath = ByteArray(0),
        name = name,
        lastAdvertTimestamp = 0,
        latitude = 0.0,
        longitude = 0.0,
        lastModified = 0,
    )

    @Test
    fun chatPinSurvivesRadioSyncAndDatabaseReopenIndependentlyOfFavorite() = runBlocking {
        val advert = frame(ByteArray(32) { 0x12 }, "Alice", flags = 0)
        val id = store.saveContact(radioID, advert)
        val list = RoomConversationListStore(radioID, database.conversationDaos())
        list.setContactPinned(id, true)
        store.batchSaveContacts(radioID, listOf(advert.copy(name = "Updated")))
        val pinned = requireNotNull(store.fetchContact(id))
        assertTrue(pinned.isPinned)
        assertFalse(pinned.isFavorite)

        store.saveContact(pinned.copy(isFavorite = true, isPinned = false))
        assertTrue(requireNotNull(store.fetchContact(id)).isPinned)
        list.setContactPinned(id, false)
        database.close()
        database = meshDatabase(context, Dispatchers.IO, databaseName)
        val reopened = requireNotNull(database.contactDao().getById(id))
        assertFalse(reopened.isPinned)
        assertTrue(reopened.isFavorite)
        assertEquals("Updated", reopened.name)
    }
}
