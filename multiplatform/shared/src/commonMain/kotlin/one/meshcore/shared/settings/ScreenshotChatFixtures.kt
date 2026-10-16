package one.meshcore.shared.settings

import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.backup.RoomMessageDto
import one.meshcore.shared.chat.text.MentionUtilities
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.NotificationLevel
import one.meshcore.shared.model.RemoteNodeRole
import one.meshcore.shared.model.RoomPermissionLevel
import one.meshcore.shared.model.TextType
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.persistence.toEntity

/**
 * Deterministic Room rows for screenshot / demo mode when no radio is connected. Unlocks the chats
 * tab, channel thread, message actions, channel info, send-DM sheet, mention picker (ambiguous
 * Alice match), and room conversation list entry without BLE hardware.
 *
 * Idempotent: re-running [seed] upserts the same primary keys.
 */
object ScreenshotChatFixtures {
    val RADIO_ID: Uuid = Uuid.normalized("D0000000-0000-0000-0000-0000000000DE")
    val CHANNEL_ID: Uuid = Uuid.normalized("C0000000-0000-0000-0000-000000000001")
    val ALICE_A_ID: Uuid = Uuid.normalized("A0000000-0000-0000-0000-0000000000A1")
    val ALICE_B_ID: Uuid = Uuid.normalized("A0000000-0000-0000-0000-0000000000A2")
    val BOB_ID: Uuid = Uuid.normalized("B0000000-0000-0000-0000-0000000000B1")
    val ROOM_CONTACT_ID: Uuid = Uuid.normalized("R0000000-0000-0000-0000-0000000000C1")
    val ROOM_SESSION_ID: Uuid = Uuid.normalized("R0000000-0000-0000-0000-0000000000S1")
    val REPEATER_CONTACT_ID: Uuid = Uuid.normalized("E0000000-0000-0000-0000-0000000000E1")
    val CHANNEL_MSG_ID: Uuid = Uuid.normalized("M0000000-0000-0000-0000-0000000000C1")
    val CHANNEL_MENTION_MSG_ID: Uuid = Uuid.normalized("M0000000-0000-0000-0000-0000000000C2")
    val CHANNEL_MAP_MSG_ID: Uuid = Uuid.normalized("M0000000-0000-0000-0000-0000000000C3")
    val DM_MSG_ID: Uuid = Uuid.normalized("M0000000-0000-0000-0000-0000000000D1")
    val ROOM_MSG_ID: Uuid = Uuid.normalized("M0000000-0000-0000-0000-0000000000R1")

    const val CHANNEL_INDEX: Int = 0
    const val CHANNEL_NAME: String = "Public"
    const val NODE_NAME: String = "Demo Node"
    const val ALICE_NAME: String = "Alice"
    const val BOB_NAME: String = "Bob"
    const val ROOM_NAME: String = "Ops Room"
    const val REPEATER_NAME: String = "Hilltop Rpt"

    /** Epoch seconds used as the message clock base for fixture rows. */
    const val BASE_EPOCH: Double = 1_720_000_000.0

    private const val PUBLIC_KEY_LENGTH: Int = 32

    /** Coordinate text that [ChatCoordinateDetector] turns into a chat map preview. */
    const val MAP_PREVIEW_MESSAGE_TEXT: String = "RV at 37.7749, -122.4194"

    fun alicePublicKeyA(): ByteArray = ByteArray(PUBLIC_KEY_LENGTH) { 0xA1.toByte() }
    fun alicePublicKeyB(): ByteArray = ByteArray(PUBLIC_KEY_LENGTH) { 0xA2.toByte() }
    fun bobPublicKey(): ByteArray = ByteArray(PUBLIC_KEY_LENGTH) { 0xB1.toByte() }
    fun roomPublicKey(): ByteArray = ByteArray(PUBLIC_KEY_LENGTH) { 0xC1.toByte() }
    fun repeaterPublicKey(): ByteArray = ByteArray(PUBLIC_KEY_LENGTH) { 0xE1.toByte() }
    fun selfPublicKey(): ByteArray = ByteArray(PUBLIC_KEY_LENGTH) { 0xD0.toByte() }

