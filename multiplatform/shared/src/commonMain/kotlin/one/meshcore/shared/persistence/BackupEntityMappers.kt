package one.meshcore.shared.persistence

import one.meshcore.shared.backup.MessageRepeatDto
import one.meshcore.shared.backup.NodeStatusSnapshotDto
import one.meshcore.shared.backup.SavedTracePathDto
import one.meshcore.shared.backup.TracePathRunDto

fun MessageRepeatEntity.toDto(): MessageRepeatDto = MessageRepeatDto(
    id = id,
    messageID = messageID,
    receivedAt = receivedAt,
    pathNodes = pathNodes,
    pathLength = pathLength,
    snr = snr,
    rssi = rssi,
    rxLogEntryID = rxLogEntryID,
)

fun MessageRepeatDto.toEntity(): MessageRepeatEntity = MessageRepeatEntity(
    id = id,
    messageID = messageID,
    receivedAt = receivedAt,
    pathNodes = pathNodes,
    pathLength = pathLength,
    snr = snr,
    rssi = rssi,
    rxLogEntryID = rxLogEntryID,
)

fun SavedTracePathEntity.toDto(runs: List<TracePathRunDto>): SavedTracePathDto = SavedTracePathDto(
    id = id,
    radioID = radioID,
    name = name,
    pathBytes = pathBytes,
    hashSize = hashSize,
    createdDate = createdDate,
    runs = runs,
)

fun TracePathRunEntity.toDto(): TracePathRunDto = TracePathRunDto(
    id = id,
    date = date,
    success = success,
    roundTripMs = roundTripMs,
    hopsSNR = hopsSNR,
)

fun NodeStatusSnapshotEntity.toDto(): NodeStatusSnapshotDto = NodeStatusSnapshotDto(
    id = id,
    timestamp = timestamp,
    nodePublicKey = nodePublicKey,
    batteryMillivolts = batteryMillivolts,
    lastSNR = lastSNR,
    lastRSSI = lastRSSI,
    noiseFloor = noiseFloor,
    uptimeSeconds = uptimeSeconds,
    rxAirtimeSeconds = rxAirtimeSeconds,
    packetsSent = packetsSent,
    packetsReceived = packetsReceived,
    receiveErrors = receiveErrors,
    sentDirect = sentDirect,
    sentFlood = sentFlood,
    receivedDirect = receivedDirect,
    receivedFlood = receivedFlood,
    directDuplicates = directDuplicates,
    floodDuplicates = floodDuplicates,
    postedCount = postedCount,
    postPushCount = postPushCount,
    neighborSnapshots = neighborSnapshots,
    telemetryEntries = telemetryEntries,
    latitude = latitude,
    longitude = longitude,
    altitude = altitude,
)

fun NodeStatusSnapshotDto.toEntity(): NodeStatusSnapshotEntity = NodeStatusSnapshotEntity(
    id = id,
    timestamp = timestamp,
    nodePublicKey = nodePublicKey,
    batteryMillivolts = batteryMillivolts,
    lastSNR = lastSNR,
    lastRSSI = lastRSSI,
    noiseFloor = noiseFloor,
    uptimeSeconds = uptimeSeconds,
    rxAirtimeSeconds = rxAirtimeSeconds,
    packetsSent = packetsSent,
    packetsReceived = packetsReceived,
    receiveErrors = receiveErrors,
    sentDirect = sentDirect,
    sentFlood = sentFlood,
    receivedDirect = receivedDirect,
    receivedFlood = receivedFlood,
    directDuplicates = directDuplicates,
    floodDuplicates = floodDuplicates,
    postedCount = postedCount,
    postPushCount = postPushCount,
    neighborSnapshots = neighborSnapshots,
    telemetryEntries = telemetryEntries,
    latitude = latitude,
    longitude = longitude,
    altitude = altitude,
)
