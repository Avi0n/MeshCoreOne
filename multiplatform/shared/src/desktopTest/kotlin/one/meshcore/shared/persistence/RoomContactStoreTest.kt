package one.meshcore.shared.persistence

import kotlinx.coroutines.runBlocking
import one.meshcore.shared.chat.ChatFixtures
import one.meshcore.shared.contact.contactFrame
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.model.toUppercaseHex
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [RoomContactStore], the production adapter `ContactService` drives. Covers the identity
 * freeze (a re-synced frame keeps the existing row id) and the per-radio conversation/blocked reads.
 */
class RoomContactStoreTest {
    private lateinit var dbFile: File
    private lateinit var db: MeshDatabase
    private lateinit var store: RoomContactStore
    private val mintedContactIds = mutableListOf<Uuid>()

    private val radioID = ChatFixtures.RADIO

    @BeforeTest
    fun setUp() {
        mintedContactIds.clear()
        dbFile = File.createTempFile("room-contact-store", ".db").also { it.delete() }
        db = meshDatabase(dbFile.absolutePath)
        store = RoomContactStore(
            db,
            newId = { ChatFixtures.freshUuid().also { mintedContactIds += it } },
        )
    }

    @AfterTest
    fun tearDown() {
        db.close()
        dbFile.delete()
    }

    @Test
    fun batchFramesReuseNewIdentityAndMatchTheWholePublicKey() = runBlocking {
        val first = contactFrame(keyByte = 0x80, name = "First").copy(flags = 1)
        val differentKey = first.copy(
            publicKey = first.publicKey.copyOf().also { it[it.lastIndex] = 0x81.toByte() },
            name = "Different key",
            flags = 0,
        )
        val updated = first.copy(name = "Updated", flags = 2, lastModified = 88)

        assertEquals(3, store.batchSaveContacts(radioID, listOf(first, differentKey, updated)))
        assertEquals(2, store.fetchContacts(radioID).size)
        val merged = assertNotNull(store.fetchContact(radioID, first.publicKey))
        assertEquals(mintedContactIds.first(), merged.id)
        assertContentEquals(first.publicKey, merged.publicKey)
        assertEquals("Updated", merged.name)
        assertEquals(3, merged.flags)
        assertTrue(merged.isFavorite)
        assertEquals(88L, merged.lastModified)
        val distinct = assertNotNull(store.fetchContact(radioID, differentKey.publicKey))
        assertEquals(mintedContactIds[1], distinct.id)
        assertEquals("Different key", distinct.name)
        assertContentEquals(differentKey.publicKey, distinct.publicKey)
        assertFalse(distinct.isFavorite)
        assertEquals(2, mintedContactIds.size)
    }

    @Test
    fun batchFramesPreserveExistingIdentityMetadataAndRadioPartition() = runBlocking {
        val first = contactFrame(keyByte = 0xFF, name = "Before").copy(flags = 1)
        val id = store.saveContact(radioID, first)
        val original = assertNotNull(store.fetchContact(id))
        store.saveContact(
            original.copy(
                nickname = "Local name",
                isBlocked = true,
                isMuted = true,
                unreadCount = 7,
                unreadMentionCount = 2,
                lastHeardTimestamp = 123,
                lastMessageDate = 456.0,
            ),
        )
        val otherRadio = ChatFixtures.freshUuid()
        val otherId = store.saveContact(otherRadio, first.copy(name = "Other radio", flags = 0))
        val update = first.copy(name = "Intermediate", flags = 2, lastModified = 77)
        assertEquals(2, store.batchSaveContacts(radioID, listOf(update, update.copy(name = "Latest"))))

        val merged = assertNotNull(store.fetchContact(radioID, first.publicKey))
        assertEquals(id, merged.id)
        assertEquals(radioID, merged.radioID)
        assertContentEquals(first.publicKey, merged.publicKey)
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
        val untouched = assertNotNull(store.fetchContact(otherId))
        assertEquals(otherRadio, untouched.radioID)
        assertEquals("Other radio", untouched.name)
        assertEquals(0, untouched.flags)
        assertFalse(untouched.isFavorite)
        assertEquals(1, store.fetchContacts(otherRadio).size)
        assertEquals(0, store.batchSaveContacts(radioID, emptyList()))
        assertEquals(id, store.fetchContact(radioID, first.publicKey)?.id)
    }

    @Test
    fun reSyncedFramePreservesTheExistingRowId() = runBlocking {
        val first = store.saveContact(radioID, contactFrame(keyByte = 0x09, name = "Alice"))
        val second = store.saveContact(radioID, contactFrame(keyByte = 0x09, name = "Alice Renamed"))

        assertEquals(first, second)
        assertEquals("Alice Renamed", store.fetchContact(first)?.name)
        assertEquals(1, store.fetchContacts(radioID).size)
    }

    @Test
    fun deleteContactCascadesMessagesReactionsRepeatsAndPendingSends() = runBlocking {
        val contactID = store.saveContact(radioID, contactFrame(keyByte = 0x09, name = "Alice"))
        seedContactData(contactID)

        store.deleteContact(contactID)

        assertNull(store.fetchContact(contactID), "contact row removed")
        assertOrphansCleared(contactID)
    }

