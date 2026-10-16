package one.meshcore.shared.settings

import one.meshcore.shared.connection.DeviceConnectionState
import one.meshcore.shared.connection.SyncPhase
import one.meshcore.shared.navigation.SettingsDestination

/**
 * Whether a Settings screen may re-read the radio right now, ported from iOS
 * `AppState.canRunSettingsStartupReads`.
 *
 * A ready radio is free. A radio still working through the connect sync is not: the contact and channel
 * phases saturate the link, and a settings read queued behind them either times out or delays the sync
 * the user is waiting on. The message phase is quiet enough to slip a read into.
 */
fun canRunSettingsStartupReads(state: DeviceConnectionState, syncPhase: SyncPhase?): Boolean =
    state == DeviceConnectionState.READY ||
        (state == DeviceConnectionState.CONNECTED && syncPhase == SyncPhase.MESSAGES)

/**
 * Whether [destination] renders values the radio owns, and so re-reads them when it appears. The three
 * that do are the ones iOS re-reads: Radio (`RadioPresetSection`), Advanced (`AdvancedSettingsView`) and
 * Location (`LocationSettingsSection`). The rest render app-owned state, which no radio read can change.
 */
fun settingsDestinationReadsRadio(destination: SettingsDestination?): Boolean = when (destination) {
    SettingsDestination.RADIO,
    SettingsDestination.ADVANCED,
    SettingsDestination.LOCATION,
    -> true
    else -> false
}
