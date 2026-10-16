package one.meshcore.shared.ui

import one.meshcore.shared.connection.DeviceConnectionState

/**
 * Whether a connection-rung change means an established link went away, rather than an attempt that
 * never landed. Mirrors the iOS `ConnectionUIState.handleDisconnect` announcement, which fires when the
 * services of a live connection are torn down; a connect that fails out of `CONNECTING` never had them.
 * An auto-reconnect counts as a loss, because the services are gone for the whole retry.
 */
fun isConnectionLost(previous: DeviceConnectionState, current: DeviceConnectionState): Boolean =
    previous.isConnected && !current.isConnected

/**
 * Whether a connection-rung change means a link is now established. Mirrors the iOS
 * `ConnectionUIState.wireCallbacks` announcement, which speaks on every connection whose services get
 * wired, first pairing included, so this is the rising edge to [isConnectionLost]'s falling one.
 */
fun isDeviceReconnected(previous: DeviceConnectionState, current: DeviceConnectionState): Boolean =
    !previous.isConnected && current.isConnected

/**
 * Whether a list opening should say its rows are cached: only while disconnected from a radio the app
 * already knows, so a first run with nothing paired stays quiet. Mirrors the iOS
 * `announceOfflineStateIfNeeded` guard on both the chats and the contacts list.
 */
fun shouldAnnounceCachedData(connectionState: DeviceConnectionState, hasKnownRadio: Boolean): Boolean =
    connectionState == DeviceConnectionState.DISCONNECTED && hasKnownRadio

/**
 * Whether a room session change means the room came back. Mirrors the iOS `onChange(of:)`, which speaks
 * only on a false-to-true edge, so opening a room that is already connected stays silent.
 */
fun isRoomReconnected(previouslyConnected: Boolean, isConnected: Boolean): Boolean = isConnected && !previouslyConnected
