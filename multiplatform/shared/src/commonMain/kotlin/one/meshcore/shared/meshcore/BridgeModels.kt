@file:UseSerializers(Base64ByteArraySerializer::class)

package one.meshcore.shared.meshcore

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import one.meshcore.shared.model.Base64ByteArraySerializer
import one.meshcore.shared.remotenode.LPPDataPoint

// Shared payload sub-objects for the JNI boundary encoding (v1). Field names match the JSON keys
// exactly (the MeshCore property names). Conventions, all matching the Swift producer:
//   * `ByteArray`  = Base64 string (file-wide serializer above).
//   * dates        = whole epoch seconds as a JSON integer -> `Long` (the bridge, unlike the backup
//                    envelope, emits integer seconds, never fractional).
//   * counters, airtimes, frequencies-in-kHz, storage, firmware tags-as-numbers, and other
//     `UInt32`-origin fields are `Long`; bounded values (indices, hop counts, flags, RSSI, small
//     enums) are `Int`.
//   * a nullable field is an explicit JSON `null` on the wire; `= null` also tolerates a future
//     producer omitting the key.
// These sub-objects are reused by both `MeshEvent` and `CommandResult`.

/** A transport-layer failure carried inside [ConnectionState]. Mirrors `MeshTransportError`. */
@Serializable
data class BridgeTransportError(val kind: String, val detail: String? = null)

/** A connection-state transition. `attempt` is set only while reconnecting, `error` only on failure. */
@Serializable
data class ConnectionState(val state: String, val attempt: Int? = null, val error: BridgeTransportError? = null)

/** This device's own configuration snapshot. Mirrors `SelfInfo`. */
@Serializable
data class SelfInfo(
    val advertisementType: Int,
    val txPower: Int,
    val maxTxPower: Int,
    val publicKey: ByteArray,
    val latitude: Double,
    val longitude: Double,
    val multiAcks: Int,
    val advertisementLocationPolicy: Int,
    val telemetryModeEnvironment: Int,
    val telemetryModeLocation: Int,
    val telemetryModeBase: Int,
    val manualAddContacts: Boolean,
    val radioFrequency: Double,
    val radioBandwidth: Double,
    val radioSpreadingFactor: Int,
    val radioCodingRate: Int,
    val name: String,
)

/** Static device capabilities. Mirrors `DeviceCapabilities`. */
@Serializable
data class DeviceCapabilities(
    val firmwareVersion: Int,
    val maxContacts: Int,
    val maxChannels: Int,
    val blePin: Long,
    val firmwareBuild: String,
    val model: String,
    val version: String,
    val clientRepeat: Boolean,
    val pathHashMode: Int,
)

/** Battery and storage readout. Mirrors `BatteryInfo`. */
@Serializable
data class BatteryInfo(val level: Int, val usedStorageKB: Long? = null, val totalStorageKB: Long? = null)

/** A LoRa channel slot. Mirrors `ChannelInfo`. */
@Serializable
data class ChannelInfo(val index: Int, val name: String, val secret: ByteArray)

/** A contact record. Mirrors `MeshContact`. `typeRawValue` is the verbatim wire type byte. */
@Serializable
data class MeshContact(
    val id: String,
    val publicKey: ByteArray,
    val typeRawValue: Int,
    val flags: Int,
    val outPathLength: Int,
    val outPath: ByteArray,
    val advertisedName: String,
    val lastAdvertisement: Long,
    val latitude: Double,
    val longitude: Double,
    val lastModified: Long,
)

/** The outcome of a send: the chosen route, the expected end-to-end ACK, and a suggested timeout. */
@Serializable
data class MessageSentInfo(val route: Int, val expectedAck: ByteArray, val suggestedTimeoutMs: Long)

/** A direct message received from a contact. `snr` and `signature` may be absent. */
@Serializable
data class ContactMessage(
    val senderPublicKeyPrefix: ByteArray,
    val pathLength: Int,
    val textType: Int,
    val senderTimestamp: Long,
    val signature: ByteArray? = null,
    val text: String,
    val snr: Double? = null,
)

