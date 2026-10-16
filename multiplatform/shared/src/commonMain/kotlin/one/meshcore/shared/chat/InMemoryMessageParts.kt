package one.meshcore.shared.chat

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.backup.DeviceDto
import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.meshcore.MeshContact
import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.Uuid

/** Shared message maps and the one mutex behind [InMemoryMessageStore]. */
internal class InMemoryMessageParts {
    val mutex = Mutex()
    val messages = mutableMapOf<Uuid, MessageDto>()
    val contactLastMessage = mutableMapOf<Uuid, Double>()
    val channelIds = mutableMapOf<Pair<Uuid, Int>, Uuid>()
    val channelLastMessage = mutableMapOf<Uuid, Double>()
    val savedContacts = mutableMapOf<String, MeshContact>()
    val deviceNodeNames = mutableMapOf<Uuid, String>()
    val devices = mutableMapOf<Uuid, DeviceDto>()
    val channels = mutableMapOf<Pair<Uuid, Int>, ChannelDto>()
    val delivery = InMemoryMessageDelivery(this)
    val records = InMemoryMessageRecords(this)
    val seeds = InMemoryMessageSeeds(this)
    val store: MessageStore = records
}

/** Guarded status, ACK, timestamp, and repeat writes. */
internal class InMemoryMessageDelivery(private val parts: InMemoryMessageParts) : MessageDeliveryWrites {
    override suspend fun updateMessageStatus(id: Uuid, status: MessageStatus) = parts.mutex.withLock {
        parts.messages[id]?.let { parts.messages[id] = it.withDeliveryStatus(status) }
        Unit
    }

    override suspend fun updateMessageStatusUnlessDelivered(id: Uuid, status: MessageStatus): Boolean =
        parts.mutex.withLock {
            val current = parts.messages[id] ?: return@withLock false
            if (!MessageStatusGuards.allowsUnlessDelivered(current.status)) return@withLock false
            parts.messages[id] = current.withDeliveryStatus(status)
            true
        }

    override suspend fun clearRetryingToSent(id: Uuid, onlyRetrying: Boolean): Boolean = parts.mutex.withLock {
        val current = parts.messages[id] ?: return@withLock false
        if (!MessageStatusGuards.allowsClearRetryingToSent(current.status, onlyRetrying)) return@withLock false
        parts.messages[id] = current.copy(status = MessageStatus.SENT)
        true
    }

    override suspend fun updateMessageRetryStatus(
        id: Uuid,
        status: MessageStatus,
        retryAttempt: Int,
        maxRetryAttempts: Int,
    ) = parts.mutex.withLock {
        val current = parts.messages[id] ?: return@withLock
        if (!MessageStatusGuards.allowsRetryStatus(current.status)) return@withLock
        parts.messages[id] = current.copy(
            status = status,
            retryAttempt = retryAttempt,
            maxRetryAttempts = maxRetryAttempts,
        )
    }

    override suspend fun updateMessageAck(id: Uuid, ackCode: Long, status: MessageStatus, roundTripTime: Long?) =
        parts.mutex.withLock {
            val current = parts.messages[id] ?: return@withLock
            if (!MessageStatusGuards.allowsAckWrite(current.status, status)) return@withLock
            parts.messages[id] = current.copy(ackCode = ackCode, status = status, roundTripTime = roundTripTime)
        }

    override suspend fun updateMessageTimestamp(id: Uuid, timestamp: Long) = parts.mutex.withLock {
        parts.messages[id]?.let { parts.messages[id] = it.copy(timestamp = timestamp) }
        Unit
    }

    override suspend fun incrementMessageSendCount(id: Uuid): Int = parts.mutex.withLock {
        val current = parts.messages[id] ?: return@withLock 0
        val next = current.sendCount + 1
        parts.messages[id] = current.copy(sendCount = next)
        next
    }

