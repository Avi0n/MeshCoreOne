package one.meshcore.shared.connection

/**
 * Whether the WiFi heartbeat probe should be skipped: paused while syncing, which already generates
 * link traffic the staleness watchdog can measure against. Pure so the gating is unit-testable. A resync
 * loop holds the connection at [ConnectionState.Syncing], so it is covered by the same gate, matching
 * Swift's separate resync pause.
 */
fun shouldPauseWiFiHeartbeat(state: ConnectionState, channelRetryActive: Boolean = false): Boolean =
    state is ConnectionState.Syncing || channelRetryActive

/**
 * A cancelled connect caller (sheet leaving composition, activity going away) must tear down a
 * half-built link. Ready is already monitored, so the same cancel must not drop the live session.
 */
fun shouldAbandonCancelledConnect(state: ConnectionState): Boolean = state !is ConnectionState.Ready

/**
 * Whether the BLE reconnect loop may start from this rung. [ConnectionState.BondLost] is included
 * because iOS keeps that episode at `.disconnected` and re-arms on foreground; a dead bond the user
 * repaired in system settings would otherwise stay parked until they tap the pairing alert again.
 */
fun canArmBleReconnect(state: ConnectionState): Boolean =
    state is ConnectionState.Disconnected || state is ConnectionState.BondLost

/**
 * After the Connecting pill times out, state is Disconnected while the BLE watchdog still
 * holds autoConnect. Foreground must not steal that wait with a WiFi Case 2 connect.
 */
fun shouldRunForegroundWifiOrBleArm(canArmBle: Boolean, bleReconnectInProgress: Boolean): Boolean =
    canArmBle && !bleReconnectInProgress

/** True on the adapter-off to adapter-on edge. iOS reconnects from `centralManagerDidUpdateState`. */
fun isBluetoothPowerOnEdge(wasEnabled: Boolean, isEnabled: Boolean): Boolean = !wasEnabled && isEnabled
