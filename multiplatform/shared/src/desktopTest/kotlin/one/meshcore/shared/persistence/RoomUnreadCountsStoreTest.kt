package one.meshcore.shared.persistence

import kotlinx.coroutines.runBlocking
import one.meshcore.shared.chat.ChatFixtures
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.NotificationLevel
import one.meshcore.shared.model.RemoteNodeRole
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.notification.UnreadCounts
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit tests for [RoomUnreadCountsStore], the badge's unread source. Locks the exclusions the Swift
 * `getTotalUnreadCounts` applies — muted, blocked, repeater, and non-room sessions never reach the badge —
 * and the mentions-only channel rule, against a real in-memory Room database.
 */
class RoomUnreadCountsStoreTest {
    private lateinit var dbFile: File
    private lateinit var db: MeshDatabase
    private lateinit var store: RoomUnreadCountsStore

    private val radioID = ChatFixtures.RADIO
    private val otherRadioID = Uuid.normalized("D0000000-0000-0000-0000-0000000000FF")

    @BeforeTest
    fun setUp() {
        dbFile = File.createTempFile("room-unread-counts", ".db").also { it.delete() }
        db = meshDatabase(dbFile.absolutePath)
        store = RoomUnreadCountsStore(db)
    }

    @AfterTest
    fun tearDown() {
        db.close()
        dbFile.delete()
    }

    @Test
    fun everyReachableUnreadRowIsCountedOncePerCategory() = runBlocking {
        db.contactDao().upsert(ChatFixtures.contact(name = "Alice", unreadCount = 3).toEntity())
        db.channelDao().upsert(channel(index = 1, unreadCount = 5))
        db.remoteNodeSessionDao().upsert(session(name = "Room", unreadCount = 7))

        assertEquals(UnreadCounts(contacts = 3, channels = 5, rooms = 7), store.totals(radioID))
    }

    @Test
    fun mutedBlockedAndRepeaterRowsNeverReachTheBadge() = runBlocking {
        db.contactDao().upsert(ChatFixtures.contact(name = "Muted", unreadCount = 4, isMuted = true).toEntity())
        db.contactDao().upsert(ChatFixtures.contact(name = "Blocked", unreadCount = 4, isBlocked = true).toEntity())
        db.contactDao().upsert(
            ChatFixtures.contact(name = "Repeater", unreadCount = 4).toEntity()
                .copy(typeRawValue = ContactType.REPEATER.raw),
        )
        db.channelDao().upsert(channel(index = 1, unreadCount = 6, level = NotificationLevel.MUTED))
        db.remoteNodeSessionDao().upsert(session(name = "Muted room", unreadCount = 8, level = NotificationLevel.MUTED))
        db.remoteNodeSessionDao().upsert(
            session(name = "Repeater", unreadCount = 8).copy(roleRawValue = RemoteNodeRole.REPEATER.raw),
        )

        assertEquals(UnreadCounts.Zero, store.totals(radioID))
    }

    @Test
    fun aMentionsOnlyChannelContributesItsMentionCountOnly() = runBlocking {
        db.channelDao().upsert(
            channel(
                index = 1,
                unreadCount = 9,
                mentionCount = 2,
                level = NotificationLevel.MENTIONS_ONLY,
            ),
        )

        assertEquals(2, store.totals(radioID).channels)
    }

    @Test
    fun anotherRadiosUnreadRowsAreOutsideThisPartition() = runBlocking {
        db.contactDao().upsert(
            ChatFixtures.contact(name = "Foreign", unreadCount = 3).toEntity().copy(radioID = otherRadioID),
        )
        db.channelDao().upsert(channel(index = 1, unreadCount = 5).copy(radioID = otherRadioID))

        assertEquals(UnreadCounts.Zero, store.totals(radioID))
    }

    private fun channel(
        index: Int,
        unreadCount: Int,
        mentionCount: Int = 0,
        level: NotificationLevel = NotificationLevel.ALL,
    ) = ChannelEntity(
        id = ChatFixtures.freshUuid(),
        radioID = radioID,
        index = index,
        name = "ch$index",
        secret = ByteArray(CHANNEL_SECRET_BYTES),
        isEnabled = true,
        unreadCount = unreadCount,
        unreadMentionCount = mentionCount,
        notificationLevelRawValue = level.raw,
    )

    private fun session(name: String, unreadCount: Int, level: NotificationLevel = NotificationLevel.ALL) =
        RemoteNodeSessionEntity(
            id = ChatFixtures.freshUuid(),
            radioID = radioID,
            publicKey = ByteArray(PUBLIC_KEY_BYTES),
            name = name,
            roleRawValue = RemoteNodeRole.ROOM_SERVER.raw,
            latitude = 0.0,
            longitude = 0.0,
            isConnected = false,
            permissionLevelRawValue = 0,
            unreadCount = unreadCount,
            notificationLevelRawValue = level.raw,
        )

    private companion object {
        const val CHANNEL_SECRET_BYTES = 16
        const val PUBLIC_KEY_BYTES = 32
    }
}