/** A message received on a channel. `snr` may be absent. */
@Serializable
data class ChannelMessage(
    val channelIndex: Int,
    val pathLength: Int,
    val textType: Int,
    val senderTimestamp: Long,
    val text: String,
    val snr: Double? = null,
)

/** A binary datagram received on a channel. */
@Serializable
data class ChannelDatagram(
    val channelIndex: Int,
    val pathLength: Int,
    val dataType: Int,
    val data: ByteArray,
    val snr: Double,
)

/** Core device counters. Mirrors `CoreStats`. */
@Serializable
data class CoreStats(val batteryMV: Int, val uptimeSeconds: Long, val errors: Int, val queueLength: Int)

/** Radio-link counters. Mirrors `RadioStats`. */
@Serializable
data class RadioStats(
    val noiseFloor: Int,
    val lastRSSI: Int,
    val lastSNR: Double,
    val txAirtimeSeconds: Long,
    val rxAirtimeSeconds: Long,
)

/** Packet counters. Mirrors `PacketStats`. */
@Serializable
data class PacketStats(
    val received: Long,
    val sent: Long,
    val floodTx: Long,
    val directTx: Long,
    val floodRx: Long,
    val directRx: Long,
    val receiveErrors: Long,
)

/** Auto-add policy. Mirrors `AutoAddConfig`. */
@Serializable
data class AutoAddConfig(val bitmask: Int, val maxHops: Int)

/** The persisted default flood scope. Mirrors `DefaultFloodScope`. */
@Serializable
data class DefaultFloodScope(val name: String, val scopeKey: ByteArray)

/** One allowed repeat-frequency range, in kHz. */
@Serializable
data class RepeatFreqRange(val lowerKHz: Long, val upperKHz: Long)

/** One hop in a trace path. `hashBytes` is absent for an unreported node. */
@Serializable
data class TraceNode(val hashBytes: ByteArray? = null, val snr: Double)

/** A completed trace. Mirrors `TraceInfo`. */
@Serializable
data class TraceInfo(val tag: Long, val authCode: Long, val flags: Int, val pathLength: Int, val path: List<TraceNode>)

/** A path-discovery response. Mirrors `PathInfo`. */
@Serializable
data class PathInfo(
    val publicKeyPrefix: ByteArray,
    val outPathLength: Int,
    val outPath: ByteArray,
    val inPathLength: Int,
    val inPath: ByteArray,
)

/**
 * A successful remote login. Mirrors `LoginInfo`. [serverTime] is the node's RTC (epoch seconds) carried in
 * the login response, or null when the firmware omits it; comparing it to the radio's clock exposes drift.
 */
@Serializable
data class LoginInfo(
    val permissions: Int,
    val isAdmin: Boolean,
    val publicKeyPrefix: ByteArray,
    val serverTime: Long? = null,
)

/** A repeater/room-server status frame. Mirrors `StatusResponse`. `layout` is repeater/roomServer. */
@Serializable
data class StatusResponse(
    val layout: String,
    val publicKeyPrefix: ByteArray,
    val battery: Int,
    val txQueueLength: Int,
    val noiseFloor: Int,
    val lastRSSI: Int,
    val packetsReceived: Long,
    val packetsSent: Long,
    val airtime: Long,
    val uptime: Long,
    val sentFlood: Long,
    val sentDirect: Long,
    val receivedFlood: Long,
    val receivedDirect: Long,
    val fullEvents: Int,
    val lastSNR: Double,
    val directDuplicates: Int,
    val floodDuplicates: Int,
    val rxAirtime: Long,
    val receiveErrors: Int,
    val roomServerPostedCount: Int? = null,
    val roomServerPostPushCount: Int? = null,
)

