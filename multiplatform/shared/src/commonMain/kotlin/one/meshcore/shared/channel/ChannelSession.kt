package one.meshcore.shared.channel

import one.meshcore.shared.meshcore.ChannelInfo

/**
 * Firmware `ProtocolError` sub-codes a channel command can surface. Pinned to the wire values; the
 * production session adapter maps a bridge `DEVICE_ERROR` to a [ChannelDeviceException] carrying one of
 * these, and [ChannelService] reads [NOT_FOUND] as "slot unconfigured" (returns null, non-retryable).
 */
object ChannelProtocolErrorCode {
    const val NOT_FOUND: Int = 0x02
}

/**
 * A device error surfaced by a [ChannelSession] command, mirroring the Swift `MeshCoreError.deviceError(code)`.
 * [firmwareCode] is the radio's `ProtocolError` sub-code (see [ChannelProtocolErrorCode]). The production
 * adapter maps a bridge device error to this; fakes throw it directly.
 */
class ChannelDeviceException(val firmwareCode: Int) : Exception("device error $firmwareCode")

/**
 * A per-command response timeout, mirroring the Swift `MeshCoreError.timeout`. Retryable: [ChannelService]
 * re-reads the slot with exponential backoff before giving up, and it counts toward the circuit breaker.
 */
class ChannelTimeoutException : Exception("channel command timed out")

/**
 * A transport send timeout, mirroring the Swift `WiFiTransportError.sendTimeout`. Counted separately from
 * a response timeout (so a cascade of stuck sends is diagnosable), retryable, and breaker-counting.
 */
class ChannelSendTimeoutException : Exception("channel command send timed out")

/**
 * A transport-layer failure (disconnect, dropped write, connection failure), mirroring the Swift
 * `WiFiTransportError`/`MeshTransportError` non-timeout cases. Not retryable, but counts toward the
 * circuit breaker so a broken link aborts a sync fast instead of grinding every slot.
 */
class ChannelTransportException(message: String) : Exception(message)

/**
 * The two channel reads [ChannelService] issues during a sync: one bounded-window pipelined batch and a
 * single-slot serial read. A hard transport failure throws [ChannelTransportException]/[ChannelSendTimeoutException];
 * an idle stall in the batch returns the slots that answered plus the [missing] indices to reconcile serially.
 */
data class ChannelBatchRead(val received: List<ChannelInfo>, val missing: List<Int>)

/**
 * The device-session seam [ChannelService] depends on: the channel commands the service issues. A narrow
 * port over the androidMain-only `MeshCoreSession` so the service stays in commonMain and is
 * independently testable, mirroring [one.meshcore.shared.contact.ContactSession]. A device failure is
 * raised as a [ChannelDeviceException]; a timeout or transport failure as the matching exception above.
 * [one.meshcore.shared.connection.ChannelSessionAdapter] bridges the live session.
 */
interface ChannelSession {
    /** Reads one channel slot; the returned [ChannelInfo.index] must equal [index]. */
    suspend fun getChannel(index: Int): ChannelInfo

    /**
     * Reads [indices] in one bounded-window exchange (nRF52/BLE, ESP32/WiFi). A hard send failure throws;
     * an idle stall returns the answered slots plus the indices to reconcile with serial [getChannel] reads.
     */
    suspend fun getChannels(indices: List<Int>): ChannelBatchRead

    /** Writes a channel slot (name + 16-byte secret), waiting for the device ack. */
    suspend fun setChannel(index: Int, name: String, secret: ByteArray)
}
