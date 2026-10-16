package one.meshcore.shared.notification

import one.meshcore.shared.battery.BatteryDisplay
import one.meshcore.shared.connection.DeviceConnectionState
import one.meshcore.shared.meshcore.BatteryInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class StatusQueryDialogTest {
    @Test
    fun connectedWithBatterySpeaksNameAndPercent() {
        for (state in listOf(
            DeviceConnectionState.READY,
            DeviceConnectionState.SYNCING,
            DeviceConnectionState.CONNECTED,
        )) {
            val battery = BatteryInfo(level = SEEDED_LEVEL)
            val spoken = statusQueryDialogText(
                snapshot(
                    state = state,
                    connectedName = "Base Camp",
                    battery = battery,
                ),
                ENGLISH,
            )
            val percent = BatteryDisplay.percent(battery, OCV_ARRAY)
            assertEquals(
                "Base Camp is connected. Battery is about $percent%, as of the last reading.",
                spoken,
            )
            assertFalse(spoken.contains(SEEDED_LEVEL.toString()))
            assertEquals(EXPECTED_PERCENT, percent)
        }
    }

    @Test
    fun absentBatteryReportsNoReadingNotZeroPercent() {
        val spoken = statusQueryDialogText(
            snapshot(
                state = DeviceConnectionState.READY,
                connectedName = "Mains Node",
                battery = BatteryInfo(level = 0),
            ),
            ENGLISH,
        )
        assertEquals("Mains Node is connected. No battery reading is available.", spoken)
        assertFalse(spoken.contains("0%"))
    }

    @Test
    fun missingBatteryReadingReportsNoReading() {
        val spoken = statusQueryDialogText(
            snapshot(
                state = DeviceConnectionState.READY,
                connectedName = "No Poll Yet",
                battery = null,
            ),
            ENGLISH,
        )
        assertEquals("No Poll Yet is connected. No battery reading is available.", spoken)
    }

    @Test
    fun blankConnectedNameUsesOfflineRadioName() {
        val spoken = statusQueryDialogText(
            snapshot(
                state = DeviceConnectionState.READY,
                connectedName = "  ",
                offlineName = "Field Radio",
            ),
            ENGLISH,
        )
        assertEquals("Field Radio is connected. No battery reading is available.", spoken)
    }

    @Test
    fun connectingSpeaksConnecting() {
        val spoken = statusQueryDialogText(
            snapshot(
                state = DeviceConnectionState.CONNECTING,
                connectedName = null,
                offlineName = "Field Radio",
            ),
            ENGLISH,
        )
        assertEquals("Field Radio is connecting.", spoken)
    }

    @Test
    fun disconnectedWithKnownRadioSpeaksOfflineName() {
        val spoken = statusQueryDialogText(
            snapshot(
                state = DeviceConnectionState.DISCONNECTED,
                connectedName = null,
                offlineName = "Last Radio",
            ),
            ENGLISH,
        )
        assertEquals("Last Radio is not connected.", spoken)
    }

    @Test
    fun neverConnectedReportsNoRadio() {
        val spoken = statusQueryDialogText(
            snapshot(
                state = DeviceConnectionState.DISCONNECTED,
                connectedName = null,
                offlineName = null,
            ),
            ENGLISH,
        )
        assertEquals("No radio is connected.", spoken)
    }

    @Test
    fun notReadyWhenAppHasNoState() {
        val spoken = statusQueryDialogText(
            snapshot(appReady = false, state = DeviceConnectionState.READY, connectedName = "X"),
            ENGLISH,
        )
        assertEquals("MeshCore One is still starting up. Try again in a moment.", spoken)
    }

    private fun snapshot(
        appReady: Boolean = true,
        state: DeviceConnectionState,
        connectedName: String? = null,
        offlineName: String? = null,
        battery: BatteryInfo? = null,
    ) = StatusQuerySnapshot(
        appReady = appReady,
        state = state,
        connectedName = connectedName,
        offlineName = offlineName,
        battery = battery,
        ocvArray = OCV_ARRAY,
    )

    private companion object {
        val OCV_ARRAY = listOf(4200, 4060, 3980, 3920, 3870, 3820, 3790, 3770, 3730, 3680, 3000)
        const val SEEDED_LEVEL = 4100
        const val EXPECTED_PERCENT = 93
        val ENGLISH = StatusQueryCopies(
            notReady = "MeshCore One is still starting up. Try again in a moment.",
            connectedWithBattery = "%1\$s is connected. Battery is about %2\$d%%, as of the last reading.",
            connectedNoBattery = "%s is connected. No battery reading is available.",
            connecting = "%s is connecting.",
            disconnectedNamed = "%s is not connected.",
            disconnectedUnknown = "No radio is connected.",
            radioFallbackName = "Your radio",
        )
    }
}
