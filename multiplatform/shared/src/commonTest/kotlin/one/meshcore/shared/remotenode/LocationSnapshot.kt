package one.meshcore.shared.remotenode

import one.meshcore.shared.backup.NodeStatusSnapshotDto
import one.meshcore.shared.model.Uuid

/** A status snapshot whose id encodes the timestamp, so a location test can vary only the fix. */
internal fun locationSnapshot(
    timestamp: Double,
    lat: Double?,
    lon: Double?,
    alt: Double? = null,
): NodeStatusSnapshotDto = NodeStatusSnapshotDto(
    id = Uuid.normalized("N0000000-0000-0000-0000-${timestamp.toLong().toString().padStart(12, '0')}"),
    timestamp = timestamp,
    nodePublicKey = ByteArray(32) { 0x01 },
    latitude = lat,
    longitude = lon,
    altitude = alt,
)
