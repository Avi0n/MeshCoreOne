package one.meshcore.shared.persistence

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.tools.DecryptStatus
import one.meshcore.shared.tools.RxLogEntry

/**
 * Room row for one received-packet log entry, mirroring the SwiftData `RxLogEntry` model. Indexed on
 * `(radioID, receivedAtEpochMs)` because every read is a newest-first page for one partition and every
 * prune is an oldest-first page for the same partition. Diagnostic and regenerable, so it stays out of
 * the backup envelope, matching iOS.
 *
 * Decrypted message text has no column here and must not gain one: a decrypted body is derived from the
 * stored payload on load, so plaintext traffic never outlives the process.
 */
@Entity(
    tableName = "rx_log_entry",
    indices = [Index(value = ["radioID", "receivedAtEpochMs"])],
)
data class RxLogEntryEntity(
    @PrimaryKey val id: Uuid,
    val radioID: Uuid,
    val receivedAtEpochMs: Long,
    val snr: Double?,
    val rssi: Int?,
    val routeType: Int,
    val payloadType: Int,
    val payloadVersion: Int,
    val pathLength: Int,
    val packetHash: String,
    val packetPayloadSize: Int,
    val decryptStatusRawValue: Int,
    val regionScope: String?,
    val channelIndex: Int?,
    val channelName: String?,
    val senderTimestamp: Long?,
    val rawPayload: ByteArray,
    val packetPayload: ByteArray,
    val pathNodes: ByteArray,
    val transportCode: ByteArray?,
    val payloadTypeBits: Int,
)

fun RxLogEntryEntity.toDomain(): RxLogEntry = RxLogEntry(
    id = id,
    radioID = radioID,
    receivedAtEpochMs = receivedAtEpochMs,
    snr = snr,
    rssi = rssi,
    routeType = routeType,
    payloadType = payloadType,
    pathLength = pathLength,
    packetHash = packetHash,
    packetPayloadSize = packetPayloadSize,
    decryptStatus = DecryptStatus.fromRaw(decryptStatusRawValue),
    regionScope = regionScope,
    channelIndex = channelIndex,
    channelName = channelName,
    senderTimestamp = senderTimestamp,
    rawPayload = rawPayload,
    packetPayload = packetPayload,
    pathNodes = pathNodes,
    payloadVersion = payloadVersion,
    transportCode = transportCode,
    payloadTypeBits = payloadTypeBits,
)

fun RxLogEntry.toEntity(): RxLogEntryEntity = RxLogEntryEntity(
    id = id,
    radioID = radioID,
    receivedAtEpochMs = receivedAtEpochMs,
    snr = snr,
    rssi = rssi,
    routeType = routeType,
    payloadType = payloadType,
    payloadVersion = payloadVersion,
    pathLength = pathLength,
    packetHash = packetHash,
    packetPayloadSize = packetPayloadSize,
    decryptStatusRawValue = decryptStatus.raw,
    regionScope = regionScope,
    channelIndex = channelIndex,
    channelName = channelName,
    senderTimestamp = senderTimestamp,
    rawPayload = rawPayload,
    packetPayload = packetPayload,
    pathNodes = pathNodes,
    transportCode = transportCode,
    payloadTypeBits = payloadTypeBits,
)
