package one.meshcore.shared.simulator

import one.meshcore.shared.chat.pathdetail.PathEncoding
import one.meshcore.shared.model.RouteType
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.model.toUppercaseHex
import one.meshcore.shared.persistence.MessageRepeatEntity
import one.meshcore.shared.persistence.NodeStatusSnapshotEntity
import one.meshcore.shared.persistence.RxLogEntryEntity
import one.meshcore.shared.tools.DecryptStatus
import one.meshcore.shared.tools.RxLogPayloadType

internal data class LinkPreviewSeed(val messageID: Uuid, val url: String, val title: String, val imageData: ByteArray)

internal data class ReactedMessageSeed(val messageID: Uuid, val summary: String)

internal fun MockDataProvider.linkPreviewSeeds(): List<LinkPreviewSeed> = listOf(
    LinkPreviewSeed(
        messageID = aliceLinkPreviewMessageID,
        url = "https://meshcoreone.com/trails/skyline",
        title = "Skyline Ridge Trail Guide",
        imageData = demoImageData,
    ),
)

internal fun MockDataProvider.messagesWithRepeats(): List<Uuid> = listOf(frankRepeatMessageID)

internal fun MockDataProvider.messageRepeats(messageID: Uuid, now: Double): List<MessageRepeatEntity> {
    if (messageID != frankRepeatMessageID) return emptyList()
    return listOf(
        MessageRepeatEntity(
            id = Uuid.normalized("B0000000-0000-0000-0000-000000000001"),
            messageID = messageID,
            receivedAt = now - 255_590,
            pathNodes = FRANK_REPEAT_PATH_A,
            pathLength = PathEncoding.encodePathLen(1, 1),
            snr = 6.5,
            rssi = -92,
        ),
        MessageRepeatEntity(
            id = Uuid.normalized("B0000000-0000-0000-0000-000000000002"),
            messageID = messageID,
            receivedAt = now - 255_585,
            pathNodes = FRANK_REPEAT_PATH_B,
            pathLength = PathEncoding.encodePathLen(2, 1),
            snr = 4.2,
            rssi = -101,
        ),
        MessageRepeatEntity(
            id = Uuid.normalized("B0000000-0000-0000-0000-000000000003"),
            messageID = messageID,
            receivedAt = now - 255_580,
            pathNodes = FRANK_REPEAT_PATH_C,
            pathLength = PathEncoding.encodePathLen(1, 2),
            snr = 1.9,
            rssi = -108,
        ),
    )
}

internal fun MockDataProvider.rxLogEntries(now: Double): List<RxLogEntryEntity> = listOf(
    rxLogEntry(uniqueRxLogEntryID, now, UNIQUE_RX_OFFSET_SECONDS, UNIQUE_RX_PACKET, UNIQUE_REGION_NAME),
    rxLogEntry(ambiguousRxLogEntryID, now, AMBIGUOUS_RX_OFFSET_SECONDS, AMBIGUOUS_RX_PACKET, regionScope = null),
)

internal fun MockDataProvider.locationHistoryNodePublicKey(): ByteArray = mockPublicKey(seed = 80)

internal fun MockDataProvider.nodeStatusSnapshots(now: Double): List<NodeStatusSnapshotEntity> {
    val key = locationHistoryNodePublicKey()
    val track = LOCATION_HISTORY_TRACK
    return track.mapIndexed { index, fix ->
        val (minutesAgo, coord, altitude) = fix
        val progress = index.toDouble() / maxOf(track.lastIndex, 1)
        val millivolts = LOCATION_HISTORY_START_MILLIVOLTS -
            ((LOCATION_HISTORY_START_MILLIVOLTS - LOCATION_HISTORY_END_MILLIVOLTS) * progress).toInt()
        val hex = index.toString(SNAPSHOT_ID_RADIX).uppercase().padStart(SNAPSHOT_ID_WIDTH, '0')
        NodeStatusSnapshotEntity(
            id = Uuid.normalized("D0800000-0000-0000-0000-$hex"),
            timestamp = now - minutesAgo * 60,
            nodePublicKey = key,
            batteryMillivolts = millivolts,
            lastSNR = 8.5 - progress * 3,
            lastRSSI = -72,
            noiseFloor = -108,
            uptimeSeconds = (6 * 3600 + index * 1800).toLong(),
            latitude = coord.first,
            longitude = coord.second,
            altitude = altitude,
        )
    }
}

