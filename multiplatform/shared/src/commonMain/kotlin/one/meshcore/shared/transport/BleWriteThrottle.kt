package one.meshcore.shared.transport

/** The ATT write type for one outbound frame. Mirrors CoreBluetooth's `.withResponse`/`.withoutResponse`. */
enum class BleWriteKind {
    /** ATT Write Request: one round-trip per write, acknowledged. The universal fallback path. */
    WITH_RESPONSE,

    /** ATT Write Command: unacknowledged, pipelineable. Only radios advertising the capability use it. */
    WITHOUT_RESPONSE,
}

/**
 * The pure outbound-write policy the Kable transport applies, ported from `iOSBLETransport` /
 * `BLEStateMachine`. It captures the nRF52-vs-ESP32 asymmetry: a radio whose write characteristic
 * advertises `writeWithoutResponse` (nRF52 / Adafruit BLEUart) uses unacknowledged Write Commands and
 * skips pacing, while a write-only characteristic (ESP32) degrades to acknowledged Write Requests
 * paced by a per-write delay that protects the ESP32 RX queue during bursts.
 *
 * Isolated from Kable so the decision is unit-testable without a peripheral.
 */
object BleWriteThrottle {
    /**
     * The write type for a frame. A caller-requested unacknowledged write is honored only when the
     * characteristic actually advertises the capability; otherwise it degrades to an acknowledged
     * Write Request, exactly as the Swift `sendWithoutResponse` falls back to `claimWriteSlot`.
     */
    fun resolveWriteKind(requestedWithoutResponse: Boolean, txAdvertisesWriteWithoutResponse: Boolean): BleWriteKind =
        if (requestedWithoutResponse && txAdvertisesWriteWithoutResponse) {
            BleWriteKind.WITHOUT_RESPONSE
        } else {
            BleWriteKind.WITH_RESPONSE
        }

    /**
     * The pacing delay to apply before a write of [kind]. Only the acknowledged path is paced; a Write
     * Command deliberately skips pacing, because pacing exists to protect the ESP32 RX queue during
     * acknowledged bursts and Write Commands are an nRF52-only path.
     */
    fun pacingDelayMillis(kind: BleWriteKind, configuredPacingMillis: Long): Long =
        if (kind == BleWriteKind.WITH_RESPONSE) configuredPacingMillis else 0
}
