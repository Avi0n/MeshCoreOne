package one.meshcore.shared.room.ui

import kotlinx.coroutines.flow.update

fun RoomSettingsViewModel.setName(value: String) {
    helper.name = value
    settingsState.update {
        it.copy(name = value, identityModified = helper.identitySettingsModified, identityValidation = null)
    }
}

fun RoomSettingsViewModel.setLatitudeText(value: String) {
    setCoordinateText(value, assign = { helper.latitude = it }) { state, text, modified ->
        state.copy(latitudeText = text, identityValidation = null, identityModified = modified)
    }
}

fun RoomSettingsViewModel.setLongitudeText(value: String) {
    setCoordinateText(value, assign = { helper.longitude = it }) { state, text, modified ->
        state.copy(longitudeText = text, identityValidation = null, identityModified = modified)
    }
}

/** Stores the coordinate first, then publishes the text, cleared validation, and modified flag together. */
private fun RoomSettingsViewModel.setCoordinateText(
    value: String,
    assign: (Double?) -> Unit,
    write: (RoomSettingsUiState, String, Boolean) -> RoomSettingsUiState,
) {
    assign(value.toDoubleOrNull())
    val modified = helper.identitySettingsModified
    settingsState.update { write(it, value, modified) }
}

/** Fills the coordinate fields from a map-picked point, mirroring the iOS `setLocationFromPicker`. */
fun RoomSettingsViewModel.setLocationFromPicker(latitude: Double, longitude: Double) {
    helper.latitude = latitude
    helper.longitude = longitude
    settingsState.update {
        it.copy(
            latitudeText = latitude.toString(),
            longitudeText = longitude.toString(),
            identityModified = helper.identitySettingsModified,
            identityValidation = null,
        )
    }
}

fun RoomSettingsViewModel.setOwnerInfo(value: String) {
    helper.ownerInfo = value
    settingsState.update {
        it.copy(
            ownerInfo = value,
            ownerInfoTooLong = helper.isOwnerInfoTooLong,
            contactInfoModified = helper.contactInfoSettingsModified,
        )
    }
}

fun RoomSettingsViewModel.setNewPassword(value: String) {
    helper.newPassword = value
    settingsState.update { it.copy(newPassword = value) }
}

fun RoomSettingsViewModel.setConfirmPassword(value: String) {
    helper.confirmPassword = value
    settingsState.update { it.copy(confirmPassword = value) }
}
