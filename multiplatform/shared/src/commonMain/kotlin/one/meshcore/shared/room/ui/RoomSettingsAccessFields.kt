package one.meshcore.shared.room.ui

import kotlinx.coroutines.flow.update

fun RoomSettingsViewModel.setGuestPassword(value: String) {
    setRoomAccess({ controller.guestPassword = value }) { copy(guestPassword = value) }
}

fun RoomSettingsViewModel.setAllowReadOnly(value: Boolean) {
    setRoomAccess({ controller.allowReadOnly = value }) { copy(allowReadOnly = value) }
}

/** Stores the room-access value, then publishes it with the modified flag. */
private fun RoomSettingsViewModel.setRoomAccess(
    assign: () -> Unit,
    write: RoomSettingsUiState.() -> RoomSettingsUiState,
) {
    assign()
    settingsState.update { state -> state.write().copy(roomAccessModified = controller.roomAccessModified) }
}

fun RoomSettingsViewModel.setAdvertIntervalText(value: String) {
    setIntervalText(value, { controller.advertIntervalMinutes = it }) { copy(advertIntervalText = it) }
}

fun RoomSettingsViewModel.setFloodAdvertIntervalText(value: String) {
    setIntervalText(value, { controller.floodAdvertIntervalHours = it }) { copy(floodAdvertIntervalText = it) }
}

fun RoomSettingsViewModel.setFloodMaxHopsText(value: String) {
    setIntervalText(value, { controller.floodMaxHops = it }) { copy(floodMaxHopsText = it) }
}

/** Stores the integer before modified is read. An empty value stores null and still clears validation. */
private fun RoomSettingsViewModel.setIntervalText(
    value: String,
    assign: (Int?) -> Unit,
    write: RoomSettingsUiState.(String) -> RoomSettingsUiState,
) {
    assign(value.toIntOrNull())
    val modified = controller.behaviorSettingsModified
    settingsState.update { state ->
        state.write(value).copy(behaviorModified = modified, behaviorValidation = null)
    }
}
