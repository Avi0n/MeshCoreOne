package one.meshcore.shared.persistence

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import one.meshcore.shared.backup.DiscoveredNodeDto
import one.meshcore.shared.contact.DiscoveredNodeRecord
import one.meshcore.shared.model.Uuid

/**
 * Persistence mirror of the SwiftData `DiscoveredNode` model: the passively-heard Discover list,
 * `radioID`-scoped and capped per radio. Indexed on `(radioID, publicKey)` (dedup) and
 * `(radioID, lastHeardEpochSeconds)` (cap trim / recency sort), matching iOS `#Index`.
 */
@Entity(
    tableName = "discovered_node",
    indices = [
        Index(value = ["radioID", "publicKey"]),
        Index(value = ["radioID", "lastHeardEpochSeconds"]),
    ],
)
data class DiscoveredNodeEntity(
    @PrimaryKey val id: Uuid,
    val radioID: Uuid,
    val publicKey: ByteArray,
    val name: String,
    val typeRawValue: Int,
    val lastHeardEpochSeconds: Long,
    val lastAdvertTimestamp: Long,
    val latitude: Double,
    val longitude: Double,
    val outPathLength: Int,
    val outPath: ByteArray,
    val inboundHopCount: Int? = null,
    val inboundHopAdvertTimestamp: Long? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DiscoveredNodeEntity) return false
        return sameIdentity(other) && sameAdvert(other) && sameRoute(other)
    }

    private fun sameIdentity(other: DiscoveredNodeEntity): Boolean =
        id == other.id && radioID == other.radioID && publicKey.contentEquals(other.publicKey)

    private fun sameAdvert(other: DiscoveredNodeEntity): Boolean = name == other.name &&
        typeRawValue == other.typeRawValue &&
        lastHeardEpochSeconds == other.lastHeardEpochSeconds

    private fun sameRoute(other: DiscoveredNodeEntity): Boolean = samePlace(other) && samePath(other) && sameHop(other)

    private fun samePlace(other: DiscoveredNodeEntity): Boolean = lastAdvertTimestamp == other.lastAdvertTimestamp &&
        latitude == other.latitude &&
        longitude == other.longitude

    private fun samePath(other: DiscoveredNodeEntity): Boolean = outPathLength == other.outPathLength &&
        outPath.contentEquals(other.outPath) &&
        inboundHopCount == other.inboundHopCount

    private fun sameHop(other: DiscoveredNodeEntity): Boolean =
        inboundHopAdvertTimestamp == other.inboundHopAdvertTimestamp

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + radioID.hashCode()
        result = 31 * result + publicKey.contentHashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + typeRawValue
        result = 31 * result + lastHeardEpochSeconds.hashCode()
        result = 31 * result + lastAdvertTimestamp.hashCode()
        result = 31 * result + latitude.hashCode()
        result = 31 * result + longitude.hashCode()
        result = 31 * result + outPathLength
        result = 31 * result + outPath.contentHashCode()
        result = 31 * result + (inboundHopCount ?: 0)
        result = 31 * result + inboundHopAdvertTimestamp.hashCode()
        return result
    }
}

fun DiscoveredNodeEntity.toRecord(): DiscoveredNodeRecord = DiscoveredNodeRecord(
    id = id,
    radioID = radioID,
    publicKey = publicKey,
    name = name,
    typeRawValue = typeRawValue,
    lastHeardEpochSeconds = lastHeardEpochSeconds,
    lastAdvertTimestamp = lastAdvertTimestamp,
    latitude = latitude,
    longitude = longitude,
    outPathLength = outPathLength,
    outPath = outPath,
    inboundHopCount = inboundHopCount,
    inboundHopAdvertTimestamp = inboundHopAdvertTimestamp,
)

fun DiscoveredNodeRecord.toEntity(): DiscoveredNodeEntity = DiscoveredNodeEntity(
    id = id,
    radioID = radioID,
    publicKey = publicKey,
    name = name,
    typeRawValue = typeRawValue,
    lastHeardEpochSeconds = lastHeardEpochSeconds,
    lastAdvertTimestamp = lastAdvertTimestamp,
    latitude = latitude,
    longitude = longitude,
    outPathLength = outPathLength,
    outPath = outPath,
    inboundHopCount = inboundHopCount,
    inboundHopAdvertTimestamp = inboundHopAdvertTimestamp,
)

fun DiscoveredNodeEntity.toDto(): DiscoveredNodeDto = DiscoveredNodeDto(
    id = id,
    radioID = radioID,
    publicKey = publicKey,
    name = name,
    typeRawValue = typeRawValue,
    lastHeard = lastHeardEpochSeconds.toDouble(),
    lastAdvertTimestamp = lastAdvertTimestamp,
    latitude = latitude,
    longitude = longitude,
    outPathLength = outPathLength,
    outPath = outPath,
    inboundHopCount = inboundHopCount,
    inboundHopAdvertTimestamp = inboundHopAdvertTimestamp,
)

fun DiscoveredNodeDto.toEntity(): DiscoveredNodeEntity = DiscoveredNodeEntity(
    id = id,
    radioID = radioID,
    publicKey = publicKey,
    name = name,
    typeRawValue = typeRawValue,
    lastHeardEpochSeconds = lastHeard.toLong(),
    lastAdvertTimestamp = lastAdvertTimestamp,
    latitude = latitude,
    longitude = longitude,
    outPathLength = outPathLength,
    outPath = outPath,
    inboundHopCount = inboundHopCount,
    inboundHopAdvertTimestamp = inboundHopAdvertTimestamp,
)
