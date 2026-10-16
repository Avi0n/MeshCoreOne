package one.meshcore.android

import one.meshcore.shared.connection.DeviceConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioTileLogicTest {
    @Test
    fun aReadyRadioIsActiveAndDisconnectsOnTap() {
        assertTrue(radioTileIsActive(DeviceConnectionState.READY))
        assertEquals(RadioTileClickAction.Disconnect, radioTileClickAction(DeviceConnectionState.READY))
    }

    @Test
    fun aDisconnectedRadioIsInactiveAndReconnectsOnTap() {
        assertFalse(radioTileIsActive(DeviceConnectionState.DISCONNECTED))
        assertEquals(
            RadioTileClickAction.Reconnect,
            radioTileClickAction(DeviceConnectionState.DISCONNECTED),
        )
    }

    @Test
    fun anInFlightConnectStaysClickableAndOpensTheApp() {
        for (state in listOf(
            DeviceConnectionState.CONNECTING,
            DeviceConnectionState.CONNECTED,
            DeviceConnectionState.SYNCING,
        )) {
            assertFalse(radioTileIsActive(state))
            assertEquals(RadioTileClickAction.OpenApp, radioTileClickAction(state))
        }
    }
}