    @Test
    fun deleteContactCascadesEvenWhenContactRowAlreadyGone() = runBlocking {
        // No contact row exists for this id; the cascade must still clear its orphaned scoped rows.
        val contactID = ChatFixtures.freshUuid()
        seedContactData(contactID)

        store.deleteContact(contactID)

        assertOrphansCleared(contactID)
    }

    @Test
    fun deleteContactsSkipsMissingAndRevivedKeysAndCascadesOnce() = runBlocking {
        val kept = store.saveContact(radioID, contactFrame(keyByte = 0x31, name = "Kept"))
        val doomed = store.saveContact(radioID, contactFrame(keyByte = 0x32, name = "Doomed"))
        seedContactData(doomed)
        val keptKey = contactFrame(keyByte = 0x31, name = "Kept").publicKey
        val doomedKey = contactFrame(keyByte = 0x32, name = "Doomed").publicKey
        val missingKey = contactFrame(keyByte = 0x33, name = "Missing").publicKey
        val deleted = store.deleteContacts(
            radioID = radioID,
            publicKeys = listOf(keptKey, doomedKey, missingKey),
            skippingPublicKeys = { setOf(keptKey.toUppercaseHex()) },
        )

        assertEquals(listOf(doomed), deleted)
        assertEquals("Kept", store.fetchContact(kept)?.name)
        assertNull(store.fetchContact(doomed))
        assertOrphansCleared(doomed)
    }

    /** Inserts a DM message plus a reaction, message repeat, and pending send scoped to [contactID]. */
    private suspend fun seedContactData(contactID: Uuid) {
        val messageID = ChatFixtures.freshUuid()
        db.messageDao().upsert(
            ChatFixtures.message(id = messageID, contactID = contactID, channelIndex = null).toEntity(),
        )
        db.reactionDao().upsert(
            ReactionEntity(
                id = ChatFixtures.freshUuid(),
                messageID = messageID,
                emoji = "👍",
                senderName = "Bob",
                messageHash = "h",
                rawText = "r",
                receivedAt = 0.0,
                radioID = radioID,
                contactID = contactID,
            ),
        )
        db.messageRepeatDao().upsert(
            MessageRepeatEntity(
                id = ChatFixtures.freshUuid(),
                messageID = messageID,
                receivedAt = 0.0,
                pathNodes = ByteArray(0),
            ),
        )
        db.pendingSendDao().upsert(
            PendingSendEntity(
                id = ChatFixtures.freshUuid(),
                radioID = radioID,
                messageID = messageID,
                kindRawValue = 0,
                isResend = false,
                messageText = "",
                messageTimestamp = 0,
                sequence = 1,
                enqueuedAt = 0.0,
            ),
        )
        this.seededMessageID = messageID
    }

    private var seededMessageID: Uuid = ChatFixtures.RADIO

    private suspend fun assertOrphansCleared(contactID: Uuid) {
        assertTrue(db.messageDao().directMessageIdsForContact(contactID).isEmpty(), "messages removed")
        assertTrue(db.reactionDao().listByMessage(seededMessageID).isEmpty(), "reactions removed")
        assertTrue(db.messageRepeatDao().listByMessage(seededMessageID).isEmpty(), "message repeats removed")
        assertFalse(db.pendingSendDao().exists(seededMessageID), "pending sends removed")
    }

    @Test
    fun deleteContactIfUnreferencedSkipsWhenMessagesExistAndDeletesWhenNone() = runBlocking {
        val withMessages = store.saveContact(radioID, contactFrame(keyByte = 0x21, name = "WithMsgs"))
        seedContactData(withMessages)
        store.deleteContactIfUnreferenced(withMessages)
        assertEquals("WithMsgs", store.fetchContact(withMessages)?.name)
        assertTrue(db.messageDao().directMessageIdsForContact(withMessages).isNotEmpty())

        val bare = store.saveContact(radioID, contactFrame(keyByte = 0x22, name = "Bare"))
        store.deleteContactIfUnreferenced(bare)
        assertNull(store.fetchContact(bare))
    }

    @Test
    fun conversationsAndBlockedReadsFilterPerRadio() = runBlocking {
        val id = store.saveContact(radioID, contactFrame(keyByte = 0x09, name = "Alice"))
        // Stamp a last-message date so the contact surfaces as a conversation.
        val withMessage = store.fetchContact(id)!!.copy(lastMessageDate = 100.0)
        store.saveContact(withMessage)

        assertEquals(listOf("Alice"), store.fetchConversations(radioID).map { it.name })

        val blockedId = store.saveContact(radioID, contactFrame(keyByte = 0x0A, name = "Blocked"))
        store.saveContact(store.fetchContact(blockedId)!!.copy(isBlocked = true))
        assertTrue(store.fetchBlockedContacts(radioID).any { it.name == "Blocked" })
    }
}
