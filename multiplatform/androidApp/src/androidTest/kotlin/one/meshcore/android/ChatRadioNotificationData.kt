package one.meshcore.android

import one.meshcore.shared.channel.UNSCOPED_FLOOD_SEND_MIN_FIRMWARE_VERSION
import one.meshcore.shared.chat.ChatConversationID
import one.meshcore.shared.model.ChannelFloodScopeStorage
import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.TextType
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.ChannelEntity
import one.meshcore.shared.persistence.MessageEntity
import one.meshcore.shared.persistence.toDto
import one.meshcore.shared.ui.ChatDestination

internal object ChatRadioNotificationData {
    val RADIO_A: Uuid = uuid("A2000000-0000-4000-8000-000000000101")
    val RADIO_B: Uuid = uuid("B2000000-0000-4000-8000-000000000102")
    val DEVICE_A: Uuid = uuid("A1000000-0000-4000-8000-000000000101")
    val DEVICE_B: Uuid = uuid("B1000000-0000-4000-8000-000000000102")
    val CHANNEL_A: Uuid = uuid("A4000000-0000-4000-8000-000000000101")
    val CHANNEL_B: Uuid = uuid("B4000000-0000-4000-8000-000000000102")
    val MESSAGE_A: Uuid = uuid("A5000000-0000-4000-8000-000000000101")
    val MESSAGE_B: Uuid = uuid("B5000000-0000-4000-8000-000000000102")
    const val INDEX = 2
    const val TITLE_A = "Synthetic cached A channel"
    const val TITLE_B = "Synthetic matching B channel"
    const val HISTORY_A = "Synthetic A history remains readable"
    const val HISTORY_B = "Synthetic B history remains readable"
    const val DRAFT_A = "Synthetic unsent A draft"
    const val DRAFT_B = "Synthetic unsent B draft"
    const val NOTIFICATION_BODY = "Synthetic notification body ownership test"
    const val LOCAL_SCOPE = "Synthetic A local scope"
    const val B_NODE_NAME = "Synthetic B radio"
    const val B_DEFAULT_SCOPE = "Synthetic B default scope"
    const val B_KNOWN_REGION = "Synthetic B known region"
    private const val ORIGINAL_SCOPE = "Synthetic original scope"
    private const val KEY_SIZE = 32
    private const val KEY_BYTE = 0x45
    private const val NOW = 1_800_000_000L

    fun channel(radio: Uuid): ChannelEntity = ChannelEntity(
        id = if (radio == RADIO_A) CHANNEL_A else CHANNEL_B,
        radioID = radio,
        index = INDEX,
        name = if (radio == RADIO_A) TITLE_A else TITLE_B,
        secret = ByteArray(KEY_SIZE) { KEY_BYTE.toByte() },
        isEnabled = true,
        unreadCount = 1,
        floodScopeModeRawValue = ChannelFloodScopeStorage.MODE_SPECIFIC,
        regionScope = ORIGINAL_SCOPE,
        lastMessageDate = NOW.toDouble(),
    )

    fun destination(radio: Uuid): ChatDestination {
        val dto = channel(radio).toDto()
        return ChatDestination(ChatConversationID.channel(radio, INDEX), dto.name, contact = null, channel = dto)
    }

    fun device(radio: Uuid) = ConnectionServiceIntentRadio.entity().copy(
        id = if (radio == RADIO_A) DEVICE_A else DEVICE_B,
        radioID = radio,
        nodeName = if (radio == RADIO_A) "Synthetic A radio" else B_NODE_NAME,
        publicKey = ByteArray(KEY_SIZE) { if (radio == RADIO_A) KEY_BYTE.toByte() else (KEY_BYTE + 1).toByte() },
        connectionMethods = emptyList(),
        maxChannels = INDEX + 1,
        firmwareVersion = if (radio == RADIO_B) UNSCOPED_FLOOD_SEND_MIN_FIRMWARE_VERSION else 0,
        knownRegions = if (radio == RADIO_B) listOf(B_KNOWN_REGION) else emptyList(),
        defaultFloodScopeName = if (radio == RADIO_B) B_DEFAULT_SCOPE else null,
    )

    fun message(radio: Uuid): MessageEntity = MessageEntity(
        id = if (radio == RADIO_A) MESSAGE_A else MESSAGE_B,
        radioID = radio,
        text = if (radio == RADIO_A) HISTORY_A else HISTORY_B,
        timestamp = NOW,
        createdAt = NOW.toDouble(),
        sortDate = NOW.toDouble(),
        directionRawValue = MessageDirection.INCOMING.raw,
        statusRawValue = MessageStatus.SENT.raw,
        textTypeRawValue = TextType.PLAIN.raw,
        pathLength = 0,
        isRead = false,
        heardRepeats = 0,
        sendCount = 0,
        retryAttempt = 0,
        maxRetryAttempts = 0,
        linkPreviewFetched = false,
        containsSelfMention = false,
        mentionSeen = false,
        timestampCorrected = false,
        channelIndex = INDEX,
        senderNodeName = "Synthetic sender",
    )

    private fun uuid(value: String): Uuid = Uuid.normalized(value)
}