    /** Upserts the fixture graph into [database]. Safe to call on every screenshot/demo enable. */
    suspend fun seed(database: MeshDatabase) {
        val now = BASE_EPOCH
        Rows.seedContacts(database, now)
        Rows.seedChannel(database, now)
        Rows.seedMessages(database, now)
        Rows.seedRoom(database, now)
    }

    /** Contact, channel, message, and room rows for [seed]. */
    private object Rows {
        private const val AUTHOR_KEY_PREFIX_LENGTH: Int = 4
        private const val CHANNEL_SECRET_MARKER: Byte = 0x01
        private const val ROOM_NEIGHBOR_COUNT: Int = 2

        private const val OFFSET_ALICE_A: Double = 120.0
        private const val OFFSET_BOB: Double = 60.0
        private const val OFFSET_ROOM: Double = 30.0
        private const val OFFSET_CHANNEL_MSG: Double = 90.0
        private const val OFFSET_CHANNEL_MAP: Double = 45.0
        private const val OFFSET_CHANNEL_MENTION: Double = 10.0
        private const val OFFSET_CHANNEL_ROW: Double = 10.0

        // Demo contact pins (SF Bay) so contact-detail mini-map is visible offline.
        private const val FIXTURE_LAT_ALICE = 37.7749
        private const val FIXTURE_LON_ALICE = -122.4194
        private const val FIXTURE_LAT_BOB = 37.7849
        private const val FIXTURE_LON_BOB = -122.4094
        private const val FIXTURE_LAT_REPEATER = 37.8199
        private const val FIXTURE_LON_REPEATER = -122.4783
        private const val FIXTURE_LAT_ROOM = 37.7699
        private const val FIXTURE_LON_ROOM = -122.4469

        suspend fun seedContacts(database: MeshDatabase, now: Double) {
            database.contactDao().upsertAll(
                listOf(
                    chatContact(
                        ScreenshotChatFixtures.ALICE_A_ID,
                        ScreenshotChatFixtures.ALICE_NAME,
                        ScreenshotChatFixtures.alicePublicKeyA(),
                        now - OFFSET_ALICE_A,
                        favorite = true,
                    ),
                    // No lastMessageDate: exists for mention-picker ambiguity, not as a second DM row.
                    chatContact(
                        ScreenshotChatFixtures.ALICE_B_ID,
                        ScreenshotChatFixtures.ALICE_NAME,
                        ScreenshotChatFixtures.alicePublicKeyB(),
                        lastMessageDate = null,
                        favorite = false,
                    ),
                    chatContact(
                        ScreenshotChatFixtures.BOB_ID,
                        ScreenshotChatFixtures.BOB_NAME,
                        ScreenshotChatFixtures.bobPublicKey(),
                        now - OFFSET_BOB,
                        favorite = true,
                    ),
                    roomContact(now - OFFSET_ROOM),
                    repeaterContact(now - OFFSET_ROOM),
                ).map { it.toEntity() },
            )
        }

        suspend fun seedChannel(database: MeshDatabase, now: Double) {
            database.channelDao().upsert(
                ChannelDto(
                    id = ScreenshotChatFixtures.CHANNEL_ID,
                    radioID = ScreenshotChatFixtures.RADIO_ID,
                    index = ScreenshotChatFixtures.CHANNEL_INDEX,
                    name = ScreenshotChatFixtures.CHANNEL_NAME,
                    secret = byteArrayOf(CHANNEL_SECRET_MARKER),
                    isEnabled = true,
                    unreadCount = 1,
                    unreadMentionCount = 1,
                    notificationLevel = NotificationLevel.ALL,
                    isPinned = true,
                    lastMessageDate = now - OFFSET_CHANNEL_ROW,
                ).toEntity(),
            )
        }

