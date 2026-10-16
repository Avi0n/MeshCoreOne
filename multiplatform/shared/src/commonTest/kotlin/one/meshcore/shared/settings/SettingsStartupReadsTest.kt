package one.meshcore.shared.settings

import one.meshcore.shared.connection.DeviceConnectionState
import one.meshcore.shared.connection.SyncPhase
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * When a Settings screen may spend airtime re-reading the radio. Ported from iOS
 * `AppState.canRunSettingsStartupReads` and its `ConnectionUIStateTests` cases: a startup read must not
 * compete with the contact and channel sync that follows a connect.
 */
class SettingsStartupReadsTest {
    @Test
    fun aReadyRadioIsRead() {
        assertTrue(canRunSettingsStartupReads(DeviceConnectionState.READY, syncPhase = null))
    }

    @Test
    fun aDisconnectedRadioIsNotRead() {
        assertFalse(canRunSettingsStartupReads(DeviceConnectionState.DISCONNECTED, syncPhase = null))
        assertFalse(canRunSettingsStartupReads(DeviceConnectionState.CONNECTING, syncPhase = null))
    }

    @Test
    fun theContendedPhasesOfAFreshConnectAreLeftAlone() {
        assertFalse(canRunSettingsStartupReads(DeviceConnectionState.CONNECTED, syncPhase = SyncPhase.CONTACTS))
        assertFalse(canRunSettingsStartupReads(DeviceConnectionState.CONNECTED, syncPhase = SyncPhase.CHANNELS))
        assertFalse(canRunSettingsStartupReads(DeviceConnectionState.CONNECTED, syncPhase = null))
    }

    @Test
    fun theMessagePhaseIsQuietEnoughToRead() {
        assertTrue(canRunSettingsStartupReads(DeviceConnectionState.CONNECTED, syncPhase = SyncPhase.MESSAGES))
    }
}