/**
 * A telemetry response. [points] is the reading: MeshCore's `LPPDecoder` parses the Cayenne LPP frame
 * bridge-side, so nothing here interprets telemetry bytes. [rawData] is the frame it was decoded from,
 * kept for the audit trail only.
 */
@Serializable
data class TelemetryResponse(
    val publicKeyPrefix: ByteArray,
    val tag: ByteArray? = null,
    val rawData: ByteArray,
    val points: List<LPPDataPoint> = emptyList(),
)

/** A binary (tag + data) response. */
@Serializable
data class BinaryResponse(val tag: ByteArray, val data: ByteArray)

/** One min/max/avg telemetry aggregate. */
@Serializable
data class MMADatum(val channel: Int, val type: String, val min: Double, val max: Double, val avg: Double)

/** A min/max/avg telemetry response. Mirrors `MMAResponse`. */
@Serializable
data class MMAResponse(val publicKeyPrefix: ByteArray, val tag: ByteArray, val data: List<MMADatum>)

/** One access-control entry. */
@Serializable
data class ACLEntry(val keyPrefix: ByteArray, val permissions: Int)

/** An access-control-list response. Mirrors `ACLResponse`. */
@Serializable
data class ACLResponse(val publicKeyPrefix: ByteArray, val tag: ByteArray, val entries: List<ACLEntry>)

/** One neighbour entry. */
@Serializable
data class Neighbour(val publicKeyPrefix: ByteArray, val secondsAgo: Long, val snr: Double)

/** A neighbours response. Mirrors `NeighboursResponse`. */
@Serializable
data class NeighboursResponse(
    val publicKeyPrefix: ByteArray,
    val tag: ByteArray,
    val totalCount: Int,
    val neighbours: List<Neighbour>,
)

/** Owner info from a repeater. Mirrors `OwnerInfoResponse`. */
@Serializable
data class OwnerInfoResponse(val firmwareVersion: String, val nodeName: String, val ownerInfo: String)

/** A raw received frame. Mirrors `RawDataInfo`. */
@Serializable
data class RawDataInfo(val snr: Double, val rssi: Int, val payload: ByteArray)

/** A raw log frame. `snr`/`rssi` may be absent. Mirrors `LogDataInfo`. */
@Serializable
data class LogDataInfo(val snr: Double? = null, val rssi: Int? = null, val payload: ByteArray)

/** A parsed RX log frame. Mirrors `ParsedRxLogData`. `routeType`/`payloadType` are enum raws. */
@Serializable
data class ParsedRxLogData(
    val snr: Double? = null,
    val rssi: Int? = null,
    val rawPayload: ByteArray,
    val routeType: Int,
    val payloadType: Int,
    val payloadVersion: Int,
    val payloadTypeBits: Int,
    val transportCode: ByteArray? = null,
    val pathLength: Int,
    val pathNodes: List<Int>,
    val packetPayload: ByteArray,
    val senderPubkeyPrefix: ByteArray? = null,
    val recipientPubkeyPrefix: ByteArray? = null,
    val packetHash: String,
)

/** A control-plane frame. Mirrors `ControlDataInfo`. */
@Serializable
data class ControlDataInfo(
    val snr: Double,
    val rssi: Int,
    val pathLength: Int,
    val payloadType: Int,
    val payload: ByteArray,
)

/** A passive-discover response. Mirrors `DiscoverResponse`. */
@Serializable
data class DiscoverResponse(
    val nodeType: Int,
    val snrIn: Double,
    val snr: Double,
    val rssi: Int,
    val pathLength: Int,
    val tag: ByteArray,
    val publicKey: ByteArray,
)

/** An advert-path response. Mirrors `AdvertPathResponse`. */
@Serializable
data class AdvertPathResponse(val recvTimestamp: Long, val pathLength: Int, val path: ByteArray)

/** Radio tuning parameters. Mirrors `TuningParamsResponse`. */
@Serializable
data class TuningParamsResponse(val rxDelayBase: Double, val airtimeFactor: Double)
