package one.meshcore.shared.connection

import kotlin.test.Test
import kotlin.test.assertEquals

class WiFiDropDumpTest {
    @Test
    fun heartbeatAndDropLinesMatchIos() {
        assertEquals("WiFi heartbeat skipped while sync activity is active", WIFI_HEARTBEAT_SKIPPED)
        assertEquals(
            "WiFi heartbeat timeout during sync activity treated as inconclusive",
            WIFI_HEARTBEAT_INCONCLUSIVE,
        )
        assertEquals("WiFi heartbeat failed: timeout", wifiHeartbeatFailedLine("timeout"))
        assertEquals("WiFi connection lost: CONNECTION_LOST", wifiConnectionLostLine("CONNECTION_LOST"))
        assertEquals(
            "[WiFi] State → .connecting (WiFi disconnection, starting reconnection)",
            WIFI_STATE_CONNECTING_ON_DROP,
        )
    }

    @Test
    fun reconnectAndConnectLinesMatchIos() {
        assertEquals("Suppressing WiFi reconnection: too soon after last attempt", WIFI_RECONNECT_SUPPRESSED)
        assertEquals("WiFi reconnection timeout after 30s", WIFI_RECONNECT_TIMEOUT)
        assertEquals("WiFi reconnect attempt 2", wifiReconnectAttemptLine(2))
        assertEquals("WiFi reconnection succeeded", WIFI_RECONNECT_SUCCEEDED)
        assertEquals("WiFi reconnect failed: boom", wifiReconnectFailedLine("boom"))
        assertEquals("Connecting via WiFi to 192.168.1.1:5000", wifiConnectingLine("192.168.1.1", 5000))
        assertEquals("WiFi connection complete - device ready", WIFI_CONNECTION_COMPLETE)
        assertEquals(
            "WiFi reconnection already in progress on foreground",
            WIFI_FOREGROUND_RECONNECT_IN_PROGRESS,
        )
        assertEquals("WiFi foreground reconnect to 10.0.0.2:4000", wifiForegroundReconnectLine("10.0.0.2", 4000))
        assertEquals("WiFi foreground reconnect failed: boom", wifiForegroundReconnectFailedLine("boom"))
        assertEquals("WiFi connection died while backgrounded", WIFI_FOREGROUND_TRANSPORT_DEAD)
    }
}
