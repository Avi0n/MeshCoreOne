package one.meshcore.android

import one.meshcore.shared.connection.shouldStartConnectionOnRadioPresence
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioPresenceRestoreTest {
    @Test
    fun matchingWantedRadioStartsRestore() {
        assertTrue(
            shouldStartConnectionOnRadioPresence(
                appearedHandle = RADIO,
                lastWantedBleHandle = RADIO.lowercase(),
                userDisconnected = false,
            ),
        )
    }

    @Test
    fun leftoverAssociationDoesNotStartRestore() {
        assertFalse(
            shouldStartConnectionOnRadioPresence(
                appearedHandle = LEFTOVER,
                lastWantedBleHandle = RADIO,
                userDisconnected = false,
            ),
        )
    }

    @Test
    fun explicitDisconnectDoesNotStartRestore() {
        assertFalse(
            shouldStartConnectionOnRadioPresence(
                appearedHandle = RADIO,
                lastWantedBleHandle = RADIO,
                userDisconnected = true,
            ),
        )
    }

    private companion object {
        const val RADIO = "AA:BB:CC:DD:EE:FF"
        const val LEFTOVER = "11:22:33:44:55:66"
    }
}
