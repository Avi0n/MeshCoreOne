package one.meshcore.shared.persistence

import kotlinx.coroutines.runBlocking
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.backup.RoomMessageDto
import one.meshcore.shared.contact.CONTACT_FLOOD_PATH_SENTINEL
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.NotificationLevel
import one.meshcore.shared.model.RemoteNodeRole
import one.meshcore.shared.model.RoomPermissionLevel
import one.meshcore.shared.model.Uuid
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises the Room-backed [RoomNodeStore] against a real database: the session lifecycle both seams
 * share, the room-message reads/writes, and the contact routing/name lookups. Proves the DTO<->entity
 * round-trip and the Kotlin-side prefix/dup filtering reproduce `InMemoryRoomStore`'s semantics.
 */
class RoomNodeStoreTest {
    private lateinit var dbFile: File
    private lateinit var db: MeshDatabase
    private lateinit var store: RoomNodeStore

    private val radioID = Uuid.normalized("A0000000-0000-0000-0000-0000000000AA")

    @BeforeTest
    fun setUp() {
        dbFile = File.createTempFile("room-node-store", ".db").also { it.delete() }
        db = meshDatabase(dbFile.absolutePath)
        store = RoomNodeStore(radioID, db.remoteNodeSessionDao(), db.roomMessageDao(), db.contactDao()) { 42.0 }
    }

    @AfterTest
    fun tearDown() {
        db.close()
        dbFile.delete()
    }

    @Test
    fun sessionRoundTripsAndResolvesByKeyAndPrefix() = runBlocking {
        val session = session(publicKey = key(0x11))
        store.saveSession(session)

        assertEquals(session.id, store.fetchSessionById(session.id)?.id)
        assertEquals(session.id, store.fetchSessionByPublicKey(key(0x11))?.id)
        assertEquals(session.id, store.fetchSessionByPrefix(key(0x11).copyOf(6))?.id)
        assertNull(store.fetchSessionByPublicKey(key(0x22)))
        assertEquals(listOf(session.id), store.fetchSessionsByRadioId(radioID).map { it.id })
    }

    @Test
    fun connectionStateAndCleanupAndDelete() = runBlocking {
        val a = session(publicKey = key(0x11))
        val b = session(publicKey = key(0x11)) // same key, different id — a duplicate to clean up
        store.saveSession(a)
        store.saveSession(b)

        store.updateSessionConnection(a.id, isConnected = true, permissionLevel = RoomPermissionLevel.ADMIN)
        assertEquals(RoomPermissionLevel.ADMIN, store.fetchSessionById(a.id)?.permissionLevel)
        assertEquals(listOf(a.id), store.fetchConnectedSessions().map { it.id })
        assertEquals(42.0, store.fetchSessionById(a.id)?.lastConnectedDate)

        store.markSessionDisconnected(a.id)
        assertFalse(store.fetchSessionById(a.id)!!.isConnected)
        assertEquals(42.0, store.fetchSessionById(a.id)?.lastConnectedDate)

        store.cleanupDuplicateSessions(key(0x11), keepID = a.id)
        assertNull(store.fetchSessionById(b.id))
        assertEquals(a.id, store.fetchSessionById(a.id)?.id)

        store.deleteSession(a.id)
        assertNull(store.fetchSessionById(a.id))
    }

    @Test
    fun deleteSessionRemovesRoomMessagesForThatSession() = runBlocking {
        val keep = session(publicKey = key(0x11))
        val drop = session(publicKey = key(0x22)).copy(
            id = Uuid.normalized("E0000000-0000-0000-0000-000000000022"),
        )
        store.saveSession(keep)
        store.saveSession(drop)
        store.saveRoomMessage(message(keep.id, id = 1, timestamp = 100, dedup = "keep"))
        store.saveRoomMessage(message(drop.id, id = 2, timestamp = 200, dedup = "drop"))

        store.deleteSession(drop.id)

        assertNull(store.fetchSessionById(drop.id))
        assertTrue(store.fetchRoomMessages(drop.id).isEmpty())
        assertEquals(
            listOf(Uuid.normalized("D0000000-0000-0000-0000-000000000001")),
            store.fetchRoomMessages(keep.id).map {
                it.id
            },
        )
    }

