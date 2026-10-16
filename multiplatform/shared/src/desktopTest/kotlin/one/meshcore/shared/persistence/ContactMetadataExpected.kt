package one.meshcore.shared.persistence

import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.chat.ChatFixtures
import one.meshcore.shared.chat.IngestNotifier
import one.meshcore.shared.chat.ResolvedContact
import one.meshcore.shared.contact.DiscoveredNodeRecord
import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.TextType
import one.meshcore.shared.model.Uuid
import kotlin.test.assertEquals

internal object ContactMetadataExpected {
    const val NOW = 1_700_000_500.0
    const val SENDER_TIME = 1_700_000_495L
    const val BODY = "hey @[Me] look"
    val MESSAGE_ID = Uuid.normalized("99999999-9999-9999-9999-999999999999")
    private val DISCOVER_ID = Uuid.normalized("88888888-8888-8888-8888-888888888888")

    fun message(contactID: Uuid, prefix: ByteArray): MessageDto = MessageDto(
        id = MESSAGE_ID, radioID = ChatFixtures.RADIO, contactID = contactID, channelIndex = null,
        text = BODY, timestamp = SENDER_TIME, createdAt = NOW, sortDateRaw = NOW,
        direction = MessageDirection.INCOMING, status = MessageStatus.DELIVERED, textType = TextType.PLAIN,
        pathLength = 3, snr = -5.0, senderKeyPrefix = prefix, senderNodeName = null, pathNodes = null,
        isRead = false, heardRepeats = 0, sendCount = 1, retryAttempt = 0, maxRetryAttempts = 0,
        linkPreviewFetched = false, containsSelfMention = true, mentionSeen = false, failureSeen = false,
        timestampCorrected = false, senderTimestamp = null,
        deduplicationKey = "dm-${contactID.value}-${SENDER_TIME}-A2F245CA",
    )

    fun assertBookkeeping(contact: ContactEntity) {
        assertEquals(NOW.toLong(), contact.lastHeardTimestamp)
        assertEquals(NOW, contact.lastMessageDate)
        assertEquals(8, contact.unreadCount)
        assertEquals(3, contact.unreadMentionCount)
    }

    fun discovered(contact: ContactEntity, stamp: Long): DiscoveredNodeRecord = DiscoveredNodeRecord(
        id = DISCOVER_ID, radioID = contact.radioID, publicKey = contact.publicKey.copyOf(), name = contact.name,
        typeRawValue = contact.typeRawValue, lastHeardEpochSeconds = stamp,
        lastAdvertTimestamp = contact.lastAdvertTimestamp, latitude = contact.latitude, longitude = contact.longitude,
        outPathLength = contact.outPathLength, outPath = contact.outPath.copyOf(),
        inboundHopCount = 2, inboundHopAdvertTimestamp = contact.lastAdvertTimestamp,
    )
}

internal class ContactMetadataNoticeRecorder : IngestNotifier by IngestNotifier.NoOp {
    private val calls = mutableListOf<List<Any>>()
    private var badge = 0
    private var conversations = 0

    override suspend fun postDirectMessage(
        contact: ResolvedContact,
        text: String,
        messageID: Uuid,
        hasSelfMention: Boolean,
    ) {
        calls += listOf(contact, text, messageID, hasSelfMention)
    }
    override suspend fun postChannelMessage(notice: one.meshcore.shared.chat.ChannelMessageNotice) {
        calls += listOf(notice)
    }
    override suspend fun recordUnresolvedChannel(channelIndex: Int, radioID: Uuid, senderTimestamp: Long) {
        calls += listOf(channelIndex, radioID, senderTimestamp)
    }
    override suspend fun updateBadge() {
        badge += 1
    }
    override suspend fun notifyConversationsChanged() {
        conversations += 1
    }

    fun assertReceived(contact: ResolvedContact, message: MessageDto) {
        assertEquals(listOf(listOf(contact, message.text, message.id, true)), calls)
        assertEquals(1, badge)
        assertEquals(1, conversations)
    }
}
