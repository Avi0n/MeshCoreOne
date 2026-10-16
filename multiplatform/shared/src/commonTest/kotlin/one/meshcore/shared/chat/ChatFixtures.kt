package one.meshcore.shared.chat

import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.NotificationLevel
import one.meshcore.shared.model.RemoteNodeRole
import one.meshcore.shared.model.RoomPermissionLevel
import one.meshcore.shared.model.TextType
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.sequentialTestUuid

/** Deterministic builders for the chat domain DTOs, keeping the tests to only the fields they exercise. */
internal object ChatFixtures {
    val RADIO: Uuid = Uuid.normalized("AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA")
    val CONTACT: Uuid = Uuid.normalized("BBBBBBBB-BBBB-BBBB-BBBB-BBBBBBBBBBBB")

    /** Arbitrary epoch-second base used as the default receive/create time in tests. */
    const val BASE_EPOCH_SECONDS: Double = 1_700_000_000.0

    private var idCounter = 0

    /** A distinct, deterministic [Uuid] per call within a test run. */
    fun freshUuid(): Uuid {
        idCounter += 1
        return sequentialTestUuid(idCounter)
    }

    @Suppress("LongParameterList")
    fun message(
        id: Uuid = freshUuid(),
        text: String = "hello",
        timestamp: Long = BASE_EPOCH_SECONDS.toLong(),
        createdAt: Double = BASE_EPOCH_SECONDS,
        sortDate: Double? = null,
        direction: MessageDirection = MessageDirection.OUTGOING,
        status: MessageStatus = MessageStatus.SENT,
        contactID: Uuid? = CONTACT,
        channelIndex: Int? = null,
        senderNodeName: String? = null,
        senderTimestamp: Long? = null,
        timestampCorrected: Boolean = false,
    ): MessageDto = MessageDto(
        id = id,
        radioID = RADIO,
        text = text,
        timestamp = timestamp,
        createdAt = createdAt,
        direction = direction,
        status = status,
        textType = TextType.PLAIN,
        pathLength = 0,
        isRead = false,
        heardRepeats = 0,
        sendCount = 1,
        retryAttempt = 0,
        maxRetryAttempts = 0,
        linkPreviewFetched = false,
        containsSelfMention = false,
        mentionSeen = false,
        timestampCorrected = timestampCorrected,
        contactID = contactID,
        channelIndex = channelIndex,
        sortDateRaw = sortDate,
        senderNodeName = senderNodeName,
        senderTimestamp = senderTimestamp,
    )

    /** A DM message (contact-scoped, no channel index). */
    @Suppress("LongParameterList")
    fun dmMessage(
        timestamp: Long,
        createdAt: Double = BASE_EPOCH_SECONDS,
        sortDate: Double? = null,
        direction: MessageDirection = MessageDirection.INCOMING,
        id: Uuid = freshUuid(),
    ): MessageDto = message(
        id = id,
        text = "msg-$timestamp",
        timestamp = timestamp,
        createdAt = createdAt,
        sortDate = sortDate,
        direction = direction,
        status = MessageStatus.DELIVERED,
        contactID = CONTACT,
        channelIndex = null,
    )

    /** A channel message (channel-scoped, optional sender name). */
    @Suppress("LongParameterList")
    fun channelMessage(
        timestamp: Long,
        createdAt: Double = BASE_EPOCH_SECONDS,
        senderName: String? = null,
        direction: MessageDirection = MessageDirection.INCOMING,
        id: Uuid = freshUuid(),
    ): MessageDto = message(
        id = id,
        text = "msg-$timestamp",
        timestamp = timestamp,
        createdAt = createdAt,
        direction = direction,
        status = MessageStatus.DELIVERED,
        contactID = null,
        channelIndex = 0,
        senderNodeName = senderName,
    )

    @Suppress("LongParameterList")
    fun contact(
        name: String,
        isBlocked: Boolean = false,
        isMuted: Boolean = false,
        isFavorite: Boolean = false,
        isPinned: Boolean = false,
        unreadCount: Int = 0,
        lastMessageDate: Double? = null,
        nickname: String? = null,
        id: Uuid = freshUuid(),
    ): ContactDto = ContactDto(
        id = id,
        radioID = RADIO,
        publicKey = ByteArray(PUBLIC_KEY_LENGTH),
        name = name,
        typeRawValue = ContactType.CHAT.raw,
        flags = 0,
        outPathLength = 0,
        outPath = ByteArray(0),
        lastAdvertTimestamp = 0,
        latitude = 0.0,
        longitude = 0.0,
        lastModified = 0,
        isBlocked = isBlocked,
        isMuted = isMuted,
        isFavorite = isFavorite,
        isPinned = isPinned,
        unreadCount = unreadCount,
        nickname = nickname,
        lastMessageDate = lastMessageDate,
    )

    /** Alice, the contact the direct-send fault tests address. */
    fun directContact(): ContactDto = contact(name = "Alice", id = CONTACT)

    private const val PUBLIC_KEY_LENGTH = 32

    @Suppress("LongParameterList")
    fun channel(
        name: String,
        unreadCount: Int = 0,
        notificationLevel: NotificationLevel = NotificationLevel.ALL,
        isPinned: Boolean = false,
        lastMessageDate: Double? = null,
        index: Int = 0,
        id: Uuid = freshUuid(),
    ): ChannelDto = ChannelDto(
        id = id,
        radioID = RADIO,
        index = index,
        name = name,
        secret = ByteArray(0),
        isEnabled = true,
        unreadCount = unreadCount,
        notificationLevel = notificationLevel,
        isPinned = isPinned,
        lastMessageDate = lastMessageDate,
    )

    @Suppress("LongParameterList")
    fun room(
        name: String,
        unreadCount: Int = 0,
        notificationLevel: NotificationLevel = NotificationLevel.ALL,
        isPinned: Boolean = false,
        lastMessageDate: Double? = null,
        id: Uuid = freshUuid(),
        role: RemoteNodeRole = RemoteNodeRole.ROOM_SERVER,
    ): RemoteNodeSessionDto = RemoteNodeSessionDto(
        id = id,
        radioID = RADIO,
        publicKey = ByteArray(PUBLIC_KEY_LENGTH),
        name = name,
        role = role,
        latitude = 0.0,
        longitude = 0.0,
        isConnected = false,
        permissionLevel = RoomPermissionLevel.GUEST,
        unreadCount = unreadCount,
        notificationLevel = notificationLevel,
        isPinned = isPinned,
        neighborCount = 0,
        lastSyncTimestamp = 0,
        lastMessageDate = lastMessageDate,
    )
}
