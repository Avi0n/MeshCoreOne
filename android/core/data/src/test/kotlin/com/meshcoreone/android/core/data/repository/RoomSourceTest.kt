// PortedFrom: MC1Services/Tests/MC1ServicesTests/PersistenceStoreTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.data.repository

import androidx.room.Room
import com.meshcoreone.android.core.database.MeshCoreDatabase
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RoomSourceTest : RepositoryTest() {
    @Test
    fun moduleClosesDatabaseWhenOwnershipWasNeverTransferred() {
        val untransferred = Room.inMemoryDatabaseBuilder(context, MeshCoreDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        untransferred.openHelper.writableDatabase
        assertTrue(untransferred.isOpen)
        RoomPersistenceStore.closeUntransferredDatabase(untransferred)
        assertFalse(untransferred.isOpen)
    }

    @Test @OriginalCase("PersistenceStoreTests::Save and fetch channels()")
    @OriginalCase("PersistenceStoreTests::Save and fetch remote node session()")
    fun channelsSortBySlotAndSessionsPreserveTheirRole() = runTest {
        store.saveChannel(RADIO_A, ChannelInfo(1u, "Private", Bytes(ByteArray(16) { 0x42 })))
        store.saveChannel(RADIO_A, ChannelInfo(0u, "Public", Bytes(ByteArray(16))))
        assertEquals(listOf(0.toUByte(), 1.toUByte()), store.fetchChannels(RADIO_A).map { it.index })
        assertEquals(listOf("Public", "Private"), store.fetchChannels(RADIO_A).map { it.name })
        val s = session()
        store.saveRemoteNodeSessionDTO(s)
        val fetched = assertNotNull(store.fetchRemoteNodeSession(entity(id = s.id)))
        assertEquals("TestRoom", fetched.name)
        assertEquals(RemoteNodeRole.ROOM_SERVER, fetched.role)
    }

    @Test @OriginalCase("PersistenceStoreTests::Update room activity advances sync timestamp and sets lastMessageDate()")
    @OriginalCase("PersistenceStoreTests::Update room activity ignores older sync timestamps()")
    @OriginalCase("PersistenceStoreTests::Update room activity without sync timestamp does not change lastSyncTimestamp()")
    fun senderBookmarkIsMonotonicButEveryActivityUsesThePhoneDate() = runTest {
        val s = session()
        store.saveRemoteNodeSessionDTO(s)
        val k = entity(id = s.id)
        store.updateRoomActivity(k, 1000u)
        assertEquals(1000u, assertNotNull(store.fetchRemoteNodeSession(k)).lastSyncTimestamp)
        assertEquals(AT, assertNotNull(store.fetchRemoteNodeSession(k)).lastMessageDate)
        clock.now = AT.plusNanos(1)
        store.updateRoomActivity(k, 2000u)
        assertEquals(2000u, assertNotNull(store.fetchRemoteNodeSession(k)).lastSyncTimestamp)
        assertEquals(clock.now, assertNotNull(store.fetchRemoteNodeSession(k)).lastMessageDate)
        clock.now = AT.plusNanos(2)
        store.updateRoomActivity(k, 500u)
        assertEquals(2000u, assertNotNull(store.fetchRemoteNodeSession(k)).lastSyncTimestamp)
        assertEquals(clock.now, assertNotNull(store.fetchRemoteNodeSession(k)).lastMessageDate)
        clock.now = AT.plusNanos(3)
        store.updateRoomActivity(k)
        assertEquals(2000u, assertNotNull(store.fetchRemoteNodeSession(k)).lastSyncTimestamp)
        assertEquals(clock.now, assertNotNull(store.fetchRemoteNodeSession(k)).lastMessageDate)
    }

    @Test @OriginalCase("PersistenceStoreTests::Mark room session connected changes isConnected and returns true()")
    @OriginalCase("PersistenceStoreTests::Mark room session connected returns false when already connected()")
    @OriginalCase("PersistenceStoreTests::Mark session disconnected preserves permission level()")
    @OriginalCase("PersistenceStoreTests::Mark session disconnected is no-op when already disconnected()")
    @OriginalCase("PersistenceStoreTests::Disconnect then recover preserves permission level()")
    @OriginalCase("PersistenceStoreTests::Update remote node session connection can reset permission to guest()")
    fun transientDisconnectAndExplicitLogoutHaveDifferentPermissionSemantics() = runTest {
        val s = session().copy(permissionLevel = RoomPermissionLevel.ADMIN)
        store.saveRemoteNodeSessionDTO(s)
        val k = entity(id = s.id)
        assertTrue(store.markRoomSessionConnected(k))
        assertFalse(store.markRoomSessionConnected(k))
        assertEquals(RoomPermissionLevel.ADMIN, assertNotNull(store.fetchRemoteNodeSession(k)).permissionLevel)
        store.markSessionDisconnected(k)
        store.markSessionDisconnected(k)
        assertFalse(assertNotNull(store.fetchRemoteNodeSession(k)).isConnected)
        assertEquals(RoomPermissionLevel.ADMIN, assertNotNull(store.fetchRemoteNodeSession(k)).permissionLevel)
        assertTrue(store.markRoomSessionConnected(k))
        assertEquals(RoomPermissionLevel.ADMIN, assertNotNull(store.fetchRemoteNodeSession(k)).permissionLevel)
        store.updateRemoteNodeSessionConnection(k, false, RoomPermissionLevel.GUEST)
        assertEquals(RoomPermissionLevel.GUEST, assertNotNull(store.fetchRemoteNodeSession(k)).permissionLevel)
    }

    @Test @OriginalCase("PersistenceStoreTests::Save and fetch room messages()")
    @OriginalCase("PersistenceStoreTests::Room messages tied on timestamp order deterministically by createdAt()")
    @OriginalCase("PersistenceStoreTests::Room messages order primarily by wire timestamp()")
    fun roomOrderingUsesWireSecondsThenCompleteArrivalDate() = runTest {
        val s = session()
        store.saveRemoteNodeSessionDTO(s)
        for ((text, offset) in listOf("second" to 1L, "third" to 2L, "first" to 0L)) {
            store.saveRoomMessage(RADIO_A, RoomMessageDTO(sessionID = s.id, authorKeyPrefix = Bytes.of(1, 2, 3, 4),
                text = text, timestamp = 42u, createdAt = AT.plusNanos(offset)))
        }
        assertEquals(listOf("first", "second", "third"), store.fetchRoomMessages(entity(id = s.id)).map { it.text })
        val other = session(publicKey = key(2))
        store.saveRemoteNodeSessionDTO(other)
        for ((text, timestamp, arrival) in listOf(Triple("newest", 102u, 0L), Triple("oldest", 100u, 2L), Triple("middle", 101u, 1L))) {
            store.saveRoomMessage(RADIO_A, RoomMessageDTO(sessionID = other.id, authorKeyPrefix = Bytes.of(1, 2, 3, 4),
                text = text, timestamp = timestamp, createdAt = AT.plusSeconds(arrival)))
        }
        assertEquals(listOf("oldest", "middle", "newest"), store.fetchRoomMessages(entity(id = other.id)).map { it.text })
    }

    @Test @OriginalCase("PersistenceStoreTests::Room message deduplication()")
    fun roomDedupUsesSessionAndRadioNotJustContent() = runTest {
        val s = session()
        store.saveRemoteNodeSessionDTO(s)
        val first = RoomMessageDTO(sessionID = s.id, authorKeyPrefix = Bytes.of(1, 2, 3, 4),
            text = "Duplicate message", timestamp = 42u, createdAt = AT)
        store.saveRoomMessage(RADIO_A, first)
        store.saveRoomMessage(RADIO_A, first.copy(id = java.util.UUID.randomUUID()))
        assertEquals(listOf(first), store.fetchRoomMessages(entity(id = s.id)))
        store.saveRoomMessage(RADIO_B, first)
        assertEquals(listOf(first), store.fetchRoomMessages(entity(RADIO_B, s.id)))
    }

    @Test @OriginalCase("PersistenceStoreTests::Cleanup duplicate remote node sessions keeps target and deletes others()")
    fun duplicateCleanupDeletesOnlyTheKeptRadiosExtraSessionsAndTheirMessages() = runTest {
        val keep = session()
        val duplicate = session()
        val otherRadio = duplicate.copy(radioId = RADIO_B)
        for (s in listOf(keep, duplicate, otherRadio)) store.saveRemoteNodeSessionDTO(s)
        val m = RoomMessageDTO(sessionID = duplicate.id, authorKeyPrefix = Bytes.of(1, 2, 3, 4),
            authorName = "Author", text = "Message on duplicate", timestamp = 42u, createdAt = AT)
        store.saveRoomMessage(RADIO_A, m)
        store.saveRoomMessage(RADIO_B, m)
        store.cleanupDuplicateRemoteNodeSessions(keep.publicKey, entity(id = keep.id))
        assertEquals("TestRoom", assertNotNull(store.fetchRemoteNodeSession(entity(id = keep.id))).name)
        assertNull(store.fetchRemoteNodeSession(entity(id = duplicate.id)))
        assertTrue(store.fetchRoomMessages(entity(id = duplicate.id)).isEmpty())
        assertNotNull(store.fetchRemoteNodeSession(entity(RADIO_B, duplicate.id)))
        assertEquals(listOf(m), store.fetchRoomMessages(entity(RADIO_B, duplicate.id)))
    }

    @Test @OriginalCase("PersistenceStoreTests::Get total unread counts()")
    @OriginalCase("PersistenceStoreTests::Get total unread counts excludes repeater-role sessions()")
    @OriginalCase("PersistenceStoreTests::Notification levels affect badge count correctly()")
    fun totalsKeepContactChannelAndRoomPreferenceFamiliesSeparate() = runTest {
        store.saveContact(contact().copy(unreadCount = 2))
        store.saveContact(contact(id = java.util.UUID.randomUUID(), publicKey = key(2)).copy(unreadCount = 1))
        val channel = store.saveChannel(RADIO_A, ChannelInfo(0u, "Public", Bytes(ByteArray(16))))
        repeat(3) { store.incrementChannelUnreadCount(entity(id = channel)) }
        assertEquals(3L, store.getTotalUnreadCounts(RADIO_A).contacts)
        assertEquals(3L, store.getTotalUnreadCounts(RADIO_A).channels)
        assertEquals(0L, store.getTotalUnreadCounts(RADIO_A).rooms)
        val room = session().copy(unreadCount = 2)
        store.saveRemoteNodeSessionDTO(room)
        store.saveRemoteNodeSessionDTO(session(publicKey = key(2)).copy(role = RemoteNodeRole.REPEATER, unreadCount = 3))
        assertEquals(2L, store.getTotalUnreadCounts(RADIO_A).rooms)
        store.setChannelNotificationLevel(entity(id = channel), NotificationLevel.MUTED)
        assertEquals(0L, store.getTotalUnreadCounts(RADIO_A).channels)
        store.setChannelNotificationLevel(entity(id = channel), NotificationLevel.MENTIONS_ONLY)
        assertEquals(0L, store.getTotalUnreadCounts(RADIO_A).channels)
        store.incrementChannelUnreadMentionCount(entity(id = channel))
        assertEquals(1L, store.getTotalUnreadCounts(RADIO_A).channels)
        assertEquals(0L, store.getTotalUnreadCounts(RADIO_B).contacts)
        assertEquals(0L, store.getTotalUnreadCounts(RADIO_B).channels)
        assertEquals(0L, store.getTotalUnreadCounts(RADIO_B).rooms)
    }
}