        suspend fun seedMessages(database: MeshDatabase, now: Double) {
            database.messageDao().upsertAll(
                listOf(
                    channelMessage(
                        ScreenshotChatFixtures.CHANNEL_MSG_ID,
                        "Morning check-in from the field.",
                        now - OFFSET_CHANNEL_MSG,
                    ),
                    channelMessage(
                        ScreenshotChatFixtures.CHANNEL_MAP_MSG_ID,
                        ScreenshotChatFixtures.MAP_PREVIEW_MESSAGE_TEXT,
                        now - OFFSET_CHANNEL_MAP,
                    ),
                    channelMessage(
                        ScreenshotChatFixtures.CHANNEL_MENTION_MSG_ID,
                        "Hey ${MentionUtilities.createMention(ScreenshotChatFixtures.ALICE_NAME)} can you take point?",
                        now - OFFSET_CHANNEL_MENTION,
                    ),
                    dmMessage(
                        ScreenshotChatFixtures.DM_MSG_ID,
                        ScreenshotChatFixtures.BOB_ID,
                        "Standing by on the north trail.",
                        now - OFFSET_BOB,
                    ),
                ).map { it.toEntity() },
            )
        }

        suspend fun seedRoom(database: MeshDatabase, now: Double) {
            database.remoteNodeSessionDao().upsert(
                RemoteNodeSessionDto(
                    id = ScreenshotChatFixtures.ROOM_SESSION_ID,
                    radioID = ScreenshotChatFixtures.RADIO_ID,
                    publicKey = ScreenshotChatFixtures.roomPublicKey(),
                    name = ScreenshotChatFixtures.ROOM_NAME,
                    role = RemoteNodeRole.ROOM_SERVER,
                    latitude = FIXTURE_LAT_ROOM,
                    longitude = FIXTURE_LON_ROOM,
                    isConnected = true,
                    permissionLevel = RoomPermissionLevel.READ_WRITE,
                    unreadCount = 1,
                    notificationLevel = NotificationLevel.ALL,
                    isPinned = true,
                    neighborCount = ROOM_NEIGHBOR_COUNT,
                    lastSyncTimestamp = now.toLong(),
                    lastMessageDate = now - OFFSET_ROOM,
                ).toEntity(),
            )
            database.roomMessageDao().upsert(
                RoomMessageDto(
                    id = ScreenshotChatFixtures.ROOM_MSG_ID,
                    sessionID = ScreenshotChatFixtures.ROOM_SESSION_ID,
                    authorKeyPrefix = ScreenshotChatFixtures.bobPublicKey().copyOf(AUTHOR_KEY_PREFIX_LENGTH),
                    text = "Room traffic is clear.",
                    timestamp = (now - OFFSET_ROOM).toLong(),
                    createdAt = now - OFFSET_ROOM,
                    isFromSelf = false,
                    deduplicationKey = "fixture-room-1",
                    statusRawValue = MessageStatus.DELIVERED.raw,
                    retryAttempt = 0,
                    maxRetryAttempts = 0,
                    authorName = ScreenshotChatFixtures.BOB_NAME,
                ).toEntity(),
            )
        }

        private fun chatContact(
            id: Uuid,
            name: String,
            publicKey: ByteArray,
            lastMessageDate: Double?,
            favorite: Boolean,
        ): ContactDto = ContactDto(
            id = id,
            radioID = ScreenshotChatFixtures.RADIO_ID,
            publicKey = publicKey,
            name = name,
            typeRawValue = ContactType.CHAT.raw,
            flags = 0,
            outPathLength = 0,
            outPath = ByteArray(0),
            lastAdvertTimestamp = 0,
            // Demo geo so contact-detail mini-map + Open in Maps are exercisable offline.
            latitude = if (name == ScreenshotChatFixtures.ALICE_NAME) FIXTURE_LAT_ALICE else FIXTURE_LAT_BOB,
            longitude = if (name == ScreenshotChatFixtures.ALICE_NAME) FIXTURE_LON_ALICE else FIXTURE_LON_BOB,
            lastModified = 0,
            isBlocked = false,
            isMuted = false,
            isFavorite = favorite,
            unreadCount = 0,
            lastMessageDate = lastMessageDate,
        )

