package one.meshcore.shared.transport

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import one.meshcore.shared.connection.LinkPeripheralState

/**
 * The single GATT peripheral behind [BleTransport], isolated from Kable so the transport's orchestration
 * (connect watchdog, write throttling, notification framing, disconnect detection) is unit-testable with
 * a fake. `KableBlePeripheral` (androidMain) is the production implementation over Kable; a fake stands
 * in for the transport tests. The implementation owns scanning, service discovery, and MTU — this seam
 * begins once a specific peripheral has been resolved.
 */
interface BlePeripheral {
    /** The connection state, mapped to the watchdog's vocabulary. */
    val state: StateFlow<LinkPeripheralState>

    /** Whether the write (tx) characteristic advertises `writeWithoutResponse` (nRF52). */
    val advertisesWriteWithoutResponse: Boolean

    /**
     * Connects, discovers services, negotiates MTU, and enables RX notifications.
     * Suspends until TX and RX are present and RX notify is on, or throws
     * [CharacteristicNotFoundException] without disconnecting if the UART table is missing.
     */
    suspend fun connect()

    /** Disconnects. Idempotent. */
    suspend fun disconnect()

    /** The rx-characteristic notifications; each emission is one complete frame. */
    fun observeNotifications(): Flow<ByteArray>

    /** Writes one frame to the tx characteristic with the given ATT write type. */
    suspend fun write(bytes: ByteArray, kind: BleWriteKind)

    /**
     * The last disconnect status, for the iOS `handleDidDisconnect` dump. Default is none: only a
     * production peripheral that still holds the GATT status can fill it in.
     */
    fun lastDisconnectError(): String? = null

    /**
     * Why the last disconnect happened. Default is an ordinary loss; a production peripheral that
     * still holds a definitive-bond GATT status reports [LinkDropReason.BOND_LOST].
     */
    fun lastDropReason(): LinkDropReason = LinkDropReason.CONNECTION_LOST
}
