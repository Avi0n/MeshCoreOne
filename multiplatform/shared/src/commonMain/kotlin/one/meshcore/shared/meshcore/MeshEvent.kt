@file:UseSerializers(Base64ByteArraySerializer::class)

package one.meshcore.shared.meshcore

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import one.meshcore.shared.model.Base64ByteArraySerializer

/**
 * A pushed device event, decoded from a v1 boundary envelope. One subtype per `MeshEvent` case on
 * the Swift side; the `kind` discriminator maps to the subtype and the `payload` object supplies its
 * fields (see `MeshCoreBridge/ENCODING.md`). Subtypes that carry a named sub-object wrap it; the rest
 * are decoded directly from their payload.
 */
sealed interface MeshEvent {
    /** A connection-state transition (also delivers reconnect attempts and transport failures). */
    data class ConnectionStateChanged(val state: ConnectionState) : MeshEvent

    /** A bare success push with an optional value. */
    @Serializable
    data class Ok(val value: Int? = null) : MeshEvent

    /** A bare error push with an optional device code. */
    @Serializable
    data class ErrorReported(val code: Int? = null) : MeshEvent

    /** This device's configuration snapshot. */
    data class SelfInfoReceived(val info: SelfInfo) : MeshEvent

    /** Static device capabilities. */
    data class DeviceInfoReceived(val capabilities: DeviceCapabilities) : MeshEvent

    /** Battery and storage readout. */
    data class BatteryReceived(val battery: BatteryInfo) : MeshEvent

    /** The device's current clock. */
    @Serializable
    data class CurrentTime(val time: Long) : MeshEvent

    /** Custom key/value variables. */
    @Serializable
    data class CustomVars(val vars: Map<String, String>) : MeshEvent

    /** A channel slot. */
    data class ChannelInfoReceived(val channel: ChannelInfo) : MeshEvent

    /** Core device counters. */
    data class StatsCore(val stats: CoreStats) : MeshEvent

    /** Radio-link counters. */
    data class StatsRadio(val stats: RadioStats) : MeshEvent

    /** Packet counters. */
    data class StatsPackets(val stats: PacketStats) : MeshEvent

    /** Auto-add policy. */
    data class AutoAddConfigReceived(val config: AutoAddConfig) : MeshEvent

    /** The persisted default flood scope, or `null` if unset. */
    @Serializable
    data class DefaultFloodScopeReceived(val scope: DefaultFloodScope? = null) : MeshEvent

    /** Allowed repeat-frequency ranges. */
    @Serializable
    data class AllowedRepeatFreq(val ranges: List<RepeatFreqRange>) : MeshEvent

    /** The start of a contacts sync, with the expected count. */
    @Serializable
    data class ContactsStart(val count: Int) : MeshEvent

    /** A contact record during sync. */
    data class ContactReceived(val contact: MeshContact) : MeshEvent

    /** The end of a contacts sync. */
    @Serializable
    data class ContactsEnd(val lastModified: Long) : MeshEvent

    /** A newly discovered contact. */
    data class NewContact(val contact: MeshContact) : MeshEvent

    /** A contact was deleted. */
    @Serializable
    data class ContactDeleted(val publicKey: ByteArray) : MeshEvent

    /** The device's contact store is full. */
    data object ContactsFull : MeshEvent

    /** An exported contact URI. */
    @Serializable
    data class ContactUri(val uri: String) : MeshEvent

    /** A send was queued for transmission. */
    data class MessageSent(val info: MessageSentInfo) : MeshEvent

    /** A direct message from a contact. */
    data class ContactMessageReceived(val message: ContactMessage) : MeshEvent

    /** A message on a channel. */
    data class ChannelMessageReceived(val message: ChannelMessage) : MeshEvent

    /** A binary datagram on a channel. */
    data class ChannelDataReceived(val datagram: ChannelDatagram) : MeshEvent

    /** No more queued messages remain. */
    data object NoMoreMessages : MeshEvent

    /** The device reports queued messages are waiting. */
    data object MessagesWaiting : MeshEvent

    /** An advertisement was heard from a public key. */
    @Serializable
    data class Advertisement(val publicKey: ByteArray) : MeshEvent

    /** A path to a contact changed. */
    @Serializable
    data class PathUpdate(val publicKey: ByteArray) : MeshEvent

    /** An end-to-end acknowledgement, with an optional round-trip time in ms. */
    @Serializable
    data class Acknowledgement(val code: ByteArray, val tripTime: Int? = null) : MeshEvent

    /** A completed trace. */
    data class TraceData(val trace: TraceInfo) : MeshEvent

    /** A path-discovery response. */
    data class PathResponse(val path: PathInfo) : MeshEvent

    /** A successful remote login. */
    data class LoginSuccess(val login: LoginInfo) : MeshEvent

    /** A failed remote login. */
    @Serializable
    data class LoginFailed(val publicKeyPrefix: ByteArray? = null) : MeshEvent

    /** A repeater/room-server status frame. */
    data class StatusResponseReceived(val status: StatusResponse) : MeshEvent

    /** A telemetry response (raw LPP). */
    data class TelemetryResponseReceived(val telemetry: TelemetryResponse) : MeshEvent

    /** A binary (tag + data) response. */
    data class BinaryResponseReceived(val response: BinaryResponse) : MeshEvent

    /** A min/max/avg telemetry response. */
    data class MMAResponseReceived(val response: MMAResponse) : MeshEvent

    /** An access-control-list response. */
    data class ACLResponseReceived(val response: ACLResponse) : MeshEvent

    /** A neighbours response. */
    data class NeighboursResponseReceived(val response: NeighboursResponse) : MeshEvent

    /** The device is ready to accept a payload to sign, up to `maxLength` bytes. */
    @Serializable
    data class SignStart(val maxLength: Int) : MeshEvent

    /** A produced signature. */
    @Serializable
    data class SignatureReceived(val signature: ByteArray) : MeshEvent

    /** A feature is disabled on the device. */
    @Serializable
    data class Disabled(val reason: String) : MeshEvent

    /** A raw received frame. */
    data class RawData(val data: RawDataInfo) : MeshEvent

    /** A raw log frame. */
    data class LogData(val data: LogDataInfo) : MeshEvent

    /** A parsed RX log frame. */
    data class RxLogData(val data: ParsedRxLogData) : MeshEvent

    /** A control-plane frame. */
    data class ControlData(val data: ControlDataInfo) : MeshEvent

    /** A passive-discover response. */
    data class DiscoverResponseReceived(val response: DiscoverResponse) : MeshEvent

    /** An advert-path response. */
    data class AdvertPathResponseReceived(val response: AdvertPathResponse) : MeshEvent

    /** Radio tuning parameters. */
    data class TuningParamsResponseReceived(val response: TuningParamsResponse) : MeshEvent

    /** An exported private key. */
    @Serializable
    data class PrivateKeyReceived(val privateKey: ByteArray) : MeshEvent

    /** A frame that could not be parsed, with the raw bytes and a reason. */
    @Serializable
    data class ParseFailure(val data: ByteArray, val reason: String) : MeshEvent
}

/**
 * Decodes one pushed event from its v1 boundary envelope. Throws [BridgeDecodingException] for an
 * unknown version or kind, so a producer-side addition surfaces loudly rather than being dropped.
 * The `when` table is generated from `fixtures/bridge/schema.json`.
 */
fun decodeMeshEvent(jsonText: String): MeshEvent {
    val (kind, payload) = splitEnvelope(jsonText)
    return decodeGeneratedMeshEvent(kind, payload)
}
