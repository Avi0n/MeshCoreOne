package one.meshcore.shared.transport

import kotlin.test.Test
import kotlin.test.assertEquals

class BleDropDumpTest {
    @Test
    fun didDisconnectLineMatchesIosShape() {
        assertEquals(
            "[BLE] Did disconnect: AABBCCDD, peripheralState: disconnected, " +
                "isReconnecting: false, phase: ready, elapsed: 12.50s, error: none",
            bleDidDisconnectLine(
                BleDisconnectSnapshot(
                    handle = "AABBCCDD-EEEE-FFFF-0000-111111111111",
                    peripheralState = "disconnected",
                    isReconnecting = false,
                    phase = "ready",
                    elapsedSeconds = "12.50",
                    error = "none",
                ),
            ),
        )
    }

    @Test
    fun connectionLostAndStateLinesMatchIos() {
        assertEquals(
            "[BLE] Connection lost: AABBCCDD, currentState: ready, error: CONNECTION_LOST",
            bleConnectionLostLine("AABBCCDD-EEEE", "ready", "CONNECTION_LOST"),
        )
        assertEquals(
            "[BLE] State → .disconnected (connection loss for device: AABBCCDD)",
            bleStateDisconnectedOnLossLine("AABBCCDD-EEEE"),
        )
        assertEquals("[BLE] disconnect: state → .disconnected", BLE_DISCONNECT_STATE_LINE)
    }

    @Test
    fun elapsedFormatsTwoFractionDigits() {
        assertEquals("0.00", formatElapsedSeconds(0.0))
        assertEquals("12.50", formatElapsedSeconds(12.5))
        assertEquals("1.02", formatElapsedSeconds(1.029))
    }
}