        private fun roomContact(lastMessageDate: Double): ContactDto = ContactDto(
            id = ScreenshotChatFixtures.ROOM_CONTACT_ID,
            radioID = ScreenshotChatFixtures.RADIO_ID,
            publicKey = ScreenshotChatFixtures.roomPublicKey(),
            name = ScreenshotChatFixtures.ROOM_NAME,
            typeRawValue = ContactType.ROOM.raw,
            flags = 0,
            outPathLength = 0,
            outPath = ByteArray(0),
            lastAdvertTimestamp = 0,
            latitude = FIXTURE_LAT_ROOM,
            longitude = FIXTURE_LON_ROOM,
            lastModified = 0,
            isBlocked = false,
            isMuted = false,
            isFavorite = true,
            unreadCount = 0,
            lastMessageDate = lastMessageDate,
        )

        private fun repeaterContact(lastMessageDate: Double): ContactDto = ContactDto(
            id = ScreenshotChatFixtures.REPEATER_CONTACT_ID,
            radioID = ScreenshotChatFixtures.RADIO_ID,
            publicKey = ScreenshotChatFixtures.repeaterPublicKey(),
            name = ScreenshotChatFixtures.REPEATER_NAME,
            typeRawValue = ContactType.REPEATER.raw,
            flags = 0,
            outPathLength = 0,
            outPath = ByteArray(0),
            lastAdvertTimestamp = 0,
            latitude = FIXTURE_LAT_REPEATER,
            longitude = FIXTURE_LON_REPEATER,
            lastModified = 0,
            isBlocked = false,
            isMuted = false,
            isFavorite = true,
            unreadCount = 0,
            lastMessageDate = lastMessageDate,
        )

        private fun channelMessage(id: Uuid, text: String, sortDate: Double): MessageDto = MessageDto(
            id = id,
            radioID = ScreenshotChatFixtures.RADIO_ID,
            text = text,
            timestamp = sortDate.toLong(),
            createdAt = sortDate,
            direction = MessageDirection.INCOMING,
            status = MessageStatus.DELIVERED,
            textType = TextType.PLAIN,
            pathLength = 1,
            isRead = false,
            heardRepeats = 0,
            sendCount = 0,
            retryAttempt = 0,
            maxRetryAttempts = 0,
            linkPreviewFetched = false,
            containsSelfMention = false,
            mentionSeen = false,
            timestampCorrected = false,
            contactID = null,
            channelIndex = ScreenshotChatFixtures.CHANNEL_INDEX,
            sortDateRaw = sortDate,
            senderNodeName = ScreenshotChatFixtures.BOB_NAME,
            senderKeyPrefix = ScreenshotChatFixtures.bobPublicKey().copyOf(AUTHOR_KEY_PREFIX_LENGTH),
        )

        private fun dmMessage(id: Uuid, contactID: Uuid, text: String, sortDate: Double): MessageDto = MessageDto(
            id = id,
            radioID = ScreenshotChatFixtures.RADIO_ID,
            text = text,
            timestamp = sortDate.toLong(),
            createdAt = sortDate,
            direction = MessageDirection.INCOMING,
            status = MessageStatus.DELIVERED,
            textType = TextType.PLAIN,
            pathLength = 1,
            isRead = false,
            heardRepeats = 0,
            sendCount = 0,
            retryAttempt = 0,
            maxRetryAttempts = 0,
            linkPreviewFetched = false,
            containsSelfMention = false,
            mentionSeen = false,
            timestampCorrected = false,
            contactID = contactID,
            channelIndex = null,
            sortDateRaw = sortDate,
            senderNodeName = ScreenshotChatFixtures.BOB_NAME,
        )
    }
}
