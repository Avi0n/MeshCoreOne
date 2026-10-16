package one.meshcore.android

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import one.meshcore.shared.channel.ResolvedFloodScope
import one.meshcore.shared.meshcore.AutoAddConfig
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
 * A [MeshSession] stand-in for the host-wiring tests. Only the commands a test drives are overridden;
 * every other member fails loudly so an unexpected device call cannot pass silently.
 */
// The fake mirrors the whole MeshSession command facade; the count is the interface's, not a design choice.
@Suppress("TooManyFunctions")
open class FakeMeshSession : MeshSession {
    override val events: SharedFlow<MeshEvent> = MutableSharedFlow()

    private fun unused(): Nothing = error("Unexpected device command in test")

    override suspend fun start(): SelfInfo = unused()

    override suspend fun queryDevice(): DeviceCapabilities = unused()

    override suspend fun getTime(): Long = unused()

    override suspend fun getContacts(since: Long?): List<MeshContact> = unused()

    override suspend fun sendMessage(
        destination: ByteArray,
        text: String,
        timestamp: Long,
        attempt: Int,
    ): MessageSentInfo = unused()

    override suspend fun sendChannelMessage(
        channel: Int,
        text: String,
        timestamp: Long,
        floodScope: ResolvedFloodScope,
    ) = unused()

    override suspend fun sendChannelMessage(channel: Int, text: String, timestamp: Long): Unit = unused()

    override suspend fun getChannel(index: Int): ChannelInfo = unused()

    override suspend fun getChannels(indices: ByteArray): CommandResult.ChannelsResult = unused()

    override suspend fun setChannel(index: Int, name: String, secret: ByteArray): Unit = unused()

    override suspend fun getMessage(): GetMessageResult = unused()

    override suspend fun getContact(publicKey: ByteArray): MeshContact? = unused()

    override suspend fun resetPath(publicKey: ByteArray): Unit = unused()

    override suspend fun addContact(contact: MeshContact): Unit = unused()

    override suspend fun removeContact(publicKey: ByteArray): Unit = unused()

    override suspend fun changeContactFlags(contact: MeshContact, flags: Int): Unit = unused()

    override suspend fun sendPathDiscovery(destination: ByteArray): MessageSentInfo = unused()

    override suspend fun sendTrace(tag: Long?, authCode: Long?, flags: Int, path: ByteArray?): MessageSentInfo =
        unused()

    override suspend fun shareContact(publicKey: ByteArray): Unit = unused()

    override suspend fun sendLogin(destination: ByteArray, password: String): MessageSentInfo = unused()

    override suspend fun sendLogout(destination: ByteArray): Unit = unused()

    override suspend fun sendKeepAlive(publicKey: ByteArray, syncSince: Long): MessageSentInfo = unused()

    override suspend fun requestStatus(publicKey: ByteArray, type: ContactType): StatusResponse = unused()

    override suspend fun requestTelemetry(publicKey: ByteArray): TelemetryResponse = unused()

    override suspend fun requestOwnerInfo(publicKey: ByteArray): OwnerInfoResponse = unused()

    override suspend fun sendCommand(destination: ByteArray, command: String, timestamp: Long): MessageSentInfo =
        unused()

    override suspend fun requestNeighbours(
        publicKey: ByteArray,
        count: Int,
        offset: Int,
        orderBy: Int,
        pubkeyPrefixLength: Int,
    ): NeighboursResponse = unused()

    override suspend fun getSelfInfo(): SelfInfo = unused()

    override suspend fun exportPrivateKey(): ByteArray = unused()

    override suspend fun importPrivateKey(privateKey: ByteArray): Unit = unused()

    override suspend fun setName(name: String): Unit = unused()

    override suspend fun setCoordinates(latitude: Double, longitude: Double): Unit = unused()

    override suspend fun setTime(epochSeconds: Long): Unit = unused()

    override suspend fun setOtherParams(params: DeviceOtherParams): Unit = unused()

    override suspend fun setRadio(
        frequency: Double,
        bandwidth: Double,
        spreadingFactor: Int,
        codingRate: Int,
        clientRepeat: Boolean?,
    ): Unit = unused()

    override suspend fun setTxPower(power: Int): Unit = unused()

    override suspend fun getAutoAddConfig(): AutoAddConfig = unused()

    override suspend fun setAutoAddConfig(bitmask: Int, maxHops: Int): Unit = unused()

    override suspend fun stop(): Unit = unused()

    override fun reportConnected(): Unit = unused()

    override fun reportConnectFailed(message: String): Unit = unused()

    override fun reportDisconnected(): Unit = unused()

    override fun deliverReceived(bytes: ByteArray): Unit = unused()

    override fun close() = Unit

    companion object {
        /** A [SelfInfo] carrying [latitude] / [longitude]; every other field is an inert placeholder. */
        fun selfInfoAt(latitude: Double, longitude: Double): SelfInfo = SelfInfo(
            advertisementType = 0,
            txPower = 0,
            maxTxPower = 0,
            publicKey = ByteArray(0),
            latitude = latitude,
            longitude = longitude,
            multiAcks = 0,
            advertisementLocationPolicy = 0,
            telemetryModeEnvironment = 0,
            telemetryModeLocation = 0,
            telemetryModeBase = 0,
            manualAddContacts = false,
            radioFrequency = 0.0,
            radioBandwidth = 0.0,
            radioSpreadingFactor = 0,
            radioCodingRate = 0,
            name = "test",
        )
    }
}