    @Test
    fun cleanupDuplicateSessionsRemovesMessagesForDroppedSessions() = runBlocking {
        val keep = session(publicKey = key(0x11))
        val drop = session(publicKey = key(0x11)).copy(
            id = Uuid.normalized("E0000000-0000-0000-0000-000000000033"),
        )
        store.saveSession(keep)
        store.saveSession(drop)
        store.saveRoomMessage(message(keep.id, id = 3, timestamp = 100, dedup = "keep"))
        store.saveRoomMessage(message(drop.id, id = 4, timestamp = 200, dedup = "drop"))

        store.cleanupDuplicateSessions(key(0x11), keepID = keep.id)

        assertNull(store.fetchSessionById(drop.id))
        assertTrue(store.fetchRoomMessages(drop.id).isEmpty())
        assertEquals(1, store.fetchRoomMessages(keep.id).size)
    }

    @Test
    fun markRoomSessionConnectedTransitionsOnce() = runBlocking {
        val session = session(publicKey = key(0x33))
        store.saveSession(session)
        assertTrue(store.markRoomSessionConnected(session.id)) // disconnected -> connected
        assertEquals(42.0, store.fetchSessionById(session.id)?.lastConnectedDate)
        assertFalse(store.markRoomSessionConnected(session.id)) // already connected
        assertFalse(store.markRoomSessionConnected(Uuid.normalized("B0000000-0000-0000-0000-000000000001")))
    }

    @Test
    fun resetAllRemoteNodeSessionConnectionsClearsConnectedFlag() = runBlocking {
        val a = session(publicKey = key(0x51))
        val b = session(publicKey = key(0x52)).copy(id = Uuid.normalized("D0000000-0000-0000-0000-000000000052"))
        store.saveSession(a)
        store.saveSession(b)
        store.updateSessionConnection(a.id, isConnected = true, permissionLevel = RoomPermissionLevel.ADMIN)
        store.updateSessionConnection(b.id, isConnected = true, permissionLevel = RoomPermissionLevel.GUEST)
        assertEquals(2, store.fetchConnectedSessions().size)

        resetAllRemoteNodeSessionConnections(db)

        assertTrue(store.fetchConnectedSessions().isEmpty())
        assertFalse(store.fetchSessionById(a.id)!!.isConnected)
        assertFalse(store.fetchSessionById(b.id)!!.isConnected)
    }

    @Test
    fun roomActivityAndUnreadCounters() = runBlocking {
        val session = session(publicKey = key(0x44))
        store.saveSession(session)

        store.updateRoomActivity(session.id, syncTimestamp = 900L)
        store.fetchSessionById(session.id)!!.let {
            assertEquals(42.0, it.lastMessageDate)
            assertEquals(900L, it.lastSyncTimestamp)
        }
        store.updateRoomActivity(session.id, syncTimestamp = 2000L)
        assertEquals(2000L, store.fetchSessionById(session.id)!!.lastSyncTimestamp)
        store.updateRoomActivity(session.id, syncTimestamp = 1500L)
        assertEquals(2000L, store.fetchSessionById(session.id)!!.lastSyncTimestamp)
        store.updateRoomActivity(session.id)
        assertEquals(2000L, store.fetchSessionById(session.id)!!.lastSyncTimestamp)
        store.incrementRoomUnreadCount(session.id)
        store.incrementRoomUnreadCount(session.id)
        assertEquals(2, store.fetchSessionById(session.id)?.unreadCount)
        store.resetRoomUnreadCount(session.id)
        assertEquals(0, store.fetchSessionById(session.id)?.unreadCount)
    }

    @Test
    fun roomMessagesSaveFetchDedupAndStatus() = runBlocking {
        // A room message always belongs to a saved session; the by-id read resolves it through that row.
        val session = session(publicKey = key(0x99))
        store.saveSession(session)
        val sessionID = session.id
        val older = message(sessionID, id = 1, timestamp = 100, dedup = "a")
        val newer = message(sessionID, id = 2, timestamp = 200, dedup = "b")
        store.saveRoomMessage(newer)
        store.saveRoomMessage(older)

        assertEquals(listOf(older.id, newer.id), store.fetchRoomMessages(sessionID).map { it.id })
        assertEquals(older.id, store.fetchRoomMessage(older.id)?.id)
        assertTrue(store.isDuplicateRoomMessage(sessionID, "a"))
        assertFalse(store.isDuplicateRoomMessage(sessionID, "z"))

        store.updateRoomMessageStatus(older.id, MessageStatus.DELIVERED, ackCode = 7L, roundTripTime = 3L)
        store.fetchRoomMessage(older.id)!!.let {
            assertEquals(MessageStatus.DELIVERED.raw, it.statusRawValue)
            assertEquals(7L, it.ackCode)
        }
        store.updateRoomMessageRetryStatus(newer.id, MessageStatus.FAILED, retryAttempt = 2, maxRetryAttempts = 3)
        store.fetchRoomMessage(newer.id)!!.let {
            assertEquals(2, it.retryAttempt)
            assertEquals(3, it.maxRetryAttempts)
            assertEquals(false, it.failureSeen)
        }
    }

