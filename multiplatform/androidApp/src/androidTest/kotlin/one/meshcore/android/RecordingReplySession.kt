package one.meshcore.android

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import one.meshcore.shared.channel.ResolvedFloodScope
import one.meshcore.shared.meshcore.AutoAddConfig
import one.meshcore.shared.meshcore.BatteryInfo
import one.meshcore.shared.meshcore.ChannelInfo
import one.meshcore.shared.meshcore.CommandResult
import one.meshcore.shared.meshcore.DeviceCapabilities
import one.meshcore.shared.meshcore.DeviceOtherParams
import one.meshcore.shared.meshcore.GetMessageResult
import one.meshcore.shared.meshcore.MeshContact
import one.meshcore.shared.meshcore.MeshEvent
import one.meshcore.shared.meshcore.MeshSession
import one.meshcore.shared.meshcore.MessageSentInfo
import one.meshcore.shared.meshcore.NeighboursResponse
import one.meshcore.shared.meshcore.OwnerInfoResponse
import one.meshcore.shared.meshcore.SelfInfo
import one.meshcore.shared.meshcore.StatusResponse
import one.meshcore.shared.meshcore.TelemetryResponse
import one.meshcore.shared.model.ContactType

/**
 * Records direct and channel sends for a quick-reply fixture. [releaseSend] holds a direct send
 * until the caller lets it return, so unread state can be read while the radio call is still open.
 * Every other command fails. Nothing here opens a transport.
 */
@Suppress("TooManyFunctions")
class RecordingReplySession(private val releaseSend: CompletableDeferred<Unit>? = null) : MeshSession {
    val directSends = mutableListOf<String>()
    val channelSends = mutableListOf<String>()
    val directSendEntered = CompletableDeferred<Unit>()

    override val events: SharedFlow<MeshEvent> = MutableSharedFlow()

    override suspend fun sendMessage(
        destination: ByteArray,
        text: String,
        timestamp: Long,
        attempt: Int,
    ): MessageSentInfo {
        directSends += text
        directSendEntered.complete(Unit)
        releaseSend?.await()
        return ACCEPTED
    }

    override suspend fun sendChannelMessage(channel: Int, text: String, timestamp: Long) {
        channelSends.add(text)
    }

    override suspend fun sendChannelMessage(
        channel: Int,
        text: String,
        timestamp: Long,
        floodScope: ResolvedFloodScope,
    ) {
        channelSends += text
    }

    override suspend fun getBattery(): BatteryInfo = BatteryInfo(level = 0)

    override suspend fun start(): SelfInfo = unexpected()

    override suspend fun queryDevice(): DeviceCapabilities = unexpected()

    override suspend fun getTime(): Long = unexpected()

    override suspend fun getContacts(since: Long?): List<MeshContact> = unexpected()

    override suspend fun getChannel(index: Int): ChannelInfo = unexpected()

    override suspend fun getChannels(indices: ByteArray): CommandResult.ChannelsResult = unexpected()

    override suspend fun setChannel(index: Int, name: String, secret: ByteArray) = unexpected()

    override suspend fun getMessage(): GetMessageResult = unexpected()

    override suspend fun getContact(publicKey: ByteArray): MeshContact? = unexpected()

    override suspend fun resetPath(publicKey: ByteArray) = unexpected()

    override suspend fun addContact(contact: MeshContact) = unexpected()

    override suspend fun removeContact(publicKey: ByteArray) = unexpected()

    override suspend fun changeContactFlags(contact: MeshContact, flags: Int) = unexpected()

    override suspend fun sendPathDiscovery(destination: ByteArray): MessageSentInfo = unexpected()

    override suspend fun sendTrace(tag: Long?, authCode: Long?, flags: Int, path: ByteArray?): MessageSentInfo =
        unexpected()

    override suspend fun shareContact(publicKey: ByteArray) = unexpected()

    override suspend fun sendLogin(destination: ByteArray, password: String): MessageSentInfo = unexpected()

    override suspend fun sendLogout(destination: ByteArray) = unexpected()

    override suspend fun sendKeepAlive(publicKey: ByteArray, syncSince: Long): MessageSentInfo = unexpected()

    override suspend fun requestStatus(publicKey: ByteArray, type: ContactType): StatusResponse = unexpected()

    override suspend fun requestTelemetry(publicKey: ByteArray): TelemetryResponse = unexpected()

    override suspend fun requestOwnerInfo(publicKey: ByteArray): OwnerInfoResponse = unexpected()

    override suspend fun sendCommand(destination: ByteArray, command: String, timestamp: Long): MessageSentInfo =
        unexpected()

    override suspend fun requestNeighbours(
        publicKey: ByteArray,
        count: Int,
        offset: Int,
        orderBy: Int,
        pubkeyPrefixLength: Int,
    ): NeighboursResponse = unexpected()

    override suspend fun getSelfInfo(): SelfInfo = unexpected()

    override suspend fun exportPrivateKey(): ByteArray = unexpected()

    override suspend fun importPrivateKey(privateKey: ByteArray) = unexpected()

    override suspend fun setName(name: String) = unexpected()

    override suspend fun setCoordinates(latitude: Double, longitude: Double) = unexpected()

    override suspend fun setTime(epochSeconds: Long) = unexpected()

    override suspend fun setOtherParams(params: DeviceOtherParams) = unexpected()

    override suspend fun setRadio(
        frequency: Double,
        bandwidth: Double,
        spreadingFactor: Int,
        codingRate: Int,
        clientRepeat: Boolean?,
    ) = unexpected()

    override suspend fun setTxPower(power: Int) = unexpected()

    override suspend fun getAutoAddConfig(): AutoAddConfig = unexpected()

    override suspend fun setAutoAddConfig(bitmask: Int, maxHops: Int) = unexpected()

    override suspend fun stop() = unexpected()

    override fun reportConnected() = unexpected()

    override fun reportConnectFailed(message: String) = unexpected()

    override fun reportDisconnected() = unexpected()

    override fun deliverReceived(bytes: ByteArray) = unexpected()

    override fun close() = Unit

    private fun unexpected(): Nothing = error("Unexpected device command in quick-reply fixture")

    private companion object {
        const val ACK_CODE_BYTES = 4
        const val SUGGESTED_TIMEOUT_MS = 1_000L

        val ACCEPTED = MessageSentInfo(
            route = 0,
            expectedAck = ByteArray(ACK_CODE_BYTES),
            suggestedTimeoutMs = SUGGESTED_TIMEOUT_MS,
        )
    }
}
