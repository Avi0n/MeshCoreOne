package one.meshcore.shared.connection

import kotlin.test.Test
import kotlin.test.assertEquals

class ConnectionDropDumpTest {
    @Test
    fun disconnectReasonLabelsMatchIosRawValues() {
        assertEquals("user initiated disconnect", DisconnectReason.USER_INITIATED.dumpLabel())
        assertEquals("resync failed after 3 attempts", DisconnectReason.RESYNC_FAILED.dumpLabel())
        assertEquals("WiFi address changed", DisconnectReason.WIFI_ADDRESS_CHANGE.dumpLabel())
    }

    @Test
    fun disconnectingAndDisconnectedLinesMatchIosShape() {
        assertEquals(
            "Disconnecting from device (reason: user initiated disconnect, transport: bluetooth, " +
                "device: AABBCCDD, initialState: ready, intent: wantsConnection)",
            disconnectingFromDeviceLine(
                reason = "user initiated disconnect",
                transport = "bluetooth",
                device = "AABBCCDD",
                initialState = "ready",
                intent = "wantsConnection",
            ),
        )
        assertEquals(
            "Disconnected (reason: user initiated disconnect, transport: bluetooth, " +
                "device: AABBCCDD, initialState: ready, finalState: disconnected, " +
                "intent: userDisconnected)",
            disconnectedLine(
                drop = ConnectionDropContext(
                    reason = "user initiated disconnect",
                    transport = "bluetooth",
                    device = "AABBCCDD",
                    initialState = "ready",
                    intent = "userDisconnected",
                ),
                finalState = "disconnected",
            ),
        )
    }

    @Test
    fun connectionStateDumpNamesAreStable() {
        assertEquals("ready", connectionStateDumpName(ConnectionState.Ready("dev")))
        assertEquals("autoReconnecting", connectionStateDumpName(ConnectionState.Reconnecting("dev", 2)))
        assertEquals("disconnected", connectionStateDumpName(ConnectionState.Disconnected))
        assertEquals("bluetooth", TransportType.BLUETOOTH.dumpName())
        assertEquals("none", (null as TransportType?).dumpName())
        assertEquals("AABBCCDD", dumpDeviceHandle("AABBCCDD-EEEE"))
        assertEquals("none", dumpDeviceHandle(null))
    }
}
