package one.meshcore.shared.transport

import one.meshcore.shared.meshcore.MeshTransport

/**
 * A physical radio link the [one.meshcore.shared.connection.ConnectionManager] owns: the outbound
 * [MeshTransport.sendBytes] sink plus lifecycle control ([connect]/[disconnect]) and an inbound
 * [MeshLinkListener] the implementation drives as bytes arrive and the connection state changes.
 *
 * This is the Kotlin analogue of the Swift `iOSBLETransport`/`MeshTransport` split: `send` out, and
 * `receivedData` plus the connect/disconnect signals in. Kable (`KableBleTransport`) implements it on
 * Android; a fake implements it in tests. The session itself only ever sees [MeshTransport.sendBytes];
 * the manager wires the listener to the session's inbound hooks.
 */
interface MeshLink : MeshTransport {
    /**
     * Whether the write characteristic advertises ATT Write Commands. iOS reads this from the live
     * GATT table after discovery; default false is the Swift `MeshTransport` opt-in (ESP32 stays on
     * acknowledged serial channel reads).
     */
    val advertisesWriteWithoutResponse: Boolean get() = false

    /**
     * Whether channel-read pipelining is viable. BLE follows [advertisesWriteWithoutResponse]; TCP
     * opts in even though it has no write characteristic (iOS `WiFiTransport.supportsPipelinedReads`).
     */
    val supportsPipelinedReads: Boolean get() = advertisesWriteWithoutResponse

    /**
     * Whether the physical link is up right now. Default true so test fakes that do not observe GATT
     * stay treated as live. BLE reads the peripheral; TCP reads its connected flag. Foreground health
     * uses this to catch a Ready session whose drop collector died in the background.
     */
    val isConnected: Boolean get() = true

    /** Installs (or clears, with `null`) the listener the link drives. Set before [connect]. */
    fun setListener(listener: MeshLinkListener?)

    /**
     * Establishes the physical link. Suspends until connected (or throws on failure). On success the
     * caller reports the link up to the session; a link that fails asynchronously later surfaces
     * through [MeshLinkListener.onDisconnected] instead.
     */
    suspend fun connect()

    /** Tears the physical link down. Idempotent and safe to call when already disconnected. */
    suspend fun disconnect()
}

/**
 * Thrown by a [MeshLink.connect] that fails because the peer's bond was invalidated, so the manager
 * routes it to guided re-pair rather than the ordinary retry loop. Mirrors the Swift
 * `BLEError.authenticationFailed` escalation.
 */
class BondLostException(message: String) : Exception(message)

/**
 * Thrown when a BLE connect is refused because the adapter is off. iOS `BLEError.bluetoothPoweredOff`
 * exits `connectWithRetry` without spending the retry budget or opening the circuit breaker.
 */
class BluetoothPoweredOffException(message: String = "Bluetooth is turned off") : Exception(message)

/**
 * Thrown by a [MeshLink.connect] that failed with an encryption-timeout-class GATT status (the Android
 * analog of `CBError.encryptionTimedOut`: statuses 133/8/22 near the edge of range). Transient on its
 * own, so the manager retries, but the manager tallies these because an encryption-timeout-dominated
 * reconnect budget with no recently verified session is the ambiguous signature of an invalidated bond.
 */
class EncryptionTimeoutException(message: String) : Exception(message)

/**
 * Thrown when Nordic UART TX/RX are missing after GATT is up. Mirrors Swift
 * `BLEError.characteristicNotFound`: the peripheral stays connected; only a discovery timeout
 * cancels the GATT link.
 */
class CharacteristicNotFoundException(
    message: String = "Unable to communicate with device. Please try reconnecting.",
) : Exception(message)

/**
 * RX notify completed without enabling notifications. iOS holds `.autoReconnecting` when
 * `isNotifying` is false and the error is nil so the standing pending connect is not cancelled.
 */
class NotificationsNotEnabledException(
    message: String = "RX notifications were not enabled.",
    cause: Throwable? = null,
) : Exception(message, cause)

/** Empty GATT or a silent notify miss during auto-reconnect: hold, do not cancel the pending connect. */
fun shouldHoldAutoReconnectGatt(error: Throwable): Boolean =
    error is CharacteristicNotFoundException || error is NotificationsNotEnabledException

/** Why a link dropped, so the manager can tell an ordinary loss from an unrecoverable bond failure. */
enum class LinkDropReason {
    /** An ordinary transport drop; eligible for the auto-reconnect/backoff loop. */
    CONNECTION_LOST,

    /** The peer's bond was invalidated; needs a guided re-pair, not a silent retry. */
    BOND_LOST,
}

/**
 * Inbound callbacks a [MeshLink] drives. The manager adapts these to the session's inbound hooks
 * (`deliverReceived`, `reportConnected`, `reportDisconnected`). Implementations
 * must not assume the listener suspends — these are called from the link's own callback context.
 */
interface MeshLinkListener {
    /** One complete inbound frame arrived. Each BLE notification is a whole frame (companion protocol). */
    fun onReceived(bytes: ByteArray)

    /** The link dropped after being established, with the reason that decides recovery. */
    fun onDisconnected(reason: LinkDropReason)
}
