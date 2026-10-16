package one.meshcore.shared.chat

import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.backup.DeviceDto
import one.meshcore.shared.meshcore.MeshContact
import one.meshcore.shared.model.Uuid

/**
 * In-memory [MessageStore] holding rows in maps behind [InMemoryMessageParts.mutex]. The reference
 * implementation of the status write guards: every guarded method decides through [MessageStatusGuards],
 * so this is both the desktop/test backing and the behavior the Room adapter must reproduce. Tests
 * pre-seed channels through [seedChannel].
 */
class InMemoryMessageStore internal constructor(private val parts: InMemoryMessageParts) :
    MessageStore by parts.store {
    constructor() : this(InMemoryMessageParts())

    fun seedChannel(channel: ChannelDto) = parts.seeds.seedChannel(channel)

    fun seedDevice(device: DeviceDto) = parts.seeds.seedDevice(device)

    /** Registers a `(radioID, channelIndex) -> channelID` mapping so the send path can resolve it. */
    fun seedChannel(radioID: Uuid, channelIndex: Int, channelID: Uuid) =
        parts.seeds.seedChannel(radioID, channelIndex, channelID)

    /** Seeds the Device node name the channel-length last gate reads. */
    fun seedDeviceNodeName(radioID: Uuid, nodeName: String) = parts.seeds.seedDeviceNodeName(radioID, nodeName)

    /** The contact's last-message date, or null; test-only read of the bookkeeping. */
    suspend fun contactLastMessageDate(contactID: Uuid): Double? = parts.seeds.contactLastMessageDate(contactID)

    /** The channel's last-message date, or null; test-only read of the bookkeeping. */
    suspend fun channelLastMessageDate(channelID: Uuid): Double? = parts.seeds.channelLastMessageDate(channelID)

    /** The most recent contact persisted by the routing path for [publicKeyHex], or null. */
    suspend fun savedContact(publicKeyHex: String): MeshContact? = parts.seeds.savedContact(publicKeyHex)
}
