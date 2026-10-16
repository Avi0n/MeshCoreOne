package one.meshcore.shared.connection

import kotlin.test.Test
import kotlin.test.assertEquals

class BleLifecycleDumpTest {
    @Test
    fun lifecycleLinesMatchIos() {
        assertEquals(
            "[BLE] Lifecycle transition: entering background, " +
                "transport: bluetooth, connectionIntent: wantsConnection, connectionState: ready",
            bleLifecycleEnteringBackgroundLine("bluetooth", "wantsConnection", "ready"),
        )
        assertEquals(
            "[BLE] Lifecycle transition: becoming active, " +
                "transport: wifi, connectionIntent: userDisconnected, connectionState: disconnected",
            bleLifecycleBecomingActiveLine("wifi", "userDisconnected", "disconnected"),
        )
        assertEquals(
            "[BLE] Lifecycle transition complete: backgrounded",
            BLE_LIFECYCLE_BACKGROUNDED_COMPLETE_LINE,
        )
        assertEquals(
            "[BLE] Lifecycle transition complete: active health check finished, connectionState: ready",
            bleLifecycleActiveCompleteLine("ready"),
        )
        assertEquals(
            "[BLE] ConnectionManager: re-armed watchdog on foreground while disconnected",
            BLE_REARMED_WATCHDOG_FOREGROUND,
        )
        assertEquals(RESYNC_SKIP_FOREGROUND_TRIGGER, "Resync loop already running, skipping foreground trigger")
        assertEquals(
            FOREGROUND_SYNC_FAILED_START_RESYNC,
            "Foreground return: sync state is failed, starting resync loop",
        )
    }
}
