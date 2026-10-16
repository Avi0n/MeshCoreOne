package one.meshcore.shared.connection

import kotlin.test.Test
import kotlin.test.assertEquals

class BleReconnectDumpTest {
    @Test
    fun reconnectAndCompleteLinesMatchIos() {
        assertEquals("[BLE] Watchdog attempting reconnection", BLE_WATCHDOG_ATTEMPTING)
        assertEquals("Connection complete - device ready", BLE_CONNECTION_COMPLETE)
        assertEquals(
            "[BLE] Connect caller cancelled after Ready; leaving live session",
            BLE_CONNECT_CANCEL_AFTER_READY,
        )
        assertEquals("Reconnection succeeded on attempt 2", bleReconnectionSucceededLine(2))
        assertEquals(
            "[BLE] Reconnection attempt 1 failed - error: timeout",
            bleReconnectAttemptFailedLine(1, "timeout"),
        )
    }

    @Test
    fun foregroundAndPowerLinesMatchIos() {
        assertEquals(
            "[BLE] Skipping foreground reconnect: reconnect/session rebuild already in progress",
            BLE_FOREGROUND_SKIP_IN_PROGRESS,
        )
        assertEquals(
            "[BLE] Attempting foreground reconnection to AABBCCDD",
            bleForegroundAttemptingLine("AABBCCDD-EEEE"),
        )
        assertEquals(
            "[BLE] Bluetooth powered on: attempting reconnection to AABBCCDD",
            blePoweredOnAttemptingLine("AABBCCDD"),
        )
        assertEquals("[BLE] Bluetooth state changed: poweredOn", bleBluetoothStateChangedLine(true))
        assertEquals("[BLE] Bluetooth state changed: poweredOff", bleBluetoothStateChangedLine(false))
        assertEquals(
            "[BLE] Skipping foreground reconnect: Bluetooth is powered off",
            BLE_FOREGROUND_SKIP_POWERED_OFF,
        )
        assertEquals(
            "[BLE] Watchdog skipping: Bluetooth powered off",
            BLE_WATCHDOG_SKIP_POWERED_OFF,
        )
        assertEquals(
            "[BLE] Auto-reconnect UI timeout (60s) fired - transitioning UI to disconnected " +
                "(reconnect continues in background)",
            bleReconnectUiTimeoutLine(60),
        )
        assertEquals(
            "[BLE] Opportunistic reconnect skipped (pairing): pairing in progress",
            BLE_OPPORTUNISTIC_SKIP_PAIRING,
        )
        assertEquals(
            "[BLE] Ignoring connection loss for DEADDEAD — connecting to LIVELIVE",
            bleIgnoringConnectionLossLine("DEADDEAD", "LIVELIVE"),
        )
        assertEquals("Connecting to device: AABBCCDD", connectingToDeviceLine("AABBCCDD"))
        assertEquals(
            "[BLE] State → .connected (transport connected for device: AABBCCDD)",
            bleStateConnectedLine("AABBCCDD"),
        )
        assertEquals(
            "[BLE] Connected BLE app stack missing listeners; restarting event pipeline for AABBCCDD",
            bleMissingListenersRestartLine("AABBCCDD"),
        )
        assertEquals(
            "[BLE] Detected stale connection state on foreground: connectionState=ready " +
                "but BLE disconnected, triggering cleanup",
            bleForegroundStaleConnectionLine("ready"),
        )
    }
}
