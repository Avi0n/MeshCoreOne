package one.meshcore.shared.transport

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The nRF52-vs-ESP32 write-type and pacing decisions ported from `iOSBLETransport`/`BLEStateMachine`.
 */
class BleWriteThrottleTest {
    @Test
    fun requestedUnacknowledgedWriteUsesWriteCommandWhenTheCharacteristicAdvertisesIt() {
        assertEquals(
            BleWriteKind.WITHOUT_RESPONSE,
            BleWriteThrottle.resolveWriteKind(requestedWithoutResponse = true, txAdvertisesWriteWithoutResponse = true),
        )
    }

    @Test
    fun requestedUnacknowledgedWriteDegradesToWriteRequestOnAWriteOnlyCharacteristic() {
        // ESP32: the tx characteristic does not advertise writeWithoutResponse, so the caller's
        // request degrades to the acknowledged path (as the Swift sendWithoutResponse falls back).
        assertEquals(
            BleWriteKind.WITH_RESPONSE,
            BleWriteThrottle.resolveWriteKind(
                requestedWithoutResponse = true,
                txAdvertisesWriteWithoutResponse = false,
            ),
        )
    }

    @Test
    fun anAcknowledgedRequestAlwaysUsesWriteRequest() {
        assertEquals(
            BleWriteKind.WITH_RESPONSE,
            BleWriteThrottle.resolveWriteKind(
                requestedWithoutResponse = false,
                txAdvertisesWriteWithoutResponse = true,
            ),
        )
    }

    @Test
    fun onlyTheAcknowledgedPathIsPaced() {
        val pacing = 20L
        assertEquals(pacing, BleWriteThrottle.pacingDelayMillis(BleWriteKind.WITH_RESPONSE, pacing))
        assertEquals(0L, BleWriteThrottle.pacingDelayMillis(BleWriteKind.WITHOUT_RESPONSE, pacing))
    }
}