    @Test
    fun markFailedSendsSeenDropsOnlyThatRoomSession() = runBlocking {
        val seen = session(publicKey = key(0xAA))
        val other = session(publicKey = key(0xBB))
        store.saveSession(seen)
        store.saveSession(other)
        val seenFail = message(seen.id, id = 3, timestamp = 10, dedup = "s", fromSelf = true)
            .copy(statusRawValue = MessageStatus.FAILED.raw)
        val otherFail = message(other.id, id = 4, timestamp = 11, dedup = "o", fromSelf = true)
            .copy(statusRawValue = MessageStatus.FAILED.raw)
        store.saveRoomMessage(seenFail)
        store.saveRoomMessage(otherFail)

        store.markFailedSendsSeen(seen.id)

        assertTrue(store.fetchRoomMessage(seenFail.id)!!.failureSeen)
        assertFalse(store.fetchRoomMessage(otherFail.id)!!.failureSeen)
    }

    @Test
    fun transitionToFailedFromSentClearsRoomFailureSeen() = runBlocking {
        val session = session(publicKey = key(0xCC))
        store.saveSession(session)
        val sent = message(session.id, id = 5, timestamp = 10, dedup = "t", fromSelf = true)
            .copy(statusRawValue = MessageStatus.SENT.raw, failureSeen = true)
        store.saveRoomMessage(sent)

        store.updateRoomMessageStatus(sent.id, MessageStatus.FAILED, ackCode = null, roundTripTime = null)

        assertFalse(store.fetchRoomMessage(sent.id)!!.failureSeen)
    }

    @Test
    fun contactRoutingAndNameLookup() = runBlocking {
        db.contactDao().upsert(contact(publicKey = key(0x55), outPathLength = 3, name = "Repeater A"))
        db.contactDao().upsert(contact(publicKey = key(0x66), outPathLength = CONTACT_FLOOD_PATH_SENTINEL, name = "B"))

        assertEquals(false, store.contactFloodRouted(radioID, key(0x55)))
        assertEquals(true, store.contactFloodRouted(radioID, key(0x66)))
        assertNull(store.contactFloodRouted(radioID, key(0x77)))
        assertEquals("Repeater A", store.findContactNameByKeyPrefix(key(0x55).copyOf(6)))
        assertNull(store.findContactNameByKeyPrefix(key(0x77).copyOf(6)))
    }

    private fun key(byte: Int) = ByteArray(32) { byte.toByte() }

    private fun session(publicKey: ByteArray) = RemoteNodeSessionDto(
        id = Uuid.random(),
        radioID = radioID,
        publicKey = publicKey,
        name = "Node",
        role = RemoteNodeRole.REPEATER,
        latitude = 0.0,
        longitude = 0.0,
        isConnected = false,
        permissionLevel = RoomPermissionLevel.GUEST,
        unreadCount = 0,
        notificationLevel = NotificationLevel.ALL,
        isPinned = false,
        neighborCount = 0,
        lastSyncTimestamp = 0,
    )

    private fun message(sessionID: Uuid, id: Int, timestamp: Long, dedup: String, fromSelf: Boolean = false) =
        RoomMessageDto(
            id = Uuid.normalized("D0000000-0000-0000-0000-00000000000$id"),
            sessionID = sessionID,
            authorKeyPrefix = ByteArray(4) { 1 },
            text = "hi",
            timestamp = timestamp,
            createdAt = timestamp.toDouble(),
            isFromSelf = fromSelf,
            deduplicationKey = dedup,
            statusRawValue = MessageStatus.SENT.raw,
            retryAttempt = 0,
            maxRetryAttempts = 0,
        )

    private fun contact(publicKey: ByteArray, outPathLength: Int, name: String) = ContactEntity(
        id = Uuid.random(),
        radioID = radioID,
        publicKey = publicKey,
        name = name,
        typeRawValue = ContactType.REPEATER.raw,
        flags = 0,
        outPathLength = outPathLength,
        outPath = ByteArray(0),
        lastAdvertTimestamp = 0,
        latitude = 0.0,
        longitude = 0.0,
        lastModified = 0,
        isBlocked = false,
        isMuted = false,
        isFavorite = false,
        unreadCount = 0,
    )
}
