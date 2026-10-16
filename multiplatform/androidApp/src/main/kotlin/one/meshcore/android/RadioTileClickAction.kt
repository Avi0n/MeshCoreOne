package one.meshcore.android

import one.meshcore.shared.connection.DeviceConnectionState

/** Click and active-state mapping for the Quick Settings radio tile. */
enum class RadioTileClickAction {
    Disconnect,
    Reconnect,
    OpenApp,
}

fun radioTileClickAction(state: DeviceConnectionState): RadioTileClickAction = when (state) {
    DeviceConnectionState.READY -> RadioTileClickAction.Disconnect
    DeviceConnectionState.DISCONNECTED -> RadioTileClickAction.Reconnect
    else -> RadioTileClickAction.OpenApp
}

fun radioTileIsActive(state: DeviceConnectionState): Boolean = state == DeviceConnectionState.READY
