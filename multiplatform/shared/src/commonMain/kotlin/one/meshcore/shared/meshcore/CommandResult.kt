@file:UseSerializers(Base64ByteArraySerializer::class)

package one.meshcore.shared.meshcore

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import one.meshcore.shared.model.Base64ByteArraySerializer

/**
 * The result of a single command, decoded from a v1 boundary envelope. One subtype per command
 * result kind on the Swift side (see `MeshCoreBridge/ENCODING.md`). A `Void`-returning command
 * yields [Ok].
 */
sealed interface CommandResult {
    /** A command that returns no value. */
    data object Ok : CommandResult

    /** `startSession` -> this device's configuration. */
    data class SelfInfoResult(val info: SelfInfo) : CommandResult

    /** `queryDevice` -> static capabilities. */
    data class DeviceCapabilitiesResult(val capabilities: DeviceCapabilities) : CommandResult

    /** `getBattery` -> battery/storage. */
    data class BatteryResult(val battery: BatteryInfo) : CommandResult

    /** `getTime` -> the device clock. */
    @Serializable
    data class TimeResult(val time: Long) : CommandResult

    /** `getCustomVars` -> custom variables. */
    @Serializable
    data class CustomVarsResult(val vars: Map<String, String>) : CommandResult

    /** `getChannel` -> one channel slot. */
    data class ChannelInfoResult(val channel: ChannelInfo) : CommandResult

    /** `getChannels` -> the fetched slots and the indices the device did not return. */
    @Serializable
    data class ChannelsResult(val received: List<ChannelInfo>, val missing: List<Int>) : CommandResult

    /** `getContacts` -> the contact list and the device's `contactsStart` total, if any. */
    @Serializable
    data class ContactsResult(val contacts: List<MeshContact>, val reportedTotal: Int? = null) : CommandResult

    /** `getContact` -> the contact, or `null` if not found. */
    @Serializable
    data class ContactOptionalResult(val contact: MeshContact? = null) : CommandResult

    /** `exportContact` -> a contact URI. */
    @Serializable
    data class ContactUriResult(val uri: String) : CommandResult

    /** `sendMessage`/`sendTrace`/`sendLogin`/`sendCommand`/... -> the queued-send info. */
    data class MessageSentResult(val info: MessageSentInfo) : CommandResult

    /** `getMessage` -> the next queued message, or that none remain. */
    data class MessageResult(val message: GetMessageResult) : CommandResult

    /** `requestStatus` -> a repeater/room-server status frame. */
    data class StatusResponseResult(val status: StatusResponse) : CommandResult

    /** `requestTelemetry`/`getSelfTelemetry` -> raw telemetry. */
    data class TelemetryResponseResult(val telemetry: TelemetryResponse) : CommandResult

    /** `sign` -> a signature. */
    @Serializable
    data class SignatureResult(val signature: ByteArray) : CommandResult

    /** `exportPrivateKey` -> the private key. */
    @Serializable
    data class PrivateKeyResult(val privateKey: ByteArray) : CommandResult

    /** `getStatsCore` -> core counters. */
    data class StatsCoreResult(val stats: CoreStats) : CommandResult

    /** `getStatsRadio` -> radio counters. */
    data class StatsRadioResult(val stats: RadioStats) : CommandResult

    /** `getStatsPackets` -> packet counters. */
    data class StatsPacketsResult(val stats: PacketStats) : CommandResult

    /** `getRepeatFreq` -> the allowed repeat-frequency ranges. */
    @Serializable
    data class AllowedRepeatFreqResult(val ranges: List<RepeatFreqRange>) : CommandResult

    /** `getAutoAddConfig` -> the auto-add policy. */
    data class AutoAddConfigResult(val config: AutoAddConfig) : CommandResult

    /** `getDefaultFloodScope` -> the default flood scope, or `null` if unset. */
    @Serializable
    data class DefaultFloodScopeResult(val scope: DefaultFloodScope? = null) : CommandResult

    /** `requestMMA` -> a min/max/avg telemetry response. */
    data class MMAResult(val response: MMAResponse) : CommandResult

    /** `requestACL` -> an access-control-list response. */
    data class ACLResult(val response: ACLResponse) : CommandResult

    /** `requestNeighbours`/`fetchAllNeighbours` -> a neighbours response. */
    data class NeighboursResult(val response: NeighboursResponse) : CommandResult

    /** `requestOwnerInfo` -> repeater owner info. */
    data class OwnerInfoResult(val info: OwnerInfoResponse) : CommandResult

    /** `requestRegions` -> the repeater's region list. */
    @Serializable
    data class RegionsResult(val regions: List<String>) : CommandResult

    /** `sendNodeDiscoverRequest` -> the 32-bit correlation tag for matching discover responses. */
    @Serializable
    data class DiscoverTagResult(val tag: Long) : CommandResult
}

/** The discriminated payload of a `getMessage` result. */
sealed interface GetMessageResult {
    /** A direct message from a contact. */
    data class ContactMessageReceived(val message: ContactMessage) : GetMessageResult

    /** A message on a channel. */
    data class ChannelMessageReceived(val message: ChannelMessage) : GetMessageResult

    /** A binary datagram on a channel. */
    data class ChannelDataReceived(val datagram: ChannelDatagram) : GetMessageResult

    /** No queued message was available. */
    data object NoMoreMessages : GetMessageResult
}

internal fun decodeGetMessage(payload: JsonObject): GetMessageResult {
    fun malformed(detail: String): Nothing = throw BridgeDecodingException(detail)
    val type =
        payload["type"]?.jsonPrimitive?.content
            ?: malformed("messageResult is missing 'type'")
    if (type == "noMoreMessages") return GetMessageResult.NoMoreMessages
    val message =
        payload["message"] as? JsonObject
            ?: malformed("messageResult '$type' is missing an object 'message'")
    return when (type) {
        "contactMessage" -> GetMessageResult.ContactMessageReceived(message.decodeBridge())
        "channelMessage" -> GetMessageResult.ChannelMessageReceived(message.decodeBridge())
        "channelDatagram" -> GetMessageResult.ChannelDataReceived(message.decodeBridge())
        else -> malformed("unknown messageResult type: $type")
    }
}

/**
 * Decodes one command result from its v1 boundary envelope. Throws [BridgeDecodingException] for an
 * unknown version or kind.
 */
fun decodeCommandResult(jsonText: String): CommandResult {
    val (kind, payload) = splitEnvelope(jsonText)
    return decodeGeneratedCommandResult(kind, payload)
}