private fun MockDataProvider.rxLogEntry(
    id: Uuid,
    now: Double,
    receivedAtOffset: Long,
    packetPayload: ByteArray,
    regionScope: String?,
): RxLogEntryEntity = RxLogEntryEntity(
    id = id,
    radioID = simulatorDeviceID,
    receivedAtEpochMs = ((now + receivedAtOffset) * 1000).toLong(),
    snr = 8.0,
    rssi = -70,
    routeType = RouteType.TC_FLOOD.raw,
    payloadType = RxLogPayloadType.GROUP_TEXT,
    payloadVersion = 0,
    pathLength = 1,
    packetHash = packetPayload.toUppercaseHex(),
    packetPayloadSize = packetPayload.size,
    decryptStatusRawValue = DecryptStatus.SUCCESS.raw,
    regionScope = regionScope,
    channelIndex = PUBLIC_CHANNEL_INDEX,
    channelName = "Public",
    senderTimestamp = null,
    rawPayload = RX_RAW_PAYLOAD,
    packetPayload = packetPayload,
    pathNodes = RX_PATH_NODE,
    transportCode = RX_TRANSPORT_CODE,
    payloadTypeBits = 5,
)

private const val UNIQUE_RX_OFFSET_SECONDS = -3_600L
private const val AMBIGUOUS_RX_OFFSET_SECONDS = -1_800L
private const val MINUTES_PER_DAY = 1_440.0
private const val LOCATION_HISTORY_START_MILLIVOLTS = 4050
private const val LOCATION_HISTORY_END_MILLIVOLTS = 3860
private const val SNAPSHOT_ID_RADIX = 16
private const val SNAPSHOT_ID_WIDTH = 12

private val UNIQUE_RX_PACKET: ByteArray
    get() = byteArrayOf(0x01, 0xAA.toByte(), 0xBB.toByte())

private val AMBIGUOUS_RX_PACKET: ByteArray
    get() = byteArrayOf(0x02, 0xCC.toByte(), 0xDD.toByte())

private val RX_TRANSPORT_CODE: ByteArray
    get() = byteArrayOf(0x11, 0x22, 0x33, 0x44)

private val RX_RAW_PAYLOAD: ByteArray
    get() = byteArrayOf(0x15, 0x01, 0x02, 0x03)

private val RX_PATH_NODE: ByteArray
    get() = byteArrayOf(0x42)

private val FRANK_REPEAT_PATH_A: ByteArray
    get() = byteArrayOf(0x31)

private val FRANK_REPEAT_PATH_B: ByteArray
    get() = byteArrayOf(0x8F.toByte(), 0x2C)

private val FRANK_REPEAT_PATH_C: ByteArray
    get() = byteArrayOf(0x44, 0x71)

private val LOCATION_HISTORY_TRACK: List<Triple<Double, Pair<Double, Double>, Double?>> = listOf(
    Triple(180 * MINUTES_PER_DAY + 30, 37.7690 to -122.4830, 30.0),
    Triple(180 * MINUTES_PER_DAY + 15, 37.7695 to -122.4800, 35.0),
    Triple(180 * MINUTES_PER_DAY, 37.7700 to -122.4770, 40.0),
    Triple(55 * MINUTES_PER_DAY + 30, 37.7585 to -122.4270, 25.0),
    Triple(55 * MINUTES_PER_DAY + 15, 37.7600 to -122.4255, 28.0),
    Triple(55 * MINUTES_PER_DAY, 37.7615 to -122.4240, 32.0),
    Triple(14 * MINUTES_PER_DAY + 30, 37.7980 to -122.4650, 50.0),
    Triple(14 * MINUTES_PER_DAY + 15, 37.7995 to -122.4620, 58.0),
    Triple(14 * MINUTES_PER_DAY, 37.8010 to -122.4590, 64.0),
    Triple(300.0, 37.7350 to -122.4770, 55.0),
    Triple(285.0, 37.7340 to -122.4780, 58.0),
    Triple(270.0, 37.7330 to -122.4785, 60.0),
    Triple(255.0, 37.7325 to -122.4790, 57.0),
    Triple(240.0, 37.7320 to -122.4795, 54.0),
    Triple(90.0, 37.7260 to -122.4800, 48.0),
    Triple(72.0, 37.7230 to -122.4798, 50.0),
    Triple(54.0, 37.7200 to -122.4796, 46.0),
    Triple(36.0, 37.7180 to -122.4795, null),
    Triple(18.0, 37.7160 to -122.4794, 41.0),
    Triple(5.0, 37.7149 to -122.4794, 42.0),
)