    override suspend fun updateMessageHeardRepeats(id: Uuid, heardRepeats: Int) = parts.mutex.withLock {
        parts.messages[id]?.let { parts.messages[id] = it.copy(heardRepeats = heardRepeats) }
        Unit
    }

    override suspend fun deleteMessageRepeats(messageID: Uuid) = Unit
}

/** Message rows plus the contact and channel bookkeeping around a send. */
internal class InMemoryMessageRecords(private val parts: InMemoryMessageParts) :
    MessageStore,
    MessageDeliveryWrites by parts.delivery {
    override suspend fun fetchMessage(id: Uuid): MessageDto? = parts.mutex.withLock { parts.messages[id] }

    override suspend fun saveMessage(message: MessageDto) = parts.mutex.withLock {
        parts.messages[message.id] = message
    }

    override suspend fun updateContactLastMessage(contactID: Uuid, dateEpochSeconds: Double) = parts.mutex.withLock {
        parts.contactLastMessage[contactID] = dateEpochSeconds
    }

    override suspend fun fetchDeviceNodeName(radioID: Uuid): String? =
        parts.mutex.withLock { parts.deviceNodeNames[radioID] }

    override suspend fun fetchChannelSendConfiguration(radioID: Uuid, channelIndex: Int): ChannelSendConfiguration =
        parts.mutex.withLock {
            ChannelSendConfiguration(parts.channels[radioID to channelIndex], parts.devices[radioID])
        }

    override suspend fun fetchChannelId(radioID: Uuid, channelIndex: Int): Uuid? =
        parts.mutex.withLock { parts.channelIds[radioID to channelIndex] }

    override suspend fun updateChannelLastMessage(channelID: Uuid, dateEpochSeconds: Double) = parts.mutex.withLock {
        parts.channelLastMessage[channelID] = dateEpochSeconds
    }

    override suspend fun saveContact(radioID: Uuid, contact: MeshContact) = parts.mutex.withLock {
        parts.savedContacts[contact.id] = contact
    }

    override suspend fun hasOutgoingSentDM(ackCode: Long): Boolean = parts.mutex.withLock {
        parts.messages.values.any { isOutgoingSentDM(it, ackCode) }
    }

    private fun isOutgoingSentDM(message: MessageDto, ackCode: Long): Boolean = message.ackCode == ackCode &&
        message.status == MessageStatus.SENT &&
        message.direction == MessageDirection.OUTGOING &&
        message.channelIndex == null
}

/** Test seeds. Channel and device-name seeds stay unlocked, matching the original store. */
internal class InMemoryMessageSeeds(private val parts: InMemoryMessageParts) {
    fun seedChannel(channel: ChannelDto) {
        parts.channels[channel.radioID to channel.index] = channel
        seedChannel(channel.radioID, channel.index, channel.id)
    }

    fun seedDevice(device: DeviceDto) {
        parts.devices[device.radioID] = device
        seedDeviceNodeName(device.radioID, device.nodeName)
    }

    fun seedChannel(radioID: Uuid, channelIndex: Int, channelID: Uuid) {
        parts.channelIds[radioID to channelIndex] = channelID
    }

    fun seedDeviceNodeName(radioID: Uuid, nodeName: String) {
        parts.deviceNodeNames[radioID] = nodeName
    }

    suspend fun contactLastMessageDate(contactID: Uuid): Double? =
        parts.mutex.withLock { parts.contactLastMessage[contactID] }

    suspend fun channelLastMessageDate(channelID: Uuid): Double? =
        parts.mutex.withLock { parts.channelLastMessage[channelID] }

    suspend fun savedContact(publicKeyHex: String): MeshContact? =
        parts.mutex.withLock { parts.savedContacts[publicKeyHex] }
}

private fun MessageDto.withDeliveryStatus(status: MessageStatus): MessageDto = copy(
    status = status,
    failureSeen = MessageStatusGuards.failureSeenAfterWrite(this.status, status, failureSeen),
)
