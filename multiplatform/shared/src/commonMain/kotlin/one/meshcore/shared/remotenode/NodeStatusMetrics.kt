package one.meshcore.shared.remotenode

import one.meshcore.shared.meshcore.StatusResponse

/** Capture-rate policy for node status snapshots, mirroring the Swift `NodeSnapshotPolicy`. */
object NodeSnapshotPolicy {
    /**
     * Minimum interval, in seconds, between persisted snapshots for the same node. A status, telemetry,
     * or neighbour capture arriving within this window of the latest snapshot enriches that row rather
     * than inserting a new one, so time-series points are spaced at least 15 minutes apart.
     */
    const val MINIMUM_INTERVAL_SECONDS: Double = 15.0 * 60.0
}

/**
 * The radio/room status fields captured in a snapshot, clamped to their storage widths. Bundling them
 * keeps the save and backfill paths in lockstep and performs the lossy conversions exactly once. Role
 * extras are supplied by the caller: repeaters pass [rxAirtimeSeconds]/[receiveErrors], rooms pass
 * [postedCount]/[postPushCount]; the other role's fields stay null. Mirrors the Swift `NodeStatusMetrics`.
 */
data class NodeStatusMetrics(
    val batteryMillivolts: Int?,
    val lastSNR: Double?,
    val lastRSSI: Int?,
    val noiseFloor: Int?,
    val uptimeSeconds: Long?,
    val rxAirtimeSeconds: Long?,
    val packetsSent: Long?,
    val packetsReceived: Long?,
    val receiveErrors: Long?,
    val sentDirect: Long?,
    val sentFlood: Long?,
    val receivedDirect: Long?,
    val receivedFlood: Long?,
    val directDuplicates: Long?,
    val floodDuplicates: Long?,
    val postedCount: Int? = null,
    val postPushCount: Int? = null,
) {
    companion object {
        private val RSSI_RANGE = Short.MIN_VALUE.toInt()..Short.MAX_VALUE.toInt()

        /**
         * Builds metrics from a decoded [status] response. `lastRSSI` and `noiseFloor` saturate into the
         * 16-bit range and the duplicate counters saturate at zero, matching the Swift `Int16`/`UInt32`
         * clamping. The wide packet/uptime counters arrive within range from the bridge and pass through.
         */
        fun fromStatus(
            status: StatusResponse,
            rxAirtimeSeconds: Long? = null,
            receiveErrors: Long? = null,
            postedCount: Int? = null,
            postPushCount: Int? = null,
        ): NodeStatusMetrics = NodeStatusMetrics(
            batteryMillivolts = status.battery,
            lastSNR = status.lastSNR,
            lastRSSI = status.lastRSSI.coerceIn(RSSI_RANGE),
            noiseFloor = status.noiseFloor.coerceIn(RSSI_RANGE),
            uptimeSeconds = status.uptime,
            rxAirtimeSeconds = rxAirtimeSeconds,
            packetsSent = status.packetsSent,
            packetsReceived = status.packetsReceived,
            receiveErrors = receiveErrors,
            sentDirect = status.sentDirect,
            sentFlood = status.sentFlood,
            receivedDirect = status.receivedDirect,
            receivedFlood = status.receivedFlood,
            directDuplicates = status.directDuplicates.coerceAtLeast(0).toLong(),
            floodDuplicates = status.floodDuplicates.coerceAtLeast(0).toLong(),
            postedCount = postedCount,
            postPushCount = postPushCount,
        )
    }
}
